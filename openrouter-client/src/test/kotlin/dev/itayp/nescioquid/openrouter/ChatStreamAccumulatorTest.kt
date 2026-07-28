package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatStreamAccumulatorTest {

    private fun contentChunk(text: String, finishReason: String? = null) = ChatChunk(
        id = "gen-1",
        choices = listOf(ChunkChoice(delta = ChunkDelta(content = text), finishReason = finishReason)),
    )

    @Test
    fun `concatenates content deltas into the response message`() {
        val acc = ChatStreamAccumulator()
        val events = listOf("Hel", "lo ", "world").flatMap { acc.accept(contentChunk(it)) }

        assertEquals(
            listOf("Hel", "lo ", "world"),
            events.map { (it as ChatStreamEvent.ContentDelta).text },
        )
        assertEquals("Hello world", acc.contentText)
        assertEquals("Hello world", acc.toResponse().choices.first().message.contentText)
    }

    @Test
    fun `keeps reasoning separate from content`() {
        val acc = ChatStreamAccumulator()
        val events =
            acc.accept(ChatChunk(choices = listOf(ChunkChoice(delta = ChunkDelta(reasoning = "thinking"))))) +
                acc.accept(contentChunk("answer"))

        assertEquals(ChatStreamEvent.ReasoningDelta("thinking"), events[0])
        assertEquals(ChatStreamEvent.ContentDelta("answer"), events[1])
        assertEquals("thinking", acc.reasoningText)
        // Reasoning is not part of the assistant message.
        assertEquals("answer", acc.toResponse().choices.first().message.contentText)
    }

    @Test
    fun `emits reasoning before content when one chunk carries both`() {
        val acc = ChatStreamAccumulator()
        val events = acc.accept(
            ChatChunk(choices = listOf(ChunkChoice(delta = ChunkDelta(content = "c", reasoning = "r")))),
        )
        assertEquals(listOf(ChatStreamEvent.ReasoningDelta("r"), ChatStreamEvent.ContentDelta("c")), events)
    }

    @Test
    fun `merges tool call fragments by index and emits them on finish_reason`() {
        val acc = ChatStreamAccumulator()
        fun toolChunk(vararg calls: ToolCallChunk, finishReason: String? = null) =
            ChatChunk(choices = listOf(ChunkChoice(delta = ChunkDelta(toolCalls = calls.toList()), finishReason = finishReason)))

        assertTrue(
            acc.accept(
                toolChunk(ToolCallChunk(index = 0, id = "call_1", type = "function", function = FunctionCallChunk(name = "get_weather", arguments = ""))),
            ).isEmpty(),
        )
        assertTrue(acc.accept(toolChunk(ToolCallChunk(index = 0, function = FunctionCallChunk(arguments = """{"city":"""")))).isEmpty())
        assertTrue(acc.accept(toolChunk(ToolCallChunk(index = 0, function = FunctionCallChunk(arguments = """Berlin"}""")))).isEmpty())

        val events = acc.accept(toolChunk(finishReason = "tool_calls"))
        val ready = events.single() as ChatStreamEvent.ToolCallReady
        assertEquals(0, ready.index)
        assertEquals("call_1", ready.toolCall.id)
        assertEquals("function", ready.toolCall.type)
        assertEquals("get_weather", ready.toolCall.function.name)
        assertEquals("""{"city":"Berlin"}""", ready.toolCall.function.arguments)

        val message = acc.toResponse().choices.first().message
        assertEquals(listOf(ready.toolCall), message.toolCalls)
        // A pure tool-call turn carries null content, as it does on the blocking path.
        assertNull(message.contentText)
        assertEquals("tool_calls", acc.toResponse().choices.first().finishReason)
    }

    @Test
    fun `assembles parallel tool calls independently and in start order`() {
        val acc = ChatStreamAccumulator()
        acc.accept(
            ChatChunk(
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(
                            toolCalls = listOf(
                                ToolCallChunk(index = 0, id = "a", function = FunctionCallChunk(name = "first", arguments = "{")),
                                ToolCallChunk(index = 1, id = "b", function = FunctionCallChunk(name = "second", arguments = "{")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        acc.accept(
            ChatChunk(
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(
                            toolCalls = listOf(
                                ToolCallChunk(index = 1, function = FunctionCallChunk(arguments = """"y":2}""")),
                                ToolCallChunk(index = 0, function = FunctionCallChunk(arguments = """"x":1}""")),
                            ),
                        ),
                        finishReason = "tool_calls",
                    ),
                ),
            ),
        ).let { events ->
            val ready = events.filterIsInstance<ChatStreamEvent.ToolCallReady>()
            assertEquals(listOf(0, 1), ready.map { it.index })
            assertEquals("""{"x":1}""", ready[0].toolCall.function.arguments)
            assertEquals("""{"y":2}""", ready[1].toolCall.function.arguments)
        }
    }

    @Test
    fun `does not re-emit tool calls already flushed`() {
        val acc = ChatStreamAccumulator()
        acc.accept(
            ChatChunk(
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(toolCalls = listOf(ToolCallChunk(index = 0, id = "a", function = FunctionCallChunk(name = "f", arguments = "{}")))),
                        finishReason = "tool_calls",
                    ),
                ),
            ),
        )
        assertTrue(acc.finish().isEmpty())
    }

    @Test
    fun `finish flushes tool calls a provider never marked complete`() {
        val acc = ChatStreamAccumulator()
        acc.accept(
            ChatChunk(
                choices = listOf(
                    ChunkChoice(delta = ChunkDelta(toolCalls = listOf(ToolCallChunk(index = 0, id = "a", function = FunctionCallChunk(name = "f", arguments = "{}"))))),
                ),
            ),
        )
        val ready = acc.finish().single() as ChatStreamEvent.ToolCallReady
        assertEquals("f", ready.toolCall.function.name)
    }

    @Test
    fun `captures id model provider and the terminal usage chunk`() {
        val acc = ChatStreamAccumulator()
        acc.accept(ChatChunk(id = "gen-1", model = "openai/gpt-oss-20b", provider = "Groq", choices = listOf(ChunkChoice(delta = ChunkDelta(content = "hi")))))
        acc.accept(
            ChatChunk(
                choices = listOf(ChunkChoice(delta = ChunkDelta(), finishReason = "stop")),
                usage = Usage(promptTokens = 11, completionTokens = 3, totalTokens = 14),
            ),
        )

        val response = acc.toResponse()
        assertEquals("gen-1", response.id)
        assertEquals("openai/gpt-oss-20b", response.model)
        assertEquals("Groq", response.provider)
        assertEquals(11, response.usage?.promptTokens)
        assertEquals(3, response.usage?.completionTokens)
        assertEquals("stop", response.choices.first().finishReason)
    }

    @Test
    fun `a mid-stream error chunk throws`() {
        val acc = ChatStreamAccumulator()
        val e = assertFailsWith<OpenRouterStreamException> {
            acc.accept(ChatChunk(error = StreamErrorPayload(code = 502, message = "provider returned error")))
        }
        assertEquals(502, e.code)
        assertEquals("provider returned error", e.message)
    }

    @Test
    fun `a stream with no finish_reason reports it as unknown rather than guessing`() {
        val acc = ChatStreamAccumulator()
        acc.accept(contentChunk("hi"))
        assertEquals("unknown", acc.toResponse().choices.first().finishReason)
    }

    @Test
    fun `empty deltas produce no events`() {
        val acc = ChatStreamAccumulator()
        assertTrue(acc.accept(ChatChunk(choices = listOf(ChunkChoice(delta = ChunkDelta(role = "assistant", content = ""))))).isEmpty())
        assertEquals("", acc.contentText)
    }
}
