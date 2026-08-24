package dev.itayp.nescioquid.openrouter

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The request factory the client uses by default.
 *
 * [SimpleClientHttpRequestFactory] specifically: its read timeout becomes the socket's `SO_TIMEOUT`,
 * so it fires on a *stalled read of the response body* — which is the failure that matters to
 * [AiClient.chatStream], where the body is a long-lived stream. The JDK-based factory's read timeout
 * only bounds *obtaining* the response, so a provider that opens a stream and then goes silent would
 * hang the collector indefinitely under it.
 */
internal fun timeoutRequestFactory(connectTimeout: Duration, readTimeout: Duration): ClientHttpRequestFactory =
    SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(connectTimeout.toMillis().toInt())
        setReadTimeout(readTimeout.toMillis().toInt())
    }

/**
 * Builds a `RestClient` pointed at OpenRouter with bearer auth.
 *
 * [builder] null means "build our own", which is what carries the timeouts. A builder passed in owns
 * its transport config and is used as given — overriding its request factory would replace whatever
 * it was supplied for (a `MockRestServiceServer`, a tuned HTTP client), so [readTimeout] is ignored
 * in that case.
 */
internal fun openRouterRestClient(
    properties: AiClientProperties,
    builder: RestClient.Builder?,
    readTimeout: Duration,
): RestClient = (
    builder ?: RestClient.builder().requestFactory(timeoutRequestFactory(properties.connectTimeout, readTimeout))
    )
    .baseUrl(properties.baseUrl)
    .defaultHeader("Authorization", "Bearer ${properties.apiKey.trim()}")
    .build()

/**
 * Whether a failed attempt is worth repeating: a server-side fault or a rate limit. Every other
 * status — a malformed request, a bad key, an unsupported parameter — fails the same way however
 * many times it is sent.
 *
 * Note `CancellationException` is a `RuntimeException` on the JVM and is deliberately *not*
 * retryable, so a cancelled call unwinds immediately rather than being retried.
 */
internal fun isRetryable(e: RuntimeException): Boolean =
    e is HttpServerErrorException || (e is HttpClientErrorException && e.statusCode.value() == 429)

/**
 * Runs [attempt] under the client's single retry policy: up to [maxAttempts] tries, doubling from
 * [initialDelayMs], repeating only what [isRetryable] accepts and rethrowing everything else at once.
 *
 * `inline` on purpose. It is the one thing that lets the blocking and streaming paths share a policy
 * rather than keep two copies of it: because the lambdas are inlined into the caller they inherit its
 * suspend context, so the blocking path passes `Thread::sleep` while `AiClient.chatStream` passes
 * `{ delay(it) }` — which a normal higher-order function could not accept.
 */
internal inline fun <T> retrying(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 2000,
    onRetry: (attempt: Int, e: RuntimeException) -> Unit = { _, _ -> },
    sleep: (Long) -> Unit,
    attempt: () -> T,
): T {
    var delayMs = initialDelayMs
    var lastException: RuntimeException? = null
    repeat(maxAttempts) { index ->
        try {
            return attempt()
        } catch (e: RuntimeException) {
            if (!isRetryable(e)) throw e
            onRetry(index + 1, e)
            lastException = e
            if (index < maxAttempts - 1) sleep(delayMs)
            delayMs *= 2
        }
    }
    throw lastException!!
}

/**
 * The shared OpenRouter transport: connection setup, the retry policy, and the [AiCallGate] /
 * [AiCallListener] seams that make a call an *accounted* call.
 *
 * One bean serves every modality client ([AiClient], [ImageClient], and whatever `/videos` or
 * `/embeddings` clients follow), so they share connection pools and — more importantly — a single
 * definition of what "one accounted call" means. A modality client is then just the endpoint path
 * plus its own DTOs.
 */
@Component
class OpenRouterTransport(
    private val properties: AiClientProperties,
    private val callGate: AiCallGate,
    private val callListener: AiCallListener,
    // Injectable so tests can bind a MockRestServiceServer, as ModelCapabilityService already does.
    restClientBuilder: RestClient.Builder? = null,
) {
    private val log = LoggerFactory.getLogger(OpenRouterTransport::class.java)

    private val client = openRouterRestClient(properties, restClientBuilder, properties.readTimeout)

    // Image generation routinely outruns the read timeout tuned for text completion, so it gets its
    // own client. As with streamClient below, an injected builder owns its transport config, leaving
    // nothing to vary — so all three collapse to one client in tests.
    private val imageClient = if (restClientBuilder != null) {
        client
    } else {
        openRouterRestClient(properties, null, properties.imageReadTimeout)
    }

    /**
     * A client purely for the streaming path, carrying the much tighter idle timeout without
     * shortening the blocking path's budget for a whole generation.
     */
    val streamClient: RestClient = if (restClientBuilder != null) {
        client
    } else {
        openRouterRestClient(properties, null, properties.streamIdleTimeout)
    }

    /**
     * Sends one blocking, fully accounted call: the gate runs first and may throw to refuse it,
     * then the request is POSTed to [path] under the retry policy, and the listener is notified
     * exactly once either way.
     *
     * This is the whole of [AiClient.chat] and [ImageClient.generate]; a new blocking modality needs
     * only its DTOs and a one-line call here.
     */
    fun <RESP : AiResponse> call(
        context: AiCallContext,
        request: AiRequest,
        path: String,
        responseType: Class<RESP>,
    ): RESP {
        gateCheck(context, request)
        val response = try {
            retrying(
                onRetry = { attempt, e -> log.warn("AI API call failed (attempt $attempt/3): ${statusOf(e)} $path") },
                sleep = Thread::sleep,
            ) {
                log.debug("Sending request to AI API: path=$path, model=${request.model}")
                clientFor(path).post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(responseType)!!
            }
        } catch (e: RuntimeException) {
            recordFailure(context, request)
            throw e
        }
        recordSuccess(context, request, response)
        return response
    }

    /**
     * Opens a streaming request to [path], retrying the *connection* under the same policy [call]
     * uses. Only connection establishment is retried: once events have been delivered to a collector
     * they cannot be un-delivered, so a stream that breaks mid-flight fails the call.
     *
     * Uses `exchange(..., close = false)` to get the live response with its body unconsumed — the
     * caller owns closing it. Because `exchange` does not apply the default status handler, a non-2xx
     * response is turned here into the same exception types [call] produces, which is also what feeds
     * it to the shared [isRetryable] decision.
     */
    suspend fun openStream(
        request: AiRequest,
        path: String,
    ): RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse = retrying(
        onRetry = { attempt, e -> log.warn("AI API stream connect failed (attempt $attempt/3): ${statusOf(e)}") },
        sleep = { delay(it) },
    ) {
        val response = streamClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(request)
            .exchange({ _, res -> res }, false)

        if (!response.statusCode.isError) {
            response
        } else {
            // Drain and close before throwing: the body is the error payload, and a retry needs the
            // connection released either way.
            val statusText = runCatching { response.statusText }.getOrDefault("")
            val headers = response.headers
            val body = response.use { runCatching { it.body.readAllBytes() }.getOrDefault(ByteArray(0)) }
            throw statusException(response.statusCode, statusText, headers, body)
        }
    }

    /** Runs the pre-call gate. Exposed for [AiClient.chatStream], which cannot use [call]. */
    fun gateCheck(context: AiCallContext, request: AiRequest) = callGate.beforeCall(context, request)

    /** Notifies the listener of a success. Exposed for [AiClient.chatStream]. */
    fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse) =
        callListener.recordSuccess(context, request, response)

    /** Notifies the listener of a failure. Exposed for [AiClient.chatStream]. */
    fun recordFailure(context: AiCallContext, request: AiRequest) =
        callListener.recordFailure(context, request)

    private fun clientFor(path: String) = if (path == IMAGES_PATH) imageClient else client

    private fun statusOf(e: RuntimeException) = when (e) {
        is HttpClientErrorException -> e.statusCode.toString()
        is HttpServerErrorException -> e.statusCode.toString()
        else -> e.javaClass.simpleName
    }

    private fun statusException(
        status: HttpStatusCode,
        statusText: String,
        headers: HttpHeaders,
        body: ByteArray,
    ): RuntimeException = when {
        status.is4xxClientError -> HttpClientErrorException.create(status, statusText, headers, body, null)
        else -> HttpServerErrorException.create(status, statusText, headers, body, null)
    }
}

internal const val COMPLETIONS_PATH = "/chat/completions"
internal const val IMAGES_PATH = "/images"
