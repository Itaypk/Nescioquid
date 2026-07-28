package dev.itayp.nescioquid.openrouter

/**
 * Folds the [ChatChunk]s of a streamed completion into the same [ChatResponse] the blocking
 * [AiClient.chat] would have returned, and decides which [ChatStreamEvent]s each chunk produces.
 *
 * Kept as a pure, I/O-free class so the interesting parts of streaming — delta concatenation and
 * tool-call reassembly — are unit-testable without an HTTP server. Not thread-safe: a single stream
 * is consumed by a single collector.
 */
internal class ChatStreamAccumulator {

    private val content = StringBuilder()
    private val reasoning = StringBuilder()

    // Keyed by the chunk's tool-call `index`, which is how the wire format identifies a call across
    // fragments. LinkedHashMap so calls come back in the order the model started them.
    private val toolCalls = LinkedHashMap<Int, PartialToolCall>()
    private val flushed = mutableSetOf<Int>()

    private var id: String? = null
    private var model: String? = null
    private var provider: String? = null
    private var finishReason: String? = null
    private var usage: Usage? = null

    /** Everything the model has emitted as assistant-visible text so far. */
    val contentText: String get() = content.toString()

    /** Everything the model has emitted as reasoning so far. Not part of [toResponse]. */
    val reasoningText: String get() = reasoning.toString()

    /**
     * Absorbs one chunk and returns the events it produces, in emission order. Throws
     * [OpenRouterStreamException] if the chunk carries a mid-stream error.
     */
    fun accept(chunk: ChatChunk): List<ChatStreamEvent> {
        chunk.error?.let { throw OpenRouterStreamException(it.code, it.message) }

        id = id ?: chunk.id
        model = chunk.model ?: model
        provider = chunk.provider ?: provider
        usage = chunk.usage ?: usage

        val events = mutableListOf<ChatStreamEvent>()
        for (choice in chunk.choices) {
            // Reasoning first: a model that thinks before answering emits it first, and a chunk
            // carrying both should surface in that order.
            choice.delta.reasoning?.takeIf { it.isNotEmpty() }?.let {
                reasoning.append(it)
                events += ChatStreamEvent.ReasoningDelta(it)
            }
            choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                content.append(it)
                events += ChatStreamEvent.ContentDelta(it)
            }
            choice.delta.toolCalls?.forEach { fragment ->
                toolCalls.getOrPut(fragment.index) { PartialToolCall() }.append(fragment)
            }
            choice.finishReason?.let {
                finishReason = it
                // The wire format never marks an individual tool call complete; `finish_reason` is
                // the only signal that no more fragments are coming, so flush them all here.
                events += flushToolCalls()
            }
        }
        return events
    }

    /**
     * Events owed at end of stream. Normally empty — a well-behaved stream flushes on
     * `finish_reason` — but a provider that ends without one should still yield its tool calls
     * rather than silently dropping them.
     */
    fun finish(): List<ChatStreamEvent> = flushToolCalls()

    private fun flushToolCalls(): List<ChatStreamEvent> =
        toolCalls.entries
            .filter { it.key !in flushed }
            .onEach { flushed += it.key }
            .map { ChatStreamEvent.ToolCallReady(it.key, it.value.build()) }

    /**
     * The aggregated response. Shape-identical to the blocking path: one [Choice] carrying an
     * assistant [ChatMessage]. Reasoning is deliberately absent — it is not part of the assistant
     * message, and is delivered only as [ChatStreamEvent.ReasoningDelta].
     */
    fun toResponse(): ChatResponse {
        val calls = toolCalls.values.map { it.build() }.takeIf { it.isNotEmpty() }
        return ChatResponse(
            id = id ?: "",
            choices = listOf(
                Choice(
                    message = ChatMessage(
                        role = "assistant",
                        // Null rather than "" for a pure tool-call turn, matching what a
                        // non-streamed response carries.
                        content = content.toString().takeIf { it.isNotEmpty() || calls == null },
                        toolCalls = calls,
                    ),
                    // `finish_reason` is a non-null passthrough of provider text, so a stream that
                    // never sent one is reported as "unknown" rather than guessed at.
                    finishReason = finishReason ?: "unknown",
                ),
            ),
            usage = usage,
            model = model,
            provider = provider,
        )
    }
}

/** One tool call under construction. Every field can arrive in fragments, so all of them append. */
private class PartialToolCall {
    private val id = StringBuilder()
    private val name = StringBuilder()
    private val arguments = StringBuilder()
    private var type: String? = null

    fun append(fragment: ToolCallChunk) {
        fragment.id?.let { id.append(it) }
        fragment.type?.let { type = it }
        fragment.function?.name?.let { name.append(it) }
        fragment.function?.arguments?.let { arguments.append(it) }
    }

    fun build(): ToolCall = ToolCall(
        id = id.toString(),
        type = type ?: "function",
        function = FunctionCallDetails(name = name.toString(), arguments = arguments.toString()),
    )
}
