package dev.itayp.nescioquid.openrouter

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What changes on the wire, and which features switch off, under [ApiDialect.OPENAI_COMPATIBLE]. */
class OpenAiCompatibleDialectTest {

    private val baseUrl = "http://localhost:11434/v1"
    private val objectMapper = jacksonObjectMapper()

    private val successBody =
        """{"id":"chatcmpl-1","model":"llama3.3",
           "choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":7,"completion_tokens":1}}"""

    private fun properties(apiKey: String = "") = AiClientProperties(
        apiKey = apiKey,
        baseUrl = baseUrl,
        configuredModels = setOf("llama3.3"),
        dialect = ApiDialect.OPENAI_COMPATIBLE,
        declaredCapabilities = mapOf("llama3.3" to ModelCapabilities(supportsReasoning = true, inputModalities = listOf("text", "image"))),
    )

    private fun transport(properties: AiClientProperties): Pair<AiTransport, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return AiTransport(properties, RecordingGate(), RecordingListener(), builder) to server
    }

    private fun capturingRequest(into: (MockClientHttpRequest, JsonNode) -> Unit) = RequestMatcher { request ->
        val mock = request as MockClientHttpRequest
        into(mock, objectMapper.readTree(mock.bodyAsString))
    }

    private val request = ChatRequest(
        model = "llama3.3",
        messages = listOf(ChatMessage("user", "Hi")),
        maxTokens = 300,
        reasoning = ReasoningConfig(effort = "low", exclude = true),
        plugins = listOf(PluginConfig.pdfEngine(PdfEngine.NATIVE)),
    )

    @Test
    fun `chat drops OpenRouter's extensions, maps the reasoning effort, and sends no key when none is set`() {
        val (transport, server) = transport(properties())
        var body: JsonNode? = null
        var authorization: String? = "unset"
        server.expect(requestTo("$baseUrl/chat/completions"))
            .andExpect(capturingRequest { req, json -> body = json; authorization = req.headers.getFirst("Authorization") })
            .andRespond(withSuccess(successBody, MediaType.APPLICATION_JSON))

        val response = AiClient(transport).chat(request, testContext)

        server.verify()
        assertEquals("hi", response.choices.first().message.contentText)
        val json = body!!
        assertEquals("llama3.3", json["model"].asString())
        assertEquals("low", json["reasoning_effort"].asString())
        assertEquals(300, json["max_completion_tokens"].asInt())
        for (field in listOf("provider", "plugins", "reasoning", "usage", "stream", "stream_options", "max_tokens")) {
            assertFalse(json.has(field), "unexpected field $field in $json")
        }
        assertNull(authorization)
    }

    @Test
    fun `a stream asks for usage through stream_options`() {
        val (transport, server) = transport(properties(apiKey = "sk-local"))
        var body: JsonNode? = null
        var authorization: String? = null
        server.expect(requestTo("$baseUrl/chat/completions"))
            .andExpect(capturingRequest { req, json -> body = json; authorization = req.headers.getFirst("Authorization") })
            .andRespond(
                withSuccess(
                    """data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""" + "\n\n" + "data: [DONE]\n\n",
                    MediaType.TEXT_EVENT_STREAM,
                ),
            )

        runBlocking { AiClient(transport).chatStream(request, testContext).toList() }

        server.verify()
        val json = body!!
        assertTrue(json["stream"].asBoolean())
        assertTrue(json["stream_options"]["include_usage"].asBoolean())
        assertFalse(json.has("usage"))
        assertEquals("Bearer sk-local", authorization)
    }

    @Test
    fun `capabilities come only from the declared ones, with nothing fetched`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val service = ModelCapabilityService(properties(apiKey = "sk-local").copy(zeroDataRetention = true), builder)

        service.prefetch()

        server.verify() // no request expected: no /model/{slug}, no /endpoints/zdr
        assertTrue(service.supportsReasoning("llama3.3"))
        assertEquals(listOf("text", "image"), service.get("llama3.3")?.inputModalities)
    }

    @Test
    fun `transcription goes as OpenAI's multipart form, without OpenRouter's provider preferences`() {
        val (transport, server) = transport(properties())
        var contentType: String? = null
        var body = ""
        server.expect(requestTo("$baseUrl/audio/transcriptions"))
            .andExpect { req ->
                val mock = req as MockClientHttpRequest
                contentType = mock.headers.contentType?.toString()
                body = mock.bodyAsString
            }
            .andRespond(withSuccess("""{"text":"buy milk","usage":{"type":"duration","seconds":2}}""", MediaType.APPLICATION_JSON))

        val request = testTranscriptionRequest(model = "whisper-1")
            .copy(language = "en", provider = ProviderPreferences(zdr = true))
        val response = TranscriptionClient(transport).transcribe(request, testContext)

        server.verify()
        assertEquals("buy milk", response.text)
        assertEquals(2.0, response.usage?.seconds)
        assertTrue(contentType!!.startsWith("multipart/form-data"), "content type was $contentType")
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""), body)
        for ((name, value) in listOf("model" to "whisper-1", "language" to "en", "response_format" to "json")) {
            assertTrue(Regex("name=\"$name\"[\\s\\S]*?\r\n\r\n$value\r\n").containsMatchIn(body), "missing $name=$value in $body")
        }
        assertFalse(body.contains("temperature"))
        assertFalse(body.contains("input_audio"))
        assertFalse(body.contains("zdr"))
    }

    @Test
    fun `OpenRouter-only clients refuse to run`() {
        val (transport, _) = transport(properties())

        assertFailsWith<IllegalStateException> { ImageClient(transport).generate(testImageRequest(), testContext) }
        assertFailsWith<IllegalStateException> { ApiKeyClient(transport).currentKey() }
    }
}
