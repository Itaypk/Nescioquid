package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OpenRouter's provider-routing object. Accepted identically by every endpoint — `/chat/completions`,
 * `/images`, and the modality endpoints still to come — so it is shared rather than duplicated per
 * request type.
 *
 * Only [zdr] has a non-null default; every routing field is omitted from the wire when left null, so
 * a request that sets none of them serializes exactly as it did before they existed.
 */
data class ProviderPreferences(
    /** Zero-data-retention: restrict routing to providers that do not retain prompts. */
    val zdr: Boolean = true,
    /** Restrict routing to these provider slugs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val only: List<String>? = null,
    /** Try these provider slugs in this order before any others. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val order: List<String>? = null,
    /** Never route to these provider slugs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val ignore: List<String>? = null,
    /** Routing preference: `price`, `throughput` or `latency`. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val sort: String? = null,
    /** Whether a provider outside [only]/[order] may serve the request when the preferred ones fail. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("allow_fallbacks") val allowFallbacks: Boolean? = null,
)
