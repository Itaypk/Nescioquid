package dev.itayp.nescioquid.openrouter

import dev.itayp.nescioquid.openrouter.OpenRouterIntegrationTest.Companion.resolveConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live checks of [ApiDialect.OPENAI_COMPATIBLE] against a real server: that what the client sends
 * is accepted, and what comes back parses — blocking, with a tool, and streamed with usage.
 *
 * Skipped unless `OPENAI_COMPATIBLE_TEST_BASE_URL` and `OPENAI_COMPATIBLE_TEST_MODEL` are set (in the
 * environment or `.env`), with an optional `OPENAI_COMPATIBLE_TEST_API_KEY`, and an optional
 * `OPENAI_COMPATIBLE_TEST_REASONING_EFFORT` sent as `reasoning_effort` (OpenAI's newer reasoning
 * models need `none` to accept the tool in the tool test). A local Ollama is the
 * cheapest target: `ollama pull qwen2.5:0.5b`, then `http://localhost:11434/v1` and `qwen2.5:0.5b`.
 */
@Tag("integration")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OpenAiCompatibleIntegrationTest {

    private val baseUrl = resolveConfig("OPENAI_COMPATIBLE_TEST_BASE_URL")
    private val model = resolveConfig("OPENAI_COMPATIBLE_TEST_MODEL")
    private val reasoningEffort = resolveConfig("OPENAI_COMPATIBLE_TEST_REASONING_EFFORT")

    @BeforeEach
    fun requireServer() = assumeTrue(baseUrl != null && model != null, "no OpenAI-compatible test server configured")

    private fun client() = AiClient(
        AiTransport(
            properties = AiClientProperties(
                apiKey = resolveConfig("OPENAI_COMPATIBLE_TEST_API_KEY").orEmpty(),
                baseUrl = baseUrl!!,
                configuredModels = setOf(model!!),
                dialect = ApiDialect.OPENAI_COMPATIBLE,
            ),
            callGate = { _, _ -> },
            callListener = object : AiCallListener {
                override fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse) = Unit
                override fun recordFailure(context: AiCallContext, request: AiRequest) = Unit
            },
        ),
    )

    private val context = AiCallContext(userId = "integration", conversationType = "integration-test")

    // Everything the OpenRouter dialect would add, so the test proves it is stripped, not just absent.
    private fun request(prompt: String, tools: List<ToolDefinition>? = null) = ChatRequest(
        model = model!!,
        messages = listOf(ChatMessage("user", prompt)),
        tools = tools,
        maxTokens = 200,
        reasoning = reasoningEffort?.let { ReasoningConfig(effort = it) },
        plugins = listOf(PluginConfig.pdfEngine(PdfEngine.NATIVE)),
    )

    @Test
    fun `a blocking chat is accepted and parsed, with usage`() {
        val response = client().chat(request("Reply with the single word: pong"), context)

        assertTrue(response.choices.first().message.contentText.orEmpty().isNotBlank())
        assertNotNull(response.usage)
    }

    @Test
    fun `a tool definition is accepted and a tool call parses`() {
        val tool = ToolDefinition(
            function = FunctionDefinition(
                name = "add_task",
                description = "Adds a task to the user's backlog",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf("title" to mapOf("type" to "string")),
                    "required" to listOf("title"),
                ),
            ),
        )

        val message = client().chat(request("Add a task titled 'buy milk'. Use the add_task tool.", listOf(tool)), context)
            .choices.first().message

        // A tiny local model may answer in prose instead; what matters is that the server took the
        // tools array and anything it returned deserialized.
        message.toolCalls?.forEach { assertTrue(it.function.name.isNotBlank()) }
    }

    @Test
    fun `a stream completes and reports usage through stream_options`() {
        val events = runBlocking { client().chatStream(request("Count from 1 to 5."), context).toList() }

        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        assertTrue(completed.response.choices.first().message.contentText.orEmpty().isNotBlank())
        assertNotNull(completed.response.usage, "no usage chunk: stream_options.include_usage wasn't honored")
    }
}
