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
     * Max time a socket read may block on the **blocking** [AiClient.chat] path. The whole reply
     * arrives in one go, so in practice this bounds the model's total generation time and needs to
     * be generous — a slow reasoning model legitimately takes a minute or more.
     */
    val readTimeout: Duration = Duration.ofSeconds(120),
    /**
     * Max gap between two events on a **stream** before it is treated as stalled.
     *
     * Deliberately much tighter than [readTimeout]: this is an idle timeout between events, not a
     * budget for the whole generation, and OpenRouter emits `: OPENROUTER PROCESSING` keepalives
     * while a provider is thinking — so a healthy stream never approaches it however long the
     * completion runs. Sharing [readTimeout] here made a stalled stream take two minutes to fail,
     * which is long enough to blow a CI budget several times over.
     */
    val streamIdleTimeout: Duration = Duration.ofSeconds(30),
    /**
     * Max time a socket read may block on an [ImageClient.generate] call.
     *
     * Separate from [readTimeout] because image generation is a different order of work: a 2K render
     * routinely runs well past the budget a text completion needs, and raising [readTimeout] to suit
     * it would leave a hung *chat* call sitting on the socket for minutes.
     */
    val imageReadTimeout: Duration = Duration.ofSeconds(180),
)
