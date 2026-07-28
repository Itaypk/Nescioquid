package dev.itayp.nescioquid.openrouter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.core.JacksonException
import java.io.IOException
import java.time.Duration
import tools.jackson.module.kotlin.jacksonObjectMapper

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

@Component
class AiClient(
    private val properties: AiClientProperties,
    private val callGate: AiCallGate,
    private val callListener: AiCallListener,
    // Injectable so tests can bind a MockRestServiceServer, as ModelCapabilityService already does.
    // Null means "build our own", which is what carries the timeouts; a builder passed in here owns
    // its transport config and is used as given for both paths, since overriding its request factory
    // would replace whatever it was supplied for (a MockRestServiceServer, a tuned HTTP client).
    restClientBuilder: RestClient.Builder? = null,
) {
    private val log = LoggerFactory.getLogger(AiClient::class.java)

    private fun build(builder: RestClient.Builder) = builder
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.apiKey.trim()}")
        .build()

    private val client = build(
        restClientBuilder
            ?: RestClient.builder()
                .requestFactory(timeoutRequestFactory(properties.connectTimeout, properties.readTimeout)),
    )

    // A second client purely for the streaming path, so it can carry the much tighter idle timeout
    // without shortening the blocking path's budget for a whole generation. When a builder is
    // injected there is nothing to vary — the caller owns the transport — so both share one client.
    private val streamClient = if (restClientBuilder != null) {
        client
    } else {
        build(
            RestClient.builder()
                .requestFactory(timeoutRequestFactory(properties.connectTimeout, properties.streamIdleTimeout)),
        )
    }

    // Private to the streaming path: chunks are parsed off the SSE body ourselves rather than
    // through the RestClient's message converters, since the body is a stream of JSON objects
    // rather than one document.
    private val chunkMapper = jacksonObjectMapper()

    /**
     * Sends a chat request to the AI API. The [request] is the source of truth for everything on the
     * wire, including [ChatRequest.reasoning] — the caller sets it (the library applies no central
     * reasoning policy). [context] attributes the call for usage accounting and is the carrier for
     * the pre-call gate. The gate runs first and may throw to refuse the call; otherwise usage is
     * recorded for both success and failure.
     */
    fun chat(request: ChatRequest, context: AiCallContext): ChatResponse {
        callGate.beforeCall(context, request)
        val response = try {
            withRetry {
                log.debug("Sending chat request to AI API: model=${request.model}, messages=${request.messages.size}")
                client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatResponse::class.java)!!
            }
        } catch (e: RuntimeException) {
            callListener.recordFailure(context, request)
            throw e
        }
        callListener.recordSuccess(context, request, response)
        return response
    }

    /**
     * The streaming counterpart of [chat]: emits the model's output as it is generated, then a
     * terminal [ChatStreamEvent.Completed] carrying the same aggregated [ChatResponse] [chat] would
     * have returned for this request.
     *
     * The returned flow is **cold** — nothing is sent until it is collected, and each collection is
     * one independent call (one gate check, one listener notification). [ChatRequest.stream] and
     * [ChatRequest.usage] are set here rather than by the caller; everything else on [request] is
     * sent as given.
     *
     * Failure modes:
     * - a non-2xx response throws the same [HttpClientErrorException] / [HttpServerErrorException]
     *   [chat] throws, after the same 3-attempt backoff on 5xx and 429,
     * - an `error` object arriving inside an already-200 stream, or an unparseable chunk, throws
     *   [OpenRouterStreamException],
     * - the socket dying mid-stream — including a provider that goes quiet for longer than
     *   [AiClientProperties.streamIdleTimeout] — throws [ResourceAccessException], as the blocking path
     *   does for the same class of failure.
     *
     * In both cases [AiCallListener.recordFailure] fires exactly once, so accounting sees a call
     * resolve exactly as it does on the blocking path. Deltas already emitted stay valid; only the
     * connection is retried, never a stream that has begun delivering events.
     *
     * Collection blocks on socket reads, so the flow runs on [Dispatchers.IO]. Cancellation is
     * observed between events and closes the connection; note that a read already parked in the
     * socket is not interrupted, so cancellation takes effect once the next event or keepalive
     * arrives (OpenRouter sends `: OPENROUTER PROCESSING` comments while waiting on a provider).
     * A cancelled stream notifies the listener **not at all** — neither success nor failure — since
     * this flow unwinds asynchronously, after the collector has already moved on. A caller that
     * needs to account for abandoned generations should do so at its own cancellation point.
     */
    fun chatStream(request: ChatRequest, context: AiCallContext): Flow<ChatStreamEvent> = flow {
        callGate.beforeCall(context, request)
        val streamingRequest = request.copy(stream = true, usage = UsageConfig(include = true))
        val accumulator = ChatStreamAccumulator()
        try {
            log.debug("Opening chat stream to AI API: model=${request.model}, messages=${request.messages.size}")
            connect(streamingRequest).use { response ->
                for (payload in sseDataLines(response.body.bufferedReader())) {
                    currentCoroutineContext().ensureActive()
                    accumulator.accept(parseChunk(payload)).forEach { emit(it) }
                }
                accumulator.finish().forEach { emit(it) }
            }
            emit(ChatStreamEvent.Completed(accumulator.toResponse()))
        } catch (e: CancellationException) {
            // The collector walked away. Propagate untouched — swallowing or repurposing a
            // CancellationException breaks structured concurrency, and the listener is deliberately
            // not notified: this coroutine unwinds after the collector has already moved on, so any
            // notification here would race with whatever the caller does next.
            throw e
        } catch (e: IOException) {
            // The socket died mid-stream — a stalled provider hitting the read timeout, a reset, a
            // truncated body. Kotlin has no checked exceptions, so this would otherwise sail past
            // the RuntimeException catch below and skip accounting entirely. Rethrown as
            // ResourceAccessException because that is what the blocking path surfaces for the same
            // class of failure (RestClient wraps IO errors), keeping the two paths uniform.
            callListener.recordFailure(context, request)
            throw ResourceAccessException("I/O error on AI API stream: ${e.message}", e)
        } catch (e: RuntimeException) {
            callListener.recordFailure(context, request)
            throw e
        }
        callListener.recordSuccess(context, request, accumulator.toResponse())
        // Rendezvous rather than flowOn's default 64-element buffer: with a buffer the reader runs
        // ahead of the collector, so a collector that stops early can still have driven the call to
        // completion. Handing each event over directly keeps the collector's view and the call's
        // state in step, and makes cancellation take effect at the next event.
    }.flowOn(Dispatchers.IO).buffer(Channel.RENDEZVOUS)

    private fun parseChunk(payload: String): ChatChunk =
        try {
            chunkMapper.readValue(payload, ChatChunk::class.java)
        } catch (e: JacksonException) {
            throw OpenRouterStreamException(message = "Unparseable chunk in AI API stream: ${e.message}")
        }

    /**
     * Opens the streaming request, retrying the *connection* under the same policy as [withRetry].
     * Only connection establishment is retried: once events have been delivered to the collector
     * they cannot be un-delivered, so a stream that breaks mid-flight fails the call.
     *
     * Uses `exchange(..., close = false)` to get the live response with its body unconsumed — the
     * caller owns closing it. Because `exchange` does not apply the default status handler, non-2xx
     * responses are detected here and rethrown as the exception types [chat] produces.
     */
    private suspend fun connect(
        request: ChatRequest,
        maxAttempts: Int = 3,
    ): RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse {
        var delayMs = 2000L
        var lastException: RuntimeException? = null
        repeat(maxAttempts) { attempt ->
            val response = streamClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange({ _, res -> res }, false)

            val status = response.statusCode
            if (!status.isError) return response

            // Drain and close before deciding: the body is the error payload, and a retry needs the
            // connection released either way.
            val statusText = runCatching { response.statusText }.getOrDefault("")
            val headers = response.headers
            val body = response.use { runCatching { it.body.readAllBytes() }.getOrDefault(ByteArray(0)) }
            val e = statusException(status, statusText, headers, body)

            val retryable = status.is5xxServerError || status.value() == 429
            if (!retryable) throw e
            log.warn("AI API stream connect failed (attempt ${attempt + 1}/$maxAttempts): $status")
            lastException = e
            if (attempt < maxAttempts - 1) delay(delayMs)
            delayMs *= 2
        }
        throw lastException!!
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

    private fun <T> withRetry(maxAttempts: Int = 3, block: () -> T): T {
        var delayMs = 2000L
        var lastException: RuntimeException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: HttpServerErrorException) {
                log.warn("AI API server error (attempt ${attempt + 1}/$maxAttempts): ${e.statusCode}")
                lastException = e
                if (attempt < maxAttempts - 1) Thread.sleep(delayMs)
                delayMs *= 2
            } catch (e: HttpClientErrorException) {
                if (e.statusCode.value() == 429) {
                    log.warn("AI API rate limited (attempt ${attempt + 1}/$maxAttempts)")
                    lastException = e
                    if (attempt < maxAttempts - 1) Thread.sleep(delayMs)
                    delayMs *= 2
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }
}
