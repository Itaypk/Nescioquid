package dev.itayp.nescioquid.openrouter

/**
 * The minimal configuration the OpenRouter client core needs. The library classes ([AiClient],
 * [ModelCapabilityService]) depend only on this contract; the consumer supplies it as a bean
 * (typically derived from its own application config).
 */
data class AiClientProperties(
    val apiKey: String,
    val baseUrl: String,
    /** Model slugs to prefetch capabilities for at startup. */
    val configuredModels: Set<String>,
)
