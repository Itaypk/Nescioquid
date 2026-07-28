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
    fun `a gate that refuses the call prevents any request`() {
        val fixture = testClient(gate = RecordingGate { _, _ -> throw IllegalStateException("opted out") })
        // No request expected.

        assertFailsWith<IllegalStateException> { fixture.client.chat(testRequest(), testContext) }

        fixture.server.verify()
        assertEquals(0, fixture.listener.failures)
    }
}
