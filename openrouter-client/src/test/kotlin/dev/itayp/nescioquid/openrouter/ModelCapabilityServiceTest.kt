package dev.itayp.nescioquid.openrouter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class ModelCapabilityServiceTest {

    // ZDR off by default, so the capability tests don't each have to expect the /endpoints/zdr lookup.
    private fun service(
        apiKey: String = "k",
        models: Set<String> = emptySet(),
        zeroDataRetention: Boolean = false,
    ): Pair<ModelCapabilityService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties = AiClientProperties(
            apiKey = apiKey,
            baseUrl = "https://openrouter.ai/api/v1",
            configuredModels = models,
            zeroDataRetention = zeroDataRetention,
        )
        return ModelCapabilityService(properties, builder) to server
    }

    @Test
    fun `caches reasoning support from supported_parameters`() {
        val (svc, server) = service(models = setOf("openai/gpt-oss-20b:free"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/openai/gpt-oss-20b:free"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"data":{"supported_parameters":["temperature","reasoning","reasoning_effort"]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        server.verify()
        assertTrue(svc.supportsReasoning("openai/gpt-oss-20b:free"))
        // No `reasoning` object in this response → efforts/default unknown.
        assertNull(svc.get("openai/gpt-oss-20b:free")?.supportedEfforts)
        assertNull(svc.get("openai/gpt-oss-20b:free")?.defaultEffort)
    }

    @Test
    fun `captures the reasoning object and input modalities`() {
        val (svc, server) = service(models = setOf("google/gemini-3.1-flash-lite"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/google/gemini-3.1-flash-lite"))
            .andRespond(
                withSuccess(
                    """
                    {"data":{
                      "supported_parameters":["reasoning","reasoning_effort","temperature"],
                      "architecture":{"input_modalities":["text","image","audio"]},
                      "reasoning":{"mandatory":false,"default_enabled":true,
                        "supported_efforts":["high","medium","low","minimal"],"default_effort":"minimal"}
                    }}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        val caps = svc.get("google/gemini-3.1-flash-lite")
        assertTrue(caps!!.supportsReasoning)
        assertEquals(listOf("high", "medium", "low", "minimal"), caps.supportedEfforts)
        assertEquals("minimal", caps.defaultEffort)
        assertTrue(caps.reasoningDefaultEnabled)
        assertFalse(caps.reasoningMandatory)
        assertEquals(listOf("text", "image", "audio"), caps.inputModalities)
    }

    @Test
    fun `model without reasoning in supported_parameters is not supported`() {
        val (svc, server) = service(models = setOf("some/model"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/some/model"))
            .andRespond(
                withSuccess(
                    """{"data":{"supported_parameters":["temperature","max_tokens"]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        assertFalse(svc.supportsReasoning("some/model"))
    }

    @Test
    fun `fetch failure leaves the model unknown (not supported)`() {
        val (svc, server) = service(models = setOf("some/model"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/some/model"))
            .andRespond(withServerError())

        svc.prefetch()

        assertFalse(svc.supportsReasoning("some/model"))
    }

    @Test
    fun `blank api key skips prefetch entirely`() {
        val (svc, server) = service(apiKey = "", models = setOf("some/model"))
        // No request expected.

        svc.prefetch()

        server.verify()
        assertFalse(svc.supportsReasoning("some/model"))
    }

    @Test
    fun `exposes supported_parameters and reports structured-output support from it`() {
        val (svc, server) = service(models = setOf("some/model"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/some/model"))
            .andRespond(
                withSuccess(
                    """{"data":{"supported_parameters":["tools","structured_outputs","response_format"]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        assertEquals(
            listOf("tools", "structured_outputs", "response_format"),
            svc.get("some/model")?.supportedParameters,
        )
        assertTrue(svc.supportsStructuredOutputs("some/model"))
    }

    @Test
    fun `accepting response_format without structured_outputs is not structured-output support`() {
        val (svc, server) = service(models = setOf("some/model"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/some/model"))
            .andRespond(
                withSuccess(
                    // The distinction that matters: a model can take the parameter and still answer
                    // in prose. Only `structured_outputs` means the schema is enforced.
                    """{"data":{"supported_parameters":["tools","response_format"]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        assertFalse(svc.supportsStructuredOutputs("some/model"))
    }

    @Test
    fun `unknown model defaults to not supported`() {
        val (svc, _) = service()
        assertFalse(svc.supportsReasoning("never/fetched"))
        assertFalse(svc.supportsStructuredOutputs("never/fetched"))
    }

    @Test
    fun `captures output modalities and reports inline image output`() {
        val (svc, server) = service(models = setOf("google/gemini-3.1-flash-image"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/google/gemini-3.1-flash-image"))
            .andRespond(
                withSuccess(
                    """{"data":{"architecture":{"input_modalities":["text","image"],
                       "output_modalities":["text","image"]}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        server.verify()
        assertEquals(listOf("text", "image"), svc.get("google/gemini-3.1-flash-image")?.outputModalities)
        assertTrue(svc.supportsImageOutput("google/gemini-3.1-flash-image"))
    }

    @Test
    fun `a text-only model does not report image output`() {
        val (svc, server) = service(models = setOf("openai/gpt-oss-20b:free"))
        server.expect(requestTo("https://openrouter.ai/api/v1/model/openai/gpt-oss-20b:free"))
            .andRespond(
                withSuccess(
                    """{"data":{"architecture":{"input_modalities":["text"],"output_modalities":["text"]}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        assertFalse(svc.supportsImageOutput("openai/gpt-oss-20b:free"))
        // An unfetched model is likewise not assumed capable.
        assertFalse(svc.supportsImageOutput("some/other-model"))
    }

    private fun MockRestServiceServer.expectZdrModels(vararg models: String) {
        val data = models.joinToString(",") { """{"model_id":"$it","provider_name":"Google"}""" }
        expect(requestTo("https://openrouter.ai/api/v1/endpoints/zdr"))
            .andRespond(withSuccess("""{"data":[$data]}""", MediaType.APPLICATION_JSON))
    }

    private fun MockRestServiceServer.expectModel(model: String) {
        expect(requestTo("https://openrouter.ai/api/v1/model/$model"))
            .andRespond(withSuccess("""{"data":{}}""", MediaType.APPLICATION_JSON))
    }

    @Test
    fun `zdrModels lists the models with a ZDR endpoint, fetched once`() {
        val (svc, server) = service()
        // One model with two ZDR endpoints, and an entry with no model id.
        server.expect(requestTo("https://openrouter.ai/api/v1/endpoints/zdr"))
            .andRespond(
                withSuccess(
                    """{"data":[{"model_id":"a/one"},{"model_id":"a/one"},{"model_id":"b/two"},{"provider_name":"X"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(setOf("a/one", "b/two"), svc.zdrModels())
        assertEquals(setOf("a/one", "b/two"), svc.zdrModels())

        server.verify() // a single request
    }

    @Test
    fun `zdrModels throws when the lookup fails`() {
        val (svc, server) = service()
        server.expect(requestTo("https://openrouter.ai/api/v1/endpoints/zdr")).andRespond(withServerError())

        assertFailsWith<Exception> { svc.zdrModels() }
    }

    @Test
    fun `prefetch names the configured models that have no ZDR endpoint`(output: CapturedOutput) {
        val (svc, server) = service(models = setOf("a/zdr", "b/no-zdr:free"), zeroDataRetention = true)
        server.expectModel("a/zdr")
        server.expectModel("b/no-zdr:free")
        server.expectZdrModels("a/zdr", "b/no-zdr")

        svc.prefetch()

        server.verify()
        assertContains(output.all, "no zero-data-retention endpoint")
        assertContains(output.all, "[b/no-zdr:free]")
    }

    @Test
    fun `prefetch stays quiet when every configured model has a ZDR endpoint`(output: CapturedOutput) {
        val (svc, server) = service(models = setOf("a/zdr"), zeroDataRetention = true)
        server.expectModel("a/zdr")
        server.expectZdrModels("a/zdr")

        svc.prefetch()

        server.verify()
        assertFalse(output.all.contains("WARN"))
    }

    @Test
    fun `a failed ZDR lookup is logged, not thrown`(output: CapturedOutput) {
        val (svc, server) = service(models = setOf("a/zdr"), zeroDataRetention = true)
        server.expectModel("a/zdr")
        server.expect(requestTo("https://openrouter.ai/api/v1/endpoints/zdr")).andRespond(withServerError())

        svc.prefetch()

        server.verify()
        assertContains(output.all, "Couldn't fetch OpenRouter's zero-data-retention endpoints")
    }

    @Test
    fun `prefetch skips the ZDR lookup when zero data retention is off`() {
        val (svc, server) = service(models = setOf("b/no-zdr:free"))
        server.expectModel("b/no-zdr:free")

        svc.prefetch()

        server.verify() // no /endpoints/zdr request expected, none made
    }
}
