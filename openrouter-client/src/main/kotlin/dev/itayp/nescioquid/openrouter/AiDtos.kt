package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

// ── Request ──────────────────────────────────────────────────────────────────

data class ProviderPreferences(
    val zdr: Boolean = true,
)

data class ChatRequest(
    val model: String,
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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatMessage(
    val role: String,
    val content: MessageContent? = null,
    @JsonProperty("tool_calls") val toolCalls: List<ToolCall>? = null,
    @JsonProperty("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    /** Convenience for the common plain-string message (the only form we ever receive back). */
    constructor(
        role: String,
        content: String?,
        toolCalls: List<ToolCall>? = null,
        toolCallId: String? = null,
        name: String? = null,
    ) : this(role, content?.let { MessageContent.Text(it) }, toolCalls, toolCallId, name)

    /**
     * Content as a plain string. Responses always carry string content, so read sites use this to
     * stay type-safe; returns null when the content is the structured array form (which we only ever
     * build for outgoing requests).
     */
    @get:JsonIgnore
    val contentText: String?
        get() = (content as? MessageContent.Text)?.value

    companion object {
        /**
         * A message whose content is a single text part marked as a prompt-caching breakpoint
         * (`cache_control: {type: ephemeral}`). OpenRouter forwards the breakpoint to providers with
         * explicit caching (Anthropic, Gemini Flash Lite, …) and harmlessly ignores it for providers
         * that cache automatically. Use for the stable prefix — typically the system prompt.
         */
        fun cacheable(role: String, text: String): ChatMessage =
            ChatMessage(role = role, content = MessageContent.Parts(listOf(ContentPart(text = text, cacheControl = CacheControl()))))
    }
}

/**
 * A chat message's content. The OpenAI/OpenRouter wire schema is a union of a bare string and an
 * array of typed parts; [Text] and [Parts] model those two shapes. `@JsonValue` on each variant
 * keeps the serialized JSON as exactly that union (a raw string / a raw array — never a wrapper
 * object), and the delegating `@JsonCreator` deserializes an incoming string back into [Text]
 * (responses only ever carry string content).
 */
sealed interface MessageContent {

    data class Text(@get:JsonValue val value: String) : MessageContent

    data class Parts(@get:JsonValue val parts: List<ContentPart>) : MessageContent

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromString(value: String): MessageContent = Text(value)
    }
}

// A single part of the content-array form. Only `text` parts are used today.
data class ContentPart(
    val type: String = "text",
    val text: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("cache_control") val cacheControl: CacheControl? = null,
)

// OpenRouter prompt-caching breakpoint marker; `ephemeral` is the only supported type.
data class CacheControl(
    val type: String = "ephemeral",
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
    val usage: Usage?,
    // OpenRouter echoes the resolved model and the upstream provider it routed to. Both are
    // absent on non-OpenRouter backends, so they stay nullable. Captured for usage accounting.
    val model: String? = null,
    val provider: String? = null,
)

data class Choice(
    val message: ChatMessage,
    @JsonProperty("finish_reason") val finishReason: String,
)

data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
    @JsonProperty("total_tokens") val totalTokens: Int? = null,
    // Prompt-caching breakdown. Present only when the upstream provider reports it (models with
    // explicit/implicit caching); absent on plain responses, so the whole object stays nullable.
    @JsonProperty("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
)

// Sub-breakdown of the prompt tokens, primarily for prompt caching. Every field is nullable
// because providers populate different subsets (and OpenRouter omits the object entirely when
// none apply). See OpenRouter usage-accounting / prompt-caching docs.
data class PromptTokensDetails(
    // Prompt tokens served from cache (cache hits).
    @JsonProperty("cached_tokens") val cachedTokens: Int? = null,
    // Prompt tokens written to cache — only returned for models with explicit cache-write pricing.
    @JsonProperty("cache_write_tokens") val cacheWriteTokens: Int? = null,
    // Audio input tokens, when the prompt carried audio.
    @JsonProperty("audio_tokens") val audioTokens: Int? = null,
)
