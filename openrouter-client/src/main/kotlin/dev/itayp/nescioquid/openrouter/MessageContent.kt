package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

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
