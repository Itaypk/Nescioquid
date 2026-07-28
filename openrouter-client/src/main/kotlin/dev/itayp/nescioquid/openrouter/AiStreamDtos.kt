package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The wire shapes of a streamed chat completion — one [ChatChunk] per SSE `data:` line.
 *
 * These are deliberately kept apart from the aggregated DTOs in `AiDtos.kt`: those stay the public
 * vocabulary a caller works with, while these exist only long enough for [ChatStreamAccumulator] to
 * fold them into a [ChatResponse]. Everything is nullable with a default, because providers populate
 * different subsets of a chunk and a stream must never die on a field one of them omitted.
 */
data class ChatChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    // Present only on the terminal chunk, and only when the request asked for it (`usage.include`).
    val usage: Usage? = null,
    val model: String? = null,
    val provider: String? = null,
    // OpenRouter can report a failure *inside* an already-200 stream (e.g. the upstream provider
    // fell over mid-generation). Non-null here means the stream is over and it failed.
    val error: StreamErrorPayload? = null,
)

data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    // Reasoning/thinking text, on models that expose it. Streamed alongside `content` but kept
    // separate all the way to the caller so a UI can render the two differently.
    val reasoning: String? = null,
    @JsonProperty("tool_calls") val toolCalls: List<ToolCallChunk>? = null,
)

/**
 * A fragment of a tool call. [index] is the identity across chunks — the first fragment for an index
 * carries [id] and the function [FunctionCallChunk.name], and every fragment after it appends more
 * of the JSON [FunctionCallChunk.arguments] text.
 */
data class ToolCallChunk(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallChunk? = null,
)

data class FunctionCallChunk(
    val name: String? = null,
    val arguments: String? = null,
)

data class StreamErrorPayload(
    val code: Int? = null,
    val message: String = "unknown streaming error",
)
