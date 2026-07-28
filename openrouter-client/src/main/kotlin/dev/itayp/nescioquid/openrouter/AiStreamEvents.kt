package dev.itayp.nescioquid.openrouter

/**
 * What a caller collecting [AiClient.chatStream] sees. The stream is ordered: zero or more delta
 * events as the model generates, then exactly one [Completed] — unless the call fails, in which case
 * the flow throws instead of completing.
 */
sealed interface ChatStreamEvent {

    /** A fragment of assistant-visible text. Concatenating every [text] yields the final message. */
    data class ContentDelta(val text: String) : ChatStreamEvent

    /**
     * A fragment of the model's reasoning, on models that expose it. Kept distinct from
     * [ContentDelta] so a UI can render thinking separately; it is *not* part of the assistant
     * message and does not appear in [Completed]'s content.
     */
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    /**
     * A tool call that has finished assembling. Emitted once per call, with complete
     * [ToolCall.function] arguments — a partially accumulated arguments string is not valid JSON and
     * so is of no use to the orchestrator loop that dispatches these.
     */
    data class ToolCallReady(val index: Int, val toolCall: ToolCall) : ChatStreamEvent

    /**
     * Terminal event, carrying the same aggregated [ChatResponse] the blocking [AiClient.chat] would
     * have returned for this request — including [Usage], which OpenRouter sends in the final chunk.
     */
    data class Completed(val response: ChatResponse) : ChatStreamEvent
}

/**
 * A streamed call that failed after the response headers said 200: either OpenRouter sent an `error`
 * object mid-stream, or the stream ended without ever completing. Transport-level failures (a
 * non-2xx status) surface as the usual [org.springframework.web.client.HttpClientErrorException] /
 * [org.springframework.web.client.HttpServerErrorException] instead, so error handling matches the
 * blocking path.
 */
class OpenRouterStreamException(
    val code: Int? = null,
    message: String,
) : RuntimeException(message)
