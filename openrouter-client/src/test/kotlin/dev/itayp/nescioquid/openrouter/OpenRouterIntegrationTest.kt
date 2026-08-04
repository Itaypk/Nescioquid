package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import org.springframework.web.client.HttpClientErrorException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live integration tests that exercise a generated JSON Schema against the **real OpenRouter API**,
 * to confirm the schema is exactly what OpenRouter accepts — for both structured outputs
 * (`response_format`) and function-tool parameters. These are the only tests that catch a
 * discrepancy between what we emit and what the provider actually requires.
 *
 * Gated on an OpenRouter API key: set `OPENROUTER_API_KEY` in the environment, or put it in a
 * `.env` file at the repo root or module dir (`.env` is git-ignored). When no key is resolvable the
 * tests are **skipped** (not failed) via `assumeTrue`, so they stay dormant in normal CI and only
 * run where a key is provided. Model defaults to `openai/gpt-5-nano` (which supports both structured
 * outputs and tool calls); override with `OPENROUTER_TEST_MODEL`.
 *
 * Tagged `integration` so they can be selected/excluded by tag if desired.
 */
@Tag("integration")
// Belt and braces alongside the client's own socket timeouts: a live call that wedges for any
// reason fails this test rather than running until the CI job is killed, which has happened twice.
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class OpenRouterIntegrationTest {

    private val apiKey: String? = resolveConfig("OPENROUTER_API_KEY")
    // Default must support both structured outputs AND tool calls; override with OPENROUTER_TEST_MODEL.
    // (Free models often support neither — e.g. gpt-oss-20b:free ignores response_format and has no
    // provider that enforces tool_choice.)
    private val model: String = resolveConfig("OPENROUTER_TEST_MODEL") ?: "openai/gpt-5-nano"
    private val objectMapper = jacksonObjectMapper()

    private fun aiClient(): AiClient {
        val key = requireNotNull(apiKey)
        val properties = AiClientProperties(
            apiKey = key,
            baseUrl = "https://openrouter.ai/api/v1",
            configuredModels = emptySet(),
        )
        return AiClient(
            properties = properties,
            callGate = { _, _ -> },
            callListener = object : AiCallListener {
                override fun recordSuccess(context: AiCallContext, request: ChatRequest, response: ChatResponse) = Unit
                override fun recordFailure(context: AiCallContext, request: ChatRequest) = Unit
            },
        )
    }

    private fun context() = AiCallContext(userId = UUID.randomUUID().toString(), conversationType = "integration-test")

    /**
     * Runs [block], **aborting** the test rather than failing it when OpenRouter rate-limits the
     * call. [AiClient] already retries a 429 three times with backoff, so reaching here means the
     * limit is sustained — a per-minute or daily cap on the key, which is an environmental
     * condition exactly like the missing-key case these tests already skip on. Failing CI for it
     * would make the live suite noise rather than signal; a genuine API or schema regression still
     * surfaces as a normal failure.
     *
     * `inline` so [block] inherits the caller's coroutine context — the streaming tests collect a
     * Flow inside it, which is a suspending call.
     */
    private inline fun <T> skippingRateLimits(block: () -> T): T =
        try {
            block()
        } catch (_: HttpClientErrorException.TooManyRequests) {
            abort("OpenRouter rate-limited this run (429 after retries); skipping the live check")
        }

    enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

    data class CapitalFact(
        val city: String,
        val country: String,
        @JsonPropertyDescription("Approximate metro-area population in millions") val populationMillions: Double,
    )

    /**
     * Whether the configured [model] *enforces* structured outputs, asked of OpenRouter rather than
     * assumed. A model that merely accepts `response_format` and then answers in prose (many do)
     * cannot satisfy the structured-output test, and that is a property of the configured model, not
     * a defect in the schema under test.
     */
    private fun modelEnforcesStructuredOutputs(): Boolean {
        val properties = AiClientProperties(
            apiKey = requireNotNull(apiKey),
            baseUrl = "https://openrouter.ai/api/v1",
            configuredModels = setOf(model),
        )
        return ModelCapabilityService(properties)
            .apply { prefetch() }
            .supportsStructuredOutputs(model)
    }

    @Test
    fun `structured output reply conforms to a schema generated from a DTO`() {
        assumeTrue(apiKey != null, "OPENROUTER_API_KEY not set; skipping live OpenRouter test")
        // Checked up front, so a model that *does* advertise the capability and still returns prose
        // fails loudly — that would be a real schema regression, which is what this test guards.
        assumeTrue(
            modelEnforcesStructuredOutputs(),
            "model '$model' does not advertise `structured_outputs`; skipping the structured-output check",
        )

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = "You answer with structured data only."),
                ChatMessage(role = "user", content = "What is the capital of France?"),
            ),
            responseFormat = structuredOutput<CapitalFact>("capital_fact"),
            // Relax the default zero-data-retention requirement so free/no-ZDR models (e.g.
            // openai/gpt-oss-20b:free) have a usable endpoint — this test validates the schema, not a
            // data policy. Without this, ZDR-only routing returns 404 "No endpoints found".
            provider = ProviderPreferences(zdr = false),
        )

        val response = skippingRateLimits { aiClient().chat(request, context()) }
        val content = response.choices.first().message.contentText
        assertNotNull(content, "expected string content in the response")

        val fact = parseAssistantJsonResponse(objectMapper, content, CapitalFact::class.java)
        assertEquals("Paris", fact.city)
        assertTrue(fact.country.contains("France", ignoreCase = true), "country was '${fact.country}'")
        assertTrue(fact.populationMillions > 0.0, "population was ${fact.populationMillions}")
    }

    data class WeatherQuery(
        @JsonPropertyDescription("City name, e.g. Tokyo") val location: String,
        val unit: TemperatureUnit,
    )

    @Test
    fun `schema generated from a DTO works as a function-tool parameter schema`() {
        assumeTrue(apiKey != null, "OPENROUTER_API_KEY not set; skipping live OpenRouter test")

        val tool = ToolDefinition(
            function = FunctionDefinition(
                name = "get_weather",
                description = "Get the current weather for a location",
                parameters = jsonSchema<WeatherQuery>(),
            ),
        )
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = "What's the weather in Tokyo? Use celsius.")),
            tools = listOf(tool),
            // "auto", not "required": the free model has no provider that enforces forced tool_choice
            // ("no online provider ... advertises inference-time tool_choice enforcement" -> 503).
            toolChoice = "auto",
            provider = ProviderPreferences(zdr = false), // see the structured-output test above
        )

        val response = skippingRateLimits { aiClient().chat(request, context()) }
        val toolCalls = response.choices.first().message.toolCalls
        assertNotNull(toolCalls, "expected the model to return a tool call")
        assertTrue(toolCalls.isNotEmpty(), "expected at least one tool call")

        val call = toolCalls.first()
        assertEquals("get_weather", call.function.name)
        // The arguments must parse into the DTO the schema was generated from.
        val query = objectMapper.readValue(call.function.arguments, WeatherQuery::class.java)
        assertTrue(query.location.contains("Tokyo", ignoreCase = true), "location was '${query.location}'")
    }

    @Test
    fun `chatStream delivers deltas that add up to the completed response`() = runBlocking {
        assumeTrue(apiKey != null, "OPENROUTER_API_KEY not set; skipping live OpenRouter test")

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = "Count from 1 to 20, separated by commas.")),
            provider = ProviderPreferences(zdr = false), // see the structured-output test above
        )

        val events = skippingRateLimits { aiClient().chatStream(request, context()).toList() }

        val deltas = events.filterIsInstance<ChatStreamEvent.ContentDelta>()
        assertTrue(deltas.size > 1, "expected the reply to arrive in more than one chunk, got ${deltas.size}")

        val completed = events.last() as ChatStreamEvent.Completed
        // The whole point of the aggregation: what was streamed is exactly what the caller ends up
        // with, matching the shape the blocking `chat` returns.
        assertEquals(deltas.joinToString("") { it.text }, completed.response.choices.first().message.contentText)
        assertEquals("stop", completed.response.choices.first().finishReason)
        // `usage.include` is set by chatStream, so the terminal chunk must carry token counts.
        val usage = assertNotNull(completed.response.usage, "expected usage on the terminal chunk")
        assertTrue(usage.completionTokens > 0, "expected completion tokens, got ${usage.completionTokens}")
    }

    @Test
    fun `chatStream reassembles a fragmented tool call`() = runBlocking {
        assumeTrue(apiKey != null, "OPENROUTER_API_KEY not set; skipping live OpenRouter test")

        val tool = ToolDefinition(
            function = FunctionDefinition(
                name = "get_weather",
                description = "Get the current weather for a location",
                parameters = jsonSchema<WeatherQuery>(),
            ),
        )
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = "What's the weather in Tokyo? Use celsius.")),
            tools = listOf(tool),
            toolChoice = "auto", // see the blocking tool-call test above
            provider = ProviderPreferences(zdr = false),
        )

        val events = skippingRateLimits { aiClient().chatStream(request, context()).toList() }

        val ready = events.filterIsInstance<ChatStreamEvent.ToolCallReady>()
        assertTrue(ready.isNotEmpty(), "expected the model to return a tool call")
        val call = ready.first().toolCall
        assertEquals("get_weather", call.function.name)
        // Arguments arrive one fragment at a time; only a correct reassembly parses.
        val query = objectMapper.readValue(call.function.arguments, WeatherQuery::class.java)
        assertTrue(query.location.contains("Tokyo", ignoreCase = true), "location was '${query.location}'")

        val completed = events.last() as ChatStreamEvent.Completed
        assertEquals(listOf(call), completed.response.choices.first().message.toolCalls)
    }

    private companion object {
        /** Resolve a value from the process environment, falling back to a `.env` file. */
        fun resolveConfig(key: String): String? =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotenv()[key]

        /** Parse simple `KEY=VALUE` lines from a `.env` in the module dir or the repo root, if present. */
        fun dotenv(): Map<String, String> {
            val file = listOf(File(".env"), File("../.env")).firstOrNull { it.isFile } ?: return emptyMap()
            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim().trim('"')
                }
        }
    }
}
