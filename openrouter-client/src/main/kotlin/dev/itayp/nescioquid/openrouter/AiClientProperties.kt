package dev.itayp.nescioquid.openrouter

import java.time.Duration

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
    /** Max time to establish the TCP connection. */
    val connectTimeout: Duration = Duration.ofSeconds(10),
    /**
     * Max time a single socket read may block — an *idle* timeout, not a deadline on the call.
     *
     * The two paths lean on it differently. [AiClient.chat] receives its whole reply in one go, so
     * this bounds the model's total generation time and wants to be generous. [AiClient.chatStream]
     * reads continuously, so it bounds the gap *between* events; a healthy stream never comes close,
     * because OpenRouter emits `: OPENROUTER PROCESSING` keepalives while a provider is thinking.
     * One value serves both, and without it a stalled provider hangs the caller forever.
     */
    val readTimeout: Duration = Duration.ofSeconds(120),
)
