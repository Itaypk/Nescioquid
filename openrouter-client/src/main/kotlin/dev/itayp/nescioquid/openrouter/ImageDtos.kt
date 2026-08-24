package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Base64

// ── Request ──────────────────────────────────────────────────────────────────

/**
 * A generation request for OpenRouter's dedicated image endpoint (`POST /images`).
 *
 * Every knob beyond [model] and [prompt] is optional and omitted from the wire when null, so a
 * request that sets none of them is `{"model": …, "prompt": …, "provider": …}` and the model applies
 * its own defaults. Which knobs a given model actually honors — and their legal values — is reported
 * per model by [ImageModelCapabilityService]; OpenRouter rejects a parameter the model does not
 * support rather than ignoring it, so check before setting one.
 *
 * Note there is no `stream` field: SSE streaming of partial renders is not implemented yet, and a
 * field nothing can set would misrepresent the surface.
 *
 * @param n how many images to generate, 1–10.
 * @param size shorthand for the output dimensions — either a tier (`2K`) or explicit pixels (`2048x2048`).
 * @param resolution output resolution tier: `512`, `1K`, `2K` or `4K`.
 * @param aspectRatio output aspect ratio, e.g. `1:1`, `16:9`, `9:16`, `4:3`.
 * @param quality render quality: `auto`, `low`, `medium` or `high`.
 * @param outputFormat encoding of the returned bytes: `png`, `jpeg`, `webp` or `svg`.
 * @param background `auto`, `transparent` or `opaque`, on models that can render an alpha channel.
 * @param outputCompression 0–100, for the lossy [outputFormat]s (`webp`, `jpeg`) only.
 * @param seed fixes the sampling seed, for reproducible generations.
 * @param inputReferences reference images for image-to-image generation and editing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImageRequest(
    override val model: String,
    val prompt: String,
    val n: Int? = null,
    val size: String? = null,
    val resolution: String? = null,
    @JsonProperty("aspect_ratio") val aspectRatio: String? = null,
    val quality: String? = null,
    @JsonProperty("output_format") val outputFormat: String? = null,
    val background: String? = null,
    @JsonProperty("output_compression") val outputCompression: Int? = null,
    val seed: Long? = null,
    @JsonProperty("input_references") val inputReferences: List<ImageReference>? = null,
    val provider: ProviderPreferences = ProviderPreferences(),
) : AiRequest

/**
 * A reference image supplied to an image-to-image generation. The URL may be a plain `https://` URL
 * or a `data:` URL carrying base64 bytes — build the latter with [ImageReference.ofBytes].
 */
data class ImageReference(
    val type: String = "image_url",
    @JsonProperty("image_url") val imageUrl: ImageUrl,
) {
    companion object {
        fun of(url: String): ImageReference = ImageReference(imageUrl = ImageUrl(url))

        fun ofBytes(bytes: ByteArray, mediaType: String = "image/png"): ImageReference =
            of("data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}")
    }
}

data class ImageUrl(val url: String)

// ── Response ─────────────────────────────────────────────────────────────────

/**
 * The result of an image generation. Unlike [ChatResponse] there is no id — OpenRouter identifies
 * the generation only by its [created] timestamp.
 *
 * Image billing is all-or-nothing: a generation either completes and is billed in full via
 * [Usage.cost], or it fails and is not billed at all. There is no partial billing to reconcile.
 */
data class ImageResponse(
    val created: Long? = null,
    val data: List<ImageData> = emptyList(),
    override val usage: Usage? = null,
    override val model: String? = null,
    override val provider: String? = null,
) : AiResponse

/**
 * One generated image, returned as base64-encoded bytes rather than a URL.
 *
 * [mediaType] reflects what the provider actually produced, which is not necessarily the requested
 * [ImageRequest.outputFormat] — read it rather than assuming PNG when writing the bytes out.
 */
data class ImageData(
    @JsonProperty("b64_json") val b64Json: String,
    @JsonProperty("media_type") val mediaType: String = "image/png",
) {
    /**
     * The decoded image bytes. Decodes on every read and returns a fresh array each time, so hold on
     * to the result rather than calling this in a loop.
     *
     * Note the enclosing data class's generated `equals`/`hashCode` compare the base64 [b64Json]
     * string, not these bytes — which is the right comparison, but means two `ImageData` holding
     * equal bytes under different encodings would not compare equal.
     */
    @get:JsonIgnore
    val bytes: ByteArray get() = Base64.getDecoder().decode(b64Json)

    /** The image as a `data:` URL, ready to embed in an `<img src>` or hand back to a browser. */
    @get:JsonIgnore
    val dataUrl: String get() = "data:$mediaType;base64,$b64Json"
}
