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
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import tools.jackson.core.JacksonException
import java.io.IOException
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The chat-completions client. Transport concerns — connection setup, the retry policy, and the
 * [AiCallGate] / [AiCallListener] seams — live in [OpenRouterTransport], which every modality client
 * shares; this class carries only what is specific to `/chat/completions`.
 */
@Component
class AiClient(private val transport: OpenRouterTransport) {
    private val log = LoggerFactory.getLogger(AiClient::class.java)

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
    fun chat(request: ChatRequest, context: AiCallContext): ChatResponse =
        transport.call(context, request, COMPLETIONS_PATH, ChatResponse::class.java)

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
     * - a non-2xx response throws the same [org.springframework.web.client.HttpClientErrorException] /
     *   [org.springframework.web.client.HttpServerErrorException] [chat] throws, after the same
     *   3-attempt backoff on 5xx and 429,
     * - an `error` object arriving inside an already-200 stream, or an unparseable chunk, throws
     *   [OpenRouterStreamException] — after the same backoff when the error is a rate limit or a
     *   server fault and nothing has been emitted yet, since until then a retry is invisible,
     * - the socket dying mid-stream — including a provider that goes quiet for longer than
     *   [AiClientProperties.streamIdleTimeout] — throws [ResourceAccessException], as the blocking path
     *   does for the same class of failure.
     *
     * In both cases [AiCallListener.recordFailure] fires exactly once, so accounting sees a call
     * resolve exactly as it does on the blocking path — once, after the last attempt, not per retry.
     * Deltas already emitted stay valid; a stream that has begun delivering events is never retried.
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
        transport.gateCheck(context, request)
        val streamingRequest = request.copy(stream = true, usage = UsageConfig(include = true))
        var accumulator = ChatStreamAccumulator()
        // Set before the first emit, never after: it is what makes a retry safe (see below), and an
        // exception thrown *by* that emit — the collector's own — must not look retryable either.
        var emitted = false
        // The terminal failure travels in-band, as a final element, rather than being thrown from
        // here. Throwing out of the flow body *cancels* the channel `flowOn` hands events over,
        // and a cancelled channel discards what it is holding — including an element already
        // handed to the collector but not yet processed by it. That silently drops the last delta
        // before a failure, which is exactly what this method promises not to do. Closing the
        // channel normally instead delivers every element in order, and `getOrThrow` below turns
        // the marker back into the same exception, thrown in the collector after the last event.
        val failure: Throwable? = try {
            // A stream error that arrives before anything reached the collector is a failed attempt
            // like any other, and is retried under the same policy as a failed connection. OpenRouter
            // reports a rate limit that lands after the 200 headers this way, as the stream's first
            // chunk. Once an event has been emitted the call is committed: a retry would deliver a
            // second, different answer on top of the first.
            accumulator = retrying(
                onRetry = { attempt, e -> log.warn("AI API stream failed before any output (attempt $attempt/3): ${e.message}") },
                retryable = { e -> !emitted && e is OpenRouterStreamException && isTransient(e) },
                sleep = { delay(it) },
            ) {
                val attempt = ChatStreamAccumulator()
                log.debug("Opening chat stream to AI API: model=${request.model}, messages=${request.messages.size}")
                transport.openStream(streamingRequest, COMPLETIONS_PATH).use { response ->
                    for (payload in sseDataLines(response.body.bufferedReader())) {
                        currentCoroutineContext().ensureActive()
                        attempt.accept(parseChunk(payload)).forEach { emitted = true; emit(Result.success(it)) }
                    }
                    attempt.finish().forEach { emitted = true; emit(Result.success(it)) }
                }
                attempt
            }
            emit(Result.success(ChatStreamEvent.Completed(accumulator.toResponse())))
            null
        } catch (e: CancellationException) {
            // The collector walked away. Propagate untouched — swallowing or repurposing a
            // CancellationException breaks structured concurrency, and the listener is deliberately
            // not notified: this coroutine unwinds after the collector has already moved on, so any
            // notification here would race with whatever the caller does next.
            throw e
        } catch (e: IOException) {
            // The socket died mid-stream — a stalled provider hitting the read timeout, a reset, a
            // truncated body. Kotlin has no checked exceptions, so this would otherwise sail past
            // the RuntimeException catch below and skip accounting entirely. Surfaced as
            // ResourceAccessException because that is what the blocking path surfaces for the same
            // class of failure (RestClient wraps IO errors), keeping the two paths uniform.
            transport.recordFailure(context, request)
            ResourceAccessException("I/O error on AI API stream: ${e.message}", e)
        } catch (e: RuntimeException) {
            transport.recordFailure(context, request)
            e
        }
        // Emitted outside the try on purpose: an emit inside a catch that also covers the earlier
        // emits would re-enter the flow after a downstream failure, which is a flow-transparency
        // violation. The listener has already been notified above either way.
        if (failure != null) emit(Result.failure(failure)) else transport.recordSuccess(context, request, accumulator.toResponse())
        // Rendezvous rather than flowOn's default 64-element buffer: with a buffer the reader runs
        // ahead of the collector, so a collector that stops early can still have driven the call to
        // completion. Handing each event over directly keeps the collector's view and the call's
        // state in step, and makes cancellation take effect at the next event.
    }.flowOn(Dispatchers.IO).buffer(Channel.RENDEZVOUS).map { it.getOrThrow() }

    private fun parseChunk(payload: String): ChatChunk =
        try {
            chunkMapper.readValue(payload, ChatChunk::class.java)
        } catch (e: JacksonException) {
            throw OpenRouterStreamException(message = "Unparseable chunk in AI API stream: ${e.message}")
        }
}
