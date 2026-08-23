package dev.itayp.nescioquid.openrouter

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiClientStreamTest {

    /** Builds an SSE body from `data:` payloads, terminated the way OpenRouter terminates one. */
    private fun sse(vararg payloads: String) =
        payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun MockRestServiceServer.expectStream(body: String) {
        expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.TEXT_EVENT_STREAM))
    }

    @Test
    fun `emits content deltas then a completed response`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(
            sse(
                """{"id":"gen-1","model":"openai/gpt-oss-20b","provider":"Groq","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
                """{"id":"gen-1","choices":[{"index":0,"delta":{"content":"lo"}}]}""",
                """{"id":"gen-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2}}""",
            ),
        )

        val events = fixture.client.chatStream(testRequest(), testContext).toList()

        fixture.server.verify()
        assertEquals(
            listOf(ChatStreamEvent.ContentDelta("Hel"), ChatStreamEvent.ContentDelta("lo")),
            events.dropLast(1),
        )
        val completed = events.last() as ChatStreamEvent.Completed
        assertEquals("Hello", completed.response.choices.first().message.contentText)
        assertEquals("stop", completed.response.choices.first().finishReason)
        assertEquals("gen-1", completed.response.id)
        assertEquals("Groq", completed.response.provider)
        assertEquals(9, completed.response.usage?.promptTokens)
    }

    @Test
    fun `sets stream and usage-include on the wire without the caller asking`() = runTest {
        val fixture = testClient()
        var body: String? = null
        fixture.server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(RequestMatcher { body = (it as MockClientHttpRequest).bodyAsString })
            .andRespond(withSuccess(sse("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""), MediaType.TEXT_EVENT_STREAM))

        fixture.client.chatStream(testRequest(), testContext).toList()

        val sent = assertNotNull(body)
        assertContains(sent, """"stream":true""")
        assertContains(sent, """"usage":{"include":true}""")
    }

    @Test
    fun `the blocking path still sends neither stream nor usage`() {
        val fixture = testClient()
        var body: String? = null
        fixture.server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(RequestMatcher { body = (it as MockClientHttpRequest).bodyAsString })
            .andRespond(withSuccess("""{"id":"x","choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":null}""", MediaType.APPLICATION_JSON))

        fixture.client.chat(testRequest(), testContext)

        val sent = assertNotNull(body)
        assertFalse(sent.contains("\"stream\""), "blocking requests must not carry a stream flag: $sent")
        assertFalse(sent.contains("\"usage\""), "blocking requests must not carry a usage config: $sent")
    }

    @Test
    fun `the flow is cold - the gate runs on collection, not on the call`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(sse("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""))

        val flow = fixture.client.chatStream(testRequest(), testContext)
        assertEquals(0, fixture.gate.calls)

        flow.toList()
        assertEquals(1, fixture.gate.calls)
    }

    @Test
    fun `a gate that refuses the call prevents any request`() = runTest {
        val fixture = testClient(gate = RecordingGate { _, _ -> throw IllegalStateException("over budget") })
        // No request expected.

        assertFailsWith<IllegalStateException> {
            fixture.client.chatStream(testRequest(), testContext).toList()
        }
        fixture.server.verify()
    }

    @Test
    fun `records success with the aggregated response`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(
            sse(
                """{"id":"gen-2","choices":[{"delta":{"content":"a"}}]}""",
                """{"choices":[{"delta":{"content":"b"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}""",
            ),
        )

        fixture.client.chatStream(testRequest(), testContext).toList()

        assertEquals(0, fixture.listener.failures)
        val recorded = fixture.listener.chatSuccesses.single()
        assertEquals("ab", recorded.choices.first().message.contentText)
        assertEquals(4, recorded.usage?.promptTokens)
    }

    @Test
    fun `assembles fragmented tool calls into a ready event`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(
            sse(
                """{"id":"gen-3","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Berlin\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )

        val events = fixture.client.chatStream(testRequest(), testContext).toList()

        val ready = events.filterIsInstance<ChatStreamEvent.ToolCallReady>().single()
        assertEquals("call_1", ready.toolCall.id)
        assertEquals("get_weather", ready.toolCall.function.name)
        assertEquals("""{"city":"Berlin"}""", ready.toolCall.function.arguments)
        // …and it lands on the aggregated response too.
        val completed = events.last() as ChatStreamEvent.Completed
        assertEquals(listOf(ready.toolCall), completed.response.choices.first().message.toolCalls)
    }

    @Test
    fun `surfaces reasoning deltas separately from content`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(
            sse(
                """{"choices":[{"delta":{"reasoning":"let me think"}}]}""",
                """{"choices":[{"delta":{"content":"42"},"finish_reason":"stop"}]}""",
            ),
        )

        val events = fixture.client.chatStream(testRequest(), testContext).toList()

        assertEquals(ChatStreamEvent.ReasoningDelta("let me think"), events[0])
        assertEquals(ChatStreamEvent.ContentDelta("42"), events[1])
        assertEquals("42", (events[2] as ChatStreamEvent.Completed).response.choices.first().message.contentText)
    }

    @Test
    fun `a mid-stream error chunk fails the flow and records a failure`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(
            sse(
                """{"choices":[{"delta":{"content":"partial"}}]}""",
                """{"error":{"code":502,"message":"provider fell over"}}""",
            ),
        )

        val collected: MutableList<ChatStreamEvent> = mutableListOf()
        val e = assertFailsWith<OpenRouterStreamException> {
            fixture.client.chatStream(testRequest(), testContext).collect { collected += it }
        }

        assertEquals(502, e.code)
        assertEquals("provider fell over", e.message)
        // The deltas delivered before the error are still valid and were not rolled back.
        assertEquals(listOf<ChatStreamEvent>(ChatStreamEvent.ContentDelta("partial")), collected.toList())
        assertEquals(1, fixture.listener.failures)
        assertTrue(fixture.listener.successes.isEmpty())
    }

    @Test
    fun `an unparseable chunk fails the flow`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(sse("not json at all"))

        assertFailsWith<OpenRouterStreamException> {
            fixture.client.chatStream(testRequest(), testContext).toList()
        }
        assertEquals(1, fixture.listener.failures)
    }

    @Test
    fun `retries the connection on a 5xx and then streams`() = runTest {
        val fixture = testClient()
        fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withServerError())
        fixture.server.expectStream(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))

        val events = fixture.client.chatStream(testRequest(), testContext).toList()

        fixture.server.verify()
        assertEquals(ChatStreamEvent.ContentDelta("ok"), events.first())
        assertEquals(0, fixture.listener.failures)
    }

    @Test
    fun `does not retry a non-429 client error and rethrows the usual exception type`() = runTest {
        val fixture = testClient()
        fixture.server.expect(requestTo(COMPLETIONS_URL))
            .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST).body("""{"error":{"message":"bad model"}}"""))

        val e = assertFailsWith<HttpClientErrorException> {
            fixture.client.chatStream(testRequest(), testContext).toList()
        }

        fixture.server.verify()
        assertEquals(400, e.statusCode.value())
        assertContains(e.responseBodyAsString, "bad model")
        assertEquals(1, fixture.listener.failures)
    }

    @Test
    fun `a collector that stops early gets its events and no listener notification`() = runTest {
        val fixture = testClient()
        fixture.server.expectStream(
            sse(
                """{"choices":[{"delta":{"content":"a"}}]}""",
                """{"choices":[{"delta":{"content":"b"}}]}""",
                """{"choices":[{"delta":{"content":"c"},"finish_reason":"stop"}]}""",
            ),
        )

        val events = fixture.client.chatStream(testRequest(), testContext).take(1).toList()

        assertEquals(listOf(ChatStreamEvent.ContentDelta("a")), events)
        // Cancellation is the collector's own doing, and the flow unwinds after it has moved on —
        // notifying from there would race with the caller, so neither seam fires.
        assertTrue(fixture.listener.successes.isEmpty())
    }
}
