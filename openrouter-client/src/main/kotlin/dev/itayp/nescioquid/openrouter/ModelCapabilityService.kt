package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches, in memory, the capabilities of the configured models as reported by OpenRouter's
 * model-by-slug endpoint (`GET /model/{slug}`).
 *
 * We capture two things:
 * - **Reasoning support** — whether the model accepts the `reasoning` parameter (from
 *   `supported_parameters`) and, for reasoning models, the `reasoning` object's `supported_efforts`
 *   / `default_effort` (so we can validate configured effort levels and label the default). Sending
 *   a reasoning arg to a model that doesn't support it is an illegal argument, and an effort value
 *   outside `supported_efforts` is likewise rejected.
 * - **Input modalities** — `architecture.input_modalities` (text/image/audio/…), captured for
 *   multimodal-input features.
 *
 * The cache is populated once at startup (best-effort). An unfetched/unknown model returns null
 * capabilities, and callers treat that conservatively (reasoning omitted).
 */
@Component
class ModelCapabilityService(
    private val properties: AiClientProperties,
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(ModelCapabilityService::class.java)

    private val client: RestClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.apiKey.trim()}")
        .build()

    // model slug -> capabilities. Absent key means "unknown" (fetch failed or not attempted).
    private val capabilities = ConcurrentHashMap<String, ModelCapabilities>()

    /** Prefetch capabilities for every configured model once the app is up. Never blocks/fails boot. */
    @EventListener(ApplicationReadyEvent::class)
    fun prefetch() {
        if (properties.apiKey.isBlank()) {
            log.info("AI API key not configured; skipping model capability prefetch")
            return
        }
        for (model in properties.configuredModels) {
            fetch(model)
        }
    }

    /** Cached capabilities for [model], or null when unknown (not fetched / fetch failed). */
    fun get(model: String): ModelCapabilities? = capabilities[model]

    /** Convenience: whether [model] accepts the reasoning parameter. False when unknown. */
    fun supportsReasoning(model: String): Boolean = capabilities[model]?.supportsReasoning == true

    /**
     * Convenience: whether [model] *enforces* `response_format: {type: json_schema}`. False when
     * unknown. A model without this will happily accept the parameter and then answer in prose, so
     * check it before relying on a structured reply.
     */
    fun supportsStructuredOutputs(model: String): Boolean =
        capabilities[model]?.supportedParameters?.contains("structured_outputs") == true

    private fun fetch(model: String) {
        try {
            // Concatenate the slug into the path rather than passing it as a URI variable: a slug like
            // "openai/gpt-oss-20b:free" contains a slash that would otherwise be percent-encoded
            // (%2F), which OpenRouter's model endpoint does not resolve.
            val data = client.get()
                .uri("/model/$model")
                .retrieve()
                .body(ModelResponse::class.java)
                ?.data
            val caps = ModelCapabilities(
                supportsReasoning = data?.supportedParameters?.contains("reasoning") == true,
                supportedEfforts = data?.reasoning?.supportedEfforts,
                defaultEffort = data?.reasoning?.defaultEffort,
                reasoningMandatory = data?.reasoning?.mandatory ?: false,
                reasoningDefaultEnabled = data?.reasoning?.defaultEnabled ?: false,
                inputModalities = data?.architecture?.inputModalities ?: emptyList(),
                supportedParameters = data?.supportedParameters ?: emptyList(),
            )
            capabilities[model] = caps
            log.info(
                "Fetched model capabilities: model={} supportsReasoning={} supportedEfforts={} defaultEffort={} inputModalities={}",
                model, caps.supportsReasoning, caps.supportedEfforts, caps.defaultEffort, caps.inputModalities,
            )
        } catch (e: Exception) {
            // Best-effort: leave the model unknown (reasoning omitted) rather than failing startup.
            log.warn("Failed to fetch model capabilities for model={}; reasoning will be omitted", model, e)
        }
    }
}

/**
 * Cached capabilities for a single model.
 *
 * @param supportsReasoning whether the model accepts the `reasoning` parameter.
 * @param supportedEfforts the effort levels the model accepts, or null when the model doesn't report
 *   them (reasoning models that omit the `reasoning` object). Null means "validate against the
 *   known-effort fallback" rather than "no efforts".
 * @param defaultEffort the effort the model uses when reasoning is enabled without an explicit level.
 * @param reasoningMandatory whether reasoning cannot be disabled.
 * @param reasoningDefaultEnabled whether reasoning is on by default (no explicit request needed).
 * @param inputModalities accepted input modalities (text/image/audio/…), for multimodal input.
 * @param supportedParameters the raw `supported_parameters` list OpenRouter reports (`tools`,
 *   `structured_outputs`, `response_format`, `reasoning`, …). Empty when unknown. Exposed whole
 *   because callers need to ask about capabilities this class has no dedicated accessor for.
 */
data class ModelCapabilities(
    val supportsReasoning: Boolean,
    val supportedEfforts: List<String>? = null,
    val defaultEffort: String? = null,
    val reasoningMandatory: Boolean = false,
    val reasoningDefaultEnabled: Boolean = false,
    val inputModalities: List<String> = emptyList(),
    val supportedParameters: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ModelResponse(
    val data: ModelData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ModelData(
    @JsonProperty("supported_parameters") val supportedParameters: List<String>? = null,
    val reasoning: ReasoningInfo? = null,
    val architecture: Architecture? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ReasoningInfo(
    @JsonProperty("supported_efforts") val supportedEfforts: List<String>? = null,
    @JsonProperty("default_effort") val defaultEffort: String? = null,
    val mandatory: Boolean = false,
    @JsonProperty("default_enabled") val defaultEnabled: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class Architecture(
    @JsonProperty("input_modalities") val inputModalities: List<String>? = null,
)
