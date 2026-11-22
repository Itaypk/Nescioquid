package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The wire shape of a [ChatRequest] for [ApiDialect.OPENAI_COMPATIBLE]: the same request without
 * OpenRouter's extensions, which a strict OpenAI-compatible server rejects as unknown arguments.
 *
 * A separate type rather than nulling fields on [ChatRequest], so that what each dialect puts on the
 * wire is visible in one place. Build it with [ChatRequest.toOpenAiCompatible].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OpenAiCompatibleChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    @JsonProperty("tool_choice") val toolChoice: String? = null,
    @JsonProperty("reasoning_effort") val reasoningEffort: String? = null,
    @JsonProperty("response_format") val responseFormat: ResponseFormat? = null,
    val stream: Boolean? = null,
    @JsonProperty("stream_options") val streamOptions: StreamOptions? = null,
)

/** OpenAI's `stream_options`. `includeUsage` asks for the terminal usage chunk, as OpenRouter's `usage.include` does. */
data class StreamOptions(
    @JsonProperty("include_usage") val includeUsage: Boolean,
)

/**
 * Maps a request onto [OpenAiCompatibleChatRequest]. Dropped on the way: [ChatRequest.provider],
 * [ChatRequest.plugins], and [ReasoningConfig.maxTokens] / [ReasoningConfig.exclude], which have no
 * OpenAI counterpart. `stream_options` is only sent on a stream, since OpenAI refuses it otherwise.
 */
fun ChatRequest.toOpenAiCompatible(): OpenAiCompatibleChatRequest = OpenAiCompatibleChatRequest(
    model = model,
    messages = messages,
    tools = tools,
    temperature = temperature,
    maxTokens = maxTokens,
    toolChoice = toolChoice,
    reasoningEffort = reasoning?.effort,
    responseFormat = responseFormat,
    stream = stream,
    streamOptions = if (stream == true && usage?.include == true) StreamOptions(includeUsage = true) else null,
)
