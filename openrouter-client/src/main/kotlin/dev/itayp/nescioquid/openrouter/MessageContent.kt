package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.util.Base64

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
            ChatMessage(role = role, content = MessageContent.Parts(listOf(ContentPart.Text(text = text, cacheControl = CacheControl()))))

        /**
         * A message combining text with one or more non-text attachments — images, PDFs, audio (see
         * [ContentPart]). [text] is optional since a model can be sent an attachment with no prose
         * alongside it; when present it always becomes the first part, matching where a human would
         * put it in a chat bubble.
         *
         * The caller is expected to have already confirmed the target model accepts the modalities
         * being attached (`ModelCapabilityService.get(model)?.inputModalities`) — this constructor
         * does not check.
         */
        fun withAttachments(role: String, text: String? = null, attachments: List<ContentPart>): ChatMessage {
            val parts = buildList<ContentPart> {
                text?.let { add(ContentPart.Text(text = it)) }
                addAll(attachments)
            }
            return ChatMessage(role = role, content = MessageContent.Parts(parts))
        }
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

/**
 * One element of the content-array form of a message. OpenRouter (OpenAI-compatible) tells parts
 * apart by their `type`; each variant here is exactly the wire shape for one type, serialized as-is
 * with no wrapper — same as [MessageContent] itself. [Text] is the only part accepted in every
 * message role; [ImageUrl], [File] and [InputAudio] are *input* parts, sent on user messages to
 * models with the matching input modality.
 */
sealed interface ContentPart {

    data class Text(
        val type: String = "text",
        val text: String,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("cache_control") val cacheControl: CacheControl? = null,
    ) : ContentPart

    /**
     * An image, for vision-capable models. [ImageUrl.imageUrl]'s `url` may be a plain `https://` URL
     * or a `data:` URL carrying base64 bytes — build the latter with [ofBytes].
     */
    data class ImageUrl(
        val type: String = "image_url",
        @JsonProperty("image_url") val imageUrl: dev.itayp.nescioquid.openrouter.ImageUrl,
    ) : ContentPart {
        companion object {
            fun of(url: String): ImageUrl = ImageUrl(imageUrl = dev.itayp.nescioquid.openrouter.ImageUrl(url))

            fun ofBytes(bytes: ByteArray, mediaType: String = "image/png"): ImageUrl =
                of("data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}")
        }
    }

    /**
     * A document (PDF), for models with document support. OpenRouter parses it server-side — either
     * natively, for models that accept files directly, or via the `file-parser` plugin (configured
     * with [PluginConfig.pdfEngine] on [ChatRequest.plugins]) for models that don't — so this same
     * shape works regardless of what the target model natively accepts. [file]'s `file_data` may be a
     * plain `https://` URL or a `data:` URL carrying base64 bytes — build the latter with [ofBytes].
     */
    data class File(
        val type: String = "file",
        val file: FileData,
    ) : ContentPart {
        companion object {
            fun of(filename: String, url: String): File = File(file = FileData(filename, url))

            fun ofBytes(filename: String, bytes: ByteArray, mediaType: String = "application/pdf"): File =
                of(filename, "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}")
        }
    }

    /**
     * Audio input. Unlike [ImageUrl] and [File], OpenRouter does **not** accept a URL for audio —
     * [InputAudio.inputAudio]'s `data` must be base64-encoded bytes, so build this with [ofBytes]
     * rather than by hand. [InputAudioData.format] is the codec/container (`wav`, `mp3`, `aac`, `ogg`,
     * `flac`, `m4a`, `aiff`, `pcm16`, `pcm24`, …); check the target model's docs for which it accepts.
     */
    data class InputAudio(
        val type: String = "input_audio",
        @JsonProperty("input_audio") val inputAudio: InputAudioData,
    ) : ContentPart {
        companion object {
            fun ofBytes(bytes: ByteArray, format: String): InputAudio =
                InputAudio(inputAudio = InputAudioData(data = Base64.getEncoder().encodeToString(bytes), format = format))
        }
    }
}

// The `file` content part's payload: a filename plus the URL/data-URL OpenRouter fetches or decodes.
data class FileData(
    val filename: String,
    @JsonProperty("file_data") val fileData: String,
)

// The `input_audio` content part's payload: base64 bytes plus their codec/container.
data class InputAudioData(
    val data: String,
    val format: String,
)

// OpenRouter prompt-caching breakpoint marker; `ephemeral` is the only supported type.
data class CacheControl(
    val type: String = "ephemeral",
)
