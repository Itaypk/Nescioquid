package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the blocking path, so the streaming path has something to be consistent with. Possible only
 * since the RestClient.Builder became injectable.
 */
class AiClientTest {

    private val successBody =
        """{"id":"gen-1","model":"openai/gpt-oss-20b","provider":"Groq",
           "choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":7,"completion_tokens":1}}"""

    @Test
    fun `sends a bearer-authenticated request and returns the parsed response`() {
        val fixture = testClient()
        fixture.server.expect(requestTo(COMPLETIONS_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer k"))
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = fixture.client.chat(testRequest(), testContext)

        fixture.server.verify()
        assertEquals("hi", response.choices.first().message.contentText)
        assertEquals(7, response.usage?.promptTokens)
        assertEquals(1, fixture.gate.calls)
        assertEquals(response, fixture.listener.successes.single())
    }

    @Test
    fun `retries a 429 and succeeds`() {
        val fixture = testClient()
        fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = fixture.client.chat(testRequest(), testContext)

        fixture.server.verify()
        assertEquals("hi", response.choices.first().message.contentText)
        assertEquals(0, fixture.listener.failures)
    }

    @Test
    fun `gives up after three server errors and records a failure`() {
        val fixture = testClient()
        repeat(3) { fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withServerError()) }

        assertFailsWith<org.springframework.web.client.HttpServerErrorException> {
            fixture.client.chat(testRequest(), testContext)
        }

        fixture.server.verify()
        assertEquals(1, fixture.listener.failures)
        assertTrue(fixture.listener.successes.isEmpty())
    }

    @Test
    fun `does not retry a non-429 client error`() {
        val fixture = testClient()
        fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertFailsWith<HttpClientErrorException> { fixture.client.chat(testRequest(), testContext) }

        fixture.server.verify()
        assertEquals(1, fixture.listener.failures)
    }

    @Test
    fun `parses an error-shaped 200 without losing why the generation failed`() {
        val fixture = testClient()
        // What a provider-side failure looks like when OpenRouter still answers 200: an empty
        // message, zeroed usage, and the reason only in `native_finish_reason` / `error`.
        val body =
            """{"id":"gen-1","model":"google/gemini-3.5-flash-lite","provider":"Google",
               "choices":[{"message":{"role":"assistant","content":""},"finish_reason":"error",
               "native_finish_reason":"MALFORMED_FUNCTION_CALL",
               "error":{"code":502,"message":"upstream produced an unparseable function call",
               "metadata":{"provider_name":"Google"}}}],
               "usage":{"prompt_tokens":0,"completion_tokens":0}}"""
        fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        val choice = fixture.client.chat(testRequest(), testContext).choices.first()

        fixture.server.verify()
        assertEquals("error", choice.finishReason)
        assertEquals("MALFORMED_FUNCTION_CALL", choice.nativeFinishReason)
        assertEquals(502, choice.error?.code)
        assertEquals("Google", choice.error?.metadata?.get("provider_name"))
    }

    @Test
    fun `parses a 200 that carries a top-level error instead of any choices`() {
        val fixture = testClient()
        val body = """{"id":"gen-1","error":{"code":429,"message":"rate limited upstream"},"usage":null}"""
        fixture.server.expect(requestTo(COMPLETIONS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

        val response = fixture.client.chat(testRequest(), testContext)

        fixture.server.verify()
        assertTrue(response.choices.isEmpty())
        assertEquals(429, response.error?.code)
        assertEquals("rate limited upstream", response.error?.message)
    }

    @Test
    fun `a gate that refuses the call prevents any request`() {
        val fixture = testClient(gate = RecordingGate { _, _ -> throw IllegalStateException("opted out") })
        // No request expected.

        assertFailsWith<IllegalStateException> { fixture.client.chat(testRequest(), testContext) }

        fixture.server.verify()
        assertEquals(0, fixture.listener.failures)
    }
}
