package dev.itayp.nescioquid.openrouter

/**
 * Which API the client is talking to. Both share the OpenAI chat-completions shape, so one set of
 * DTOs serves both; what differs is the handful of OpenRouter extensions the client sends and the
 * OpenRouter-only endpoints it calls.
 */
enum class ApiDialect {
    /** OpenRouter: every extension (`provider`, `plugins`, `reasoning`, `usage`) and endpoint is available. */
    OPENROUTER,

    /**
     * Any server that speaks the OpenAI chat-completions API: OpenAI itself, a first-party provider's
     * OpenAI-compatible endpoint, or a self-hosted server (vLLM, Ollama, llama.cpp, …).
     *
     * - Chat requests are sent without OpenRouter's extensions: `provider` and `plugins` are dropped,
     *   `reasoning.effort` becomes `reasoning_effort`, and a stream's usage request becomes
     *   `stream_options.include_usage`. Messages go out as given, so don't mark them `cacheable`.
     * - Nothing is fetched from OpenRouter's `/model/{slug}`, `/endpoints/zdr` or `/images/models`:
     *   [ModelCapabilityService] knows only [AiClientProperties.declaredCapabilities], and
     *   zero data retention can't be checked or requested, so it is the provider's contract to keep.
     * - [ImageClient], [TranscriptionClient] and [ApiKeyClient] speak OpenRouter-only request shapes
     *   or endpoints, and refuse to run.
     */
    OPENAI_COMPATIBLE,
}
