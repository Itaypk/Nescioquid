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
 * Caches, in memory, what OpenRouter's image-generation models accept, as reported by
 * `GET /images/models`.
 *
 * A sibling of [ModelCapabilityService] rather than part of it, because image models are described
 * by a genuinely different endpoint and shape:
 * - `supported_parameters` is an **object** keyed by parameter name, each value carrying a type and
 *   (for enums) the legal values — versus the flat `List<String>` the chat `/model/{slug}` endpoint
 *   returns. This is the only place the allowed `resolution` / `aspect_ratio` / `quality` /
 *   `output_format` values for a model are published, which is exactly what a caller assembling an
 *   [ImageRequest] needs, since OpenRouter *rejects* an unsupported parameter rather than ignoring it.
 * - `supports_streaming` has no chat-side counterpart.
 * - It is a **listing**, not a by-slug lookup: one request describes every image model, so unlike
 *   [ModelCapabilityService] there is no set of configured slugs to enumerate and prefetch costs a
 *   single call.
 *
 * The cache is populated once at startup (best-effort — a failure leaves it empty rather than
 * breaking boot) and can be repopulated on demand with [refresh]. An unknown model returns null, and
 * callers should treat that as "don't know" rather than "unsupported".
 */
@Component
class ImageModelCapabilityService(
    private val properties: AiClientProperties,
    // Same arrangement as ModelCapabilityService: null means "build our own", which carries the
    // timeouts. Without them a hung capability fetch blocks whatever triggered it indefinitely.
    restClientBuilder: RestClient.Builder? = null,
) {
    private val log = LoggerFactory.getLogger(ImageModelCapabilityService::class.java)

    private val client: RestClient = openRouterRestClient(properties, restClientBuilder, properties.readTimeout)

    // model slug -> capabilities. Empty means "unknown" (fetch failed or not attempted).
    private val capabilities = ConcurrentHashMap<String, ImageModelCapabilities>()

    /** Fetch the image-model listing once the app is up. Never blocks or fails boot. */
    @EventListener(ApplicationReadyEvent::class)
    fun prefetch() {
        if (properties.apiKey.isBlank()) {
            log.info("AI API key not configured; skipping image model capability prefetch")
            return
        }
        refresh()
    }

    /** Cached capabilities for [slug], or null when unknown (not fetched / fetch failed). */
    fun get(slug: String): ImageModelCapabilities? = capabilities[slug]

    /** Every image model in the cached listing. Empty when the fetch has not run or failed. */
    fun all(): List<ImageModelCapabilities> = capabilities.values.toList()

    /**
     * Re-fetches the listing. Best-effort: on failure the previously cached listing is left intact
     * rather than being cleared, so a transient outage does not turn known capabilities into unknown.
     */
    fun refresh() {
        try {
            val models = client.get()
                .uri("/images/models")
                .retrieve()
                .body(ImageModelListResponse::class.java)
                ?.data
                .orEmpty()
            for (model in models) {
                val id = model.id ?: continue
                capabilities[id] = ImageModelCapabilities(
                    id = id,
                    name = model.name,
                    inputModalities = model.architecture?.inputModalities ?: emptyList(),
                    outputModalities = model.architecture?.outputModalities ?: emptyList(),
                    supportedParameters = model.supportedParameters ?: emptyMap(),
                    supportsStreaming = model.supportsStreaming ?: false,
                )
            }
            log.info("Fetched image model capabilities: models={}", capabilities.size)
        } catch (e: Exception) {
            // Best-effort: leave whatever is cached in place rather than failing startup.
            log.warn("Failed to fetch image model capabilities; image model parameters will be unknown", e)
        }
    }
}

/**
 * Cached capabilities for a single image-generation model.
 *
 * @param supportedParameters the parameters this model accepts, keyed by the [ImageRequest] wire name
 *   (`resolution`, `aspect_ratio`, `seed`, …). A parameter absent from this map is one OpenRouter will
 *   reject for this model.
 * @param supportsStreaming whether the model can stream partial renders over SSE. The client does not
 *   implement image streaming yet; this is reported for callers choosing a model.
 */
data class ImageModelCapabilities(
    val id: String,
    val name: String? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val supportedParameters: Map<String, ImageParameterSpec> = emptyMap(),
    val supportsStreaming: Boolean = false,
) {
    /** Whether this model accepts [parameter], named as it appears on the wire (`aspect_ratio`, …). */
    fun supports(parameter: String): Boolean = parameter in supportedParameters

    /**
     * The values [parameter] accepts, or null when the model does not constrain it to a list — either
     * because it isn't an enum (a `seed` is any integer) or because the model doesn't accept the
     * parameter at all. Check [supports] to tell those two apart.
     */
    fun allowedValues(parameter: String): List<String>? = supportedParameters[parameter]?.values

    /** Whether this model can take reference images, i.e. do image-to-image and editing. */
    fun supportsImageInput(): Boolean = inputModalities.contains("image")
}

/**
 * One entry of an image model's `supported_parameters` object: the parameter's declared [type]
 * (`enum`, `boolean`, `integer`, …) and, for enums, its legal [values].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageParameterSpec(
    val type: String? = null,
    val values: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ImageModelListResponse(
    val data: List<ImageModelData>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ImageModelData(
    val id: String? = null,
    val name: String? = null,
    val architecture: ImageArchitecture? = null,
    @JsonProperty("supported_parameters") val supportedParameters: Map<String, ImageParameterSpec>? = null,
    @JsonProperty("supports_streaming") val supportsStreaming: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ImageArchitecture(
    @JsonProperty("input_modalities") val inputModalities: List<String>? = null,
    @JsonProperty("output_modalities") val outputModalities: List<String>? = null,
)
