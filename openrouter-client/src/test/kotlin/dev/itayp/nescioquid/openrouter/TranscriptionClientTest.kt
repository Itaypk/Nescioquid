package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors [ImageClientTest]: the point here is that a transcription call is accounted, retried and
 * refused exactly as a chat or image call is — and that the transcription-specific request/response
 * shapes go on and come off the wire correctly.
 */
class TranscriptionClientTest {

    private val objectMapper = jacksonObjectMapper()

    /**
     * Captures the serialized request body for assertions. Hand-rolled rather than using
     * `content().json(...)` / `jsonPath(...)`, which need JSONassert and json-path — two test
     * dependencies this module deliberately doesn't carry.
     */
    private fun capturingBody(into: (JsonNode) -> Unit) = RequestMatcher { request ->
        into(objectMapper.readTree((request as MockClientHttpRequest).bodyAsString))
    }

    private val audioBytes = byteArrayOf(1, 2, 3, 4)
    private val b64 = Base64.getEncoder().encodeToString(audioBytes)

    private val successBody =
        """{"text":"Hello, this is a test.",
           "usage":{"seconds":9.2,"total_tokens":113,"input_tokens":83,"output_tokens":30,"cost":0.000508}}"""

    @Test
    fun `sends a bearer-authenticated request and returns the parsed response`() {
        val fixture = testTranscriptionClient()
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer k"))
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = fixture.client.transcribe(testTranscriptionRequest(), testContext)

        fixture.server.verify()
        assertEquals("Hello, this is a test.", response.text)
        assertEquals(9.2, response.usage?.seconds)
        assertEquals(113, response.usage?.totalTokens)
        assertEquals(83, response.usage?.inputTokens)
        assertEquals(30, response.usage?.outputTokens)
        assertEquals(0.000508, response.usage?.cost)
        assertNull(response.model)
        assertNull(response.provider)
        assertEquals(1, fixture.gate.calls)
        assertEquals(response, fixture.listener.successes.single())
    }

    @Test
    fun `omits every unset knob from the wire`() {
        val fixture = testTranscriptionClient()
        var body: JsonNode? = null
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL))
            .andExpect(capturingBody { body = it })
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        fixture.client.transcribe(testTranscriptionRequest(), testContext)

        fixture.server.verify()
        // Only what was actually set, plus the provider object every request carries.
        assertEquals(
            setOf("model", "input_audio", "provider"),
            body!!.propertyNames().toSet(),
        )
        assertEquals(b64, body.get("input_audio").get("data").asString())
        assertEquals("wav", body.get("input_audio").get("format").asString())
    }

    @Test
    fun `serializes language and temperature under their wire names`() {
        val fixture = testTranscriptionClient()
        var body: JsonNode? = null
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL))
            .andExpect(capturingBody { body = it })
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        fixture.client.transcribe(
            testTranscriptionRequest().copy(language = "en", temperature = 0.2),
            testContext,
        )

        fixture.server.verify()
        assertEquals("en", body!!.get("language").asString())
        assertEquals(0.2, body.get("temperature").asDouble())
    }

    @Test
    fun `retries a 429 and succeeds`() {
        val fixture = testTranscriptionClient()
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL)).andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = fixture.client.transcribe(testTranscriptionRequest(), testContext)

        fixture.server.verify()
        assertEquals("Hello, this is a test.", response.text)
        assertEquals(0, fixture.listener.failures)
    }

    @Test
    fun `gives up after three server errors and records a failure`() {
        val fixture = testTranscriptionClient()
        repeat(3) { fixture.server.expect(requestTo(TRANSCRIPTIONS_URL)).andRespond(withServerError()) }

        assertFailsWith<HttpServerErrorException> { fixture.client.transcribe(testTranscriptionRequest(), testContext) }

        fixture.server.verify()
        assertEquals(1, fixture.listener.failures)
        assertTrue(fixture.listener.successes.isEmpty())
    }

    @Test
    fun `does not retry a non-429 client error`() {
        val fixture = testTranscriptionClient()
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertFailsWith<HttpClientErrorException> { fixture.client.transcribe(testTranscriptionRequest(), testContext) }

        fixture.server.verify()
        assertEquals(1, fixture.listener.failures)
    }

    @Test
    fun `a gate that refuses the call prevents any request`() {
        val fixture = testTranscriptionClient(gate = RecordingGate { _, _ -> throw IllegalStateException("no budget") })
        // No request expected.

        assertFailsWith<IllegalStateException> { fixture.client.transcribe(testTranscriptionRequest(), testContext) }

        fixture.server.verify()
        assertEquals(0, fixture.listener.failures)
    }

    @Test
    fun `the gate sees the transcription request through the shared AiRequest seam`() {
        var seen: AiRequest? = null
        val fixture = testTranscriptionClient(gate = RecordingGate { _, request -> seen = request })
        fixture.server.expect(requestTo(TRANSCRIPTIONS_URL))
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        fixture.client.transcribe(testTranscriptionRequest(), testContext)

        val request = assertIs<TranscriptionRequest>(seen)
        assertEquals("wav", request.inputAudio.format)
        assertEquals("openai/whisper-large-v3", request.model)
    }
}
