package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

// ── Request ──────────────────────────────────────────────────────────────────

data class ChatRequest(
    override val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    @JsonProperty("tool_choice") val toolChoice: String? = null,
    val provider: ProviderPreferences = ProviderPreferences(),
    // Reasoning/effort control (OpenRouter `reasoning` field). Omitted from the wire when null, so
    // the default is unchanged model behavior. Set by the caller — the client applies no central
    // reasoning policy.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val reasoning: ReasoningConfig? = null,
    // Structured outputs (OpenRouter `response_format` field). Omitted from the wire when null, so
    // the default is free-form output. Build one from a Kotlin DTO with `structuredOutput<T>(name)`
    // (see JsonSchemaGenerator).
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("response_format")
    val responseFormat: ResponseFormat? = null,
    // Server-sent-events streaming (OpenRouter `stream` field). Callers do not set this — it is a
    // transport concern that `AiClient.chatStream` applies to its own copy of the request. Omitted
    // from the wire when null, so `AiClient.chat` serializes exactly as it did before streaming
    // existed.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val stream: Boolean? = null,
    // Usage accounting (OpenRouter `usage` field). Also set by `chatStream` rather than the caller:
    // `include = true` is what makes OpenRouter emit the terminal usage chunk, without which a
    // streamed call could not report token counts to the AiCallListener.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val usage: UsageConfig? = null,
) : AiRequest

/** OpenRouter `usage` request field. `include = true` asks for token accounting in the response. */
data class UsageConfig(
    val include: Boolean = true,
)

/**
 * OpenRouter `response_format` for structured outputs. Constrains the model's reply to the JSON
 * Schema in [jsonSchema]. Prefer building it from a DTO via `structuredOutput<T>(name)`.
 */
data class ResponseFormat(
    val type: String = "json_schema",
    @JsonProperty("json_schema") val jsonSchema: JsonSchemaSpec,
)

/**
 * The `json_schema` payload: a caller-chosen [name], the [strict] flag (OpenRouter recommends
 * `true`), and the JSON Schema object itself. [JsonSchemaGenerator] produces strict-compatible
 * [schema] maps.
 */
data class JsonSchemaSpec(
    val name: String,
    val strict: Boolean = true,
    val schema: Map<String, Any>,
)

/**
 * OpenRouter `reasoning` request field. [effort] is the primary knob (`minimal|low|medium|high` —
 * OpenRouter normalizes it onto each provider's native reasoning control). [maxTokens] and [exclude]
 * are available for explicit callers; only [effort] is wired to per-functionality config today.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReasoningConfig(
    val effort: String? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    val exclude: Boolean? = null,
)

data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDetails,
)

data class FunctionCallDetails(
    val name: String,
    val arguments: String,
)

// ── Response ─────────────────────────────────────────────────────────────────

data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    override val usage: Usage?,
    // OpenRouter echoes the resolved model and the upstream provider it routed to. Both are
    // absent on non-OpenRouter backends, so they stay nullable. Captured for usage accounting.
    override val model: String? = null,
    override val provider: String? = null,
) : AiResponse

data class Choice(
    val message: ChatMessage,
    @JsonProperty("finish_reason") val finishReason: String,
)
