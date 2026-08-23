package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The image-model listing has a different shape from the chat `/model/{slug}` endpoint —
 * `supported_parameters` is an object of per-parameter specs rather than a flat list — so these
 * cover that mapping rather than repeating [ModelCapabilityServiceTest].
 */
class ImageModelCapabilityServiceTest {

    private val listingUrl = "https://openrouter.ai/api/v1/images/models"

    private val listing = """
        {"data":[
          {"id":"bytedance-seed/seedream-4.5","name":"Seedream 4.5",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image"]},
           "supported_parameters":{
             "resolution":{"type":"enum","values":["1K","2K","4K"]},
             "seed":{"type":"boolean"}
           },
           "supports_streaming":true},
          {"id":"acme/text-only-imager",
           "architecture":{"input_modalities":["text"],"output_modalities":["image"]},
           "supported_parameters":{"aspect_ratio":{"type":"enum","values":["1:1","16:9"]}},
           "supports_streaming":false}
        ]}
    """.trimIndent()

    private fun service(apiKey: String = "k"): Pair<ImageModelCapabilityService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties = AiClientProperties(
            apiKey = apiKey,
            baseUrl = "https://openrouter.ai/api/v1",
            configuredModels = emptySet(),
        )
        return ImageModelCapabilityService(properties, builder) to server
    }

    @Test
    fun `one listing call populates every model`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))

        svc.prefetch()

        server.verify()
        assertEquals(2, svc.all().size)
        assertEquals("Seedream 4.5", svc.get("bytedance-seed/seedream-4.5")?.name)
        assertEquals(listOf("image"), svc.get("acme/text-only-imager")?.outputModalities)
    }

    @Test
    fun `maps the object-shaped supported_parameters to per-parameter specs`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl)).andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))

        svc.prefetch()

        val caps = svc.get("bytedance-seed/seedream-4.5")!!
        assertTrue(caps.supports("resolution"))
        assertEquals(listOf("1K", "2K", "4K"), caps.allowedValues("resolution"))
        assertEquals("enum", caps.supportedParameters["resolution"]?.type)

        // A non-enum parameter is supported but unconstrained — the two cases must stay distinguishable.
        assertTrue(caps.supports("seed"))
        assertNull(caps.allowedValues("seed"))

        // An unsupported parameter reports neither.
        assertFalse(caps.supports("aspect_ratio"))
        assertNull(caps.allowedValues("aspect_ratio"))
    }

    @Test
    fun `captures streaming support and image input`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl)).andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))

        svc.prefetch()

        assertTrue(svc.get("bytedance-seed/seedream-4.5")!!.supportsStreaming)
        assertTrue(svc.get("bytedance-seed/seedream-4.5")!!.supportsImageInput())
        assertFalse(svc.get("acme/text-only-imager")!!.supportsStreaming)
        assertFalse(svc.get("acme/text-only-imager")!!.supportsImageInput())
    }

    @Test
    fun `an unknown model is null rather than an empty capability set`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl)).andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))

        svc.prefetch()

        assertNull(svc.get("openai/gpt-image-1"))
    }

    @Test
    fun `a blank api key skips the fetch entirely`() {
        val (svc, server) = service(apiKey = "")
        // No request expected.

        svc.prefetch()

        server.verify()
        assertTrue(svc.all().isEmpty())
    }

    @Test
    fun `a failed fetch leaves the cache empty instead of throwing`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl)).andRespond(withServerError())

        svc.prefetch()

        server.verify()
        assertTrue(svc.all().isEmpty())
    }

    @Test
    fun `a failed refresh keeps previously cached capabilities`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl)).andRespond(withSuccess(listing, MediaType.APPLICATION_JSON))
        server.expect(requestTo(listingUrl)).andRespond(withServerError())

        svc.prefetch()
        svc.refresh()

        server.verify()
        // A transient outage must not turn known capabilities into unknown ones.
        assertEquals(2, svc.all().size)
    }

    @Test
    fun `unknown fields in the listing are ignored`() {
        val (svc, server) = service()
        server.expect(requestTo(listingUrl)).andRespond(
            withSuccess(
                """{"data":[{"id":"a/b","created":1692901234,"pricing":{"image":"0.03"},
                   "endpoints":"/api/v1/images/models/a-b/endpoints",
                   "supported_parameters":{"quality":{"type":"enum","values":["low"],"note":"new"}}}]}""",
                MediaType.APPLICATION_JSON,
            ),
        )

        svc.prefetch()

        assertEquals(listOf("low"), svc.get("a/b")?.allowedValues("quality"))
    }
}
