package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Token (and, where reported, cost) accounting for one call. Shared across modalities: the
 * `/chat/completions` and `/images` endpoints report the same object.
 */
data class Usage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("completion_tokens") val completionTokens: Int,
    @JsonProperty("total_tokens") val totalTokens: Int? = null,
    // Prompt-caching breakdown. Present only when the upstream provider reports it (models with
    // explicit/implicit caching); absent on plain responses, so the whole object stays nullable.
    @JsonProperty("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    // Cost of the call in USD. Reported by `/images` always, and by `/chat/completions` whenever the
    // request asked for usage accounting (`usage.include`) — which `AiClient.chatStream` always does.
    override val cost: Double? = null,
) : CallUsage

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
