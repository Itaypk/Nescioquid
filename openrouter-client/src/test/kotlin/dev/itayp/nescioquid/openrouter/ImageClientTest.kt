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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Mirrors [AiClientTest]: the two clients share one transport, so the point here is that an image
 * call is accounted, retried and refused exactly as a chat call is — and that the image-specific
 * request/response shapes go on and come off the wire correctly.
 */
class ImageClientTest {

    private val objectMapper = jacksonObjectMapper()

    /**
     * Captures the serialized request body for assertions. Hand-rolled rather than using
     * `content().json(...)` / `jsonPath(...)`, which need JSONassert and json-path — two test
     * dependencies this module deliberately doesn't carry.
     */
    private fun capturingBody(into: (JsonNode) -> Unit) = RequestMatcher { request ->
        into(objectMapper.readTree((request as MockClientHttpRequest).bodyAsString))
    }

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val b64 = Base64.getEncoder().encodeToString(pngBytes)

    private val successBody =
        """{"created":1748372400,"model":"bytedance-seed/seedream-4.5","provider":"Fal",
           "data":[{"b64_json":"$b64","media_type":"image/png"}],
           "usage":{"prompt_tokens":0,"completion_tokens":4175,"total_tokens":4175,"cost":0.04}}"""

    @Test
    fun `sends a bearer-authenticated request and returns the parsed response`() {
        val fixture = testImageClient()
        fixture.server.expect(requestTo(IMAGES_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer k"))
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = fixture.client.generate(testImageRequest(), testContext)

        fixture.server.verify()
        assertEquals(1748372400L, response.created)
        assertEquals("bytedance-seed/seedream-4.5", response.model)
        assertEquals("Fal", response.provider)
        assertEquals(0.04, response.usage?.cost)
        assertEquals(4175, response.usage?.completionTokens)
        assertEquals(1, fixture.gate.calls)
        assertEquals(response, fixture.listener.successes.single())
    }

    @Test
    fun `decodes the returned image to bytes and to a data url`() {
        val fixture = testImageClient()
        fixture.server.expect(requestTo(IMAGES_URL))
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val image = fixture.client.generate(testImageRequest(), testContext).data.single()

        assertEquals("image/png", image.mediaType)
        assertContentEquals(pngBytes, image.bytes)
        assertEquals("data:image/png;base64,$b64", image.dataUrl)
    }

    @Test
    fun `omits every unset knob from the wire`() {
        val fixture = testImageClient()
        var body: JsonNode? = null
        fixture.server.expect(requestTo(IMAGES_URL))
            .andExpect(capturingBody { body = it })
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        fixture.client.generate(testImageRequest(), testContext)

        fixture.server.verify()
        // Only what was actually set, plus the provider object every request carries.
        assertEquals(
            setOf("model", "prompt", "provider"),
            body!!.propertyNames().asSequence().toSet(),
        )
        // ...and provider itself carries only zdr, so the routing fields stay off the wire too.
        assertEquals(setOf("zdr"), body!!.get("provider").propertyNames().asSequence().toSet())
    }

    @Test
    fun `serializes the image knobs under their wire names`() {
        val fixture = testImageClient()
        var body: JsonNode? = null
        fixture.server.expect(requestTo(IMAGES_URL))
            .andExpect(capturingBody { body = it })
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        fixture.client.generate(
            testImageRequest().copy(
                n = 2,
                resolution = "2K",
                aspectRatio = "16:9",
                outputFormat = "webp",
                outputCompression = 80,
                seed = 12345L,
                inputReferences = listOf(ImageReference.of("https://example.com/p.jpg")),
            ),
            testContext,
        )

        fixture.server.verify()
        val json = body!!
        assertEquals(2, json.get("n").asInt())
        assertEquals("2K", json.get("resolution").asString())
        assertEquals("16:9", json.get("aspect_ratio").asString())
        assertEquals("webp", json.get("output_format").asString())
        assertEquals(80, json.get("output_compression").asInt())
        assertEquals(12345L, json.get("seed").asLong())
        val reference = json.get("input_references").first()
        assertEquals("image_url", reference.get("type").asString())
        assertEquals("https://example.com/p.jpg", reference.get("image_url").get("url").asString())
    }

    @Test
    fun `retries a 429 and succeeds`() {
        val fixture = testImageClient()
        fixture.server.expect(requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        fixture.server.expect(requestTo(IMAGES_URL)).andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = fixture.client.generate(testImageRequest(), testContext)

        fixture.server.verify()
        assertEquals(1, response.data.size)
        assertEquals(0, fixture.listener.failures)
    }

    @Test
    fun `gives up after three server errors and records a failure`() {
        val fixture = testImageClient()
        repeat(3) { fixture.server.expect(requestTo(IMAGES_URL)).andRespond(withServerError()) }

        assertFailsWith<HttpServerErrorException> { fixture.client.generate(testImageRequest(), testContext) }

        fixture.server.verify()
        assertEquals(1, fixture.listener.failures)
        assertTrue(fixture.listener.successes.isEmpty())
    }

    @Test
    fun `does not retry a non-429 client error`() {
        val fixture = testImageClient()
        fixture.server.expect(requestTo(IMAGES_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertFailsWith<HttpClientErrorException> { fixture.client.generate(testImageRequest(), testContext) }

        fixture.server.verify()
        assertEquals(1, fixture.listener.failures)
    }

    @Test
    fun `a gate that refuses the call prevents any request`() {
        val fixture = testImageClient(gate = RecordingGate { _, _ -> throw IllegalStateException("no budget") })
        // No request expected.

        assertFailsWith<IllegalStateException> { fixture.client.generate(testImageRequest(), testContext) }

        fixture.server.verify()
        assertEquals(0, fixture.listener.failures)
    }

    @Test
    fun `the gate sees the image request through the shared AiRequest seam`() {
        var seen: AiRequest? = null
        val fixture = testImageClient(gate = RecordingGate { _, request -> seen = request })
        fixture.server.expect(requestTo(IMAGES_URL))
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        fixture.client.generate(testImageRequest(), testContext)

        val request = assertIs<ImageRequest>(seen)
        assertEquals("a red panda astronaut", request.prompt)
        assertEquals("bytedance-seed/seedream-4.5", request.model)
    }
}
