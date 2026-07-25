package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
            callGate = AiCallGate { _, _ -> },
            callListener = object : AiCallListener {
                override fun recordSuccess(context: AiCallContext, request: ChatRequest, response: ChatResponse) = Unit
                override fun recordFailure(context: AiCallContext, request: ChatRequest) = Unit
            },
            // No configured effort -> ReasoningResolver.resolve() returns null immediately, so the
            // ModelCapabilityService never makes a /model call. The only network call is /chat/completions.
            reasoningResolver = ReasoningResolver(
                effortSource = { null },
                modelCapabilityService = ModelCapabilityService(properties),
            ),
        )
    }

    private fun context() = AiCallContext(userId = UUID.randomUUID(), conversationType = "integration-test")

    enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

    data class CapitalFact(
        val city: String,
        val country: String,
        @JsonPropertyDescription("Approximate metro-area population in millions") val populationMillions: Double,
    )

    @Test
    fun `structured output reply conforms to a schema generated from a DTO`() {
        assumeTrue(apiKey != null, "OPENROUTER_API_KEY not set; skipping live OpenRouter test")

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

        val response = aiClient().chat(request, context())
        val content = response.choices.first().message.contentText
        assertNotNull(content, "expected string content in the response")

        val fact = parseAssistantJsonResponse(objectMapper, content, CapitalFact::class.java)
        assertEquals("Paris", fact.city)
        assertTrue(fact.country.contains("France", ignoreCase = true), "country was '${fact.country}'")
        assertTrue(fact.populationMillions > 0, "population was ${fact.populationMillions}")
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

        val response = aiClient().chat(request, context())
        val toolCalls = response.choices.first().message.toolCalls
        assertNotNull(toolCalls, "expected the model to return a tool call")
        assertTrue(toolCalls.isNotEmpty(), "expected at least one tool call")

        val call = toolCalls.first()
        assertEquals("get_weather", call.function.name)
        // The arguments must parse into the DTO the schema was generated from.
        val query = objectMapper.readValue(call.function.arguments, WeatherQuery::class.java)
        assertTrue(query.location.contains("Tokyo", ignoreCase = true), "location was '${query.location}'")
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
