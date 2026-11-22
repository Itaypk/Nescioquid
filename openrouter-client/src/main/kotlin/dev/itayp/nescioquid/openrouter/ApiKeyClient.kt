package dev.itayp.nescioquid.openrouter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Reads the API key's own usage and spending limit from OpenRouter's `GET /key`.
 *
 * Not an accounted call: it costs nothing and doesn't count toward any limit, so it goes through
 * [OpenRouterTransport.restClient] with no gate, listener or retries. A failure throws; a consumer
 * polling this for metrics decides for itself what a missed reading means.
 */
@Component
class ApiKeyClient(private val transport: OpenRouterTransport) {

    /** The key's current usage and limit. Throws when OpenRouter can't be reached or rejects the key. */
    fun currentKey(): ApiKeyInfo {
        transport.requireOpenRouter("The key status endpoint")
        return transport.restClient.get()
            .uri("/key")
            .retrieve()
            .body(KeyResponse::class.java)
            ?.data
            ?: error("OpenRouter answered GET /key with no data")
    }
}

/**
 * The parts of OpenRouter's key description a consumer acts on. All amounts are USD.
 *
 * @param limit the key's own spending cap, or null when it has none.
 * @param limitRemaining what is left of [limit] in the current window, or null when there is no cap.
 *   Prefer this over `limit - usage` for "how close are we": with a resetting limit ([limitReset]),
 *   [usage] keeps counting across windows while the limit refills.
 * @param limitReset how often [limit] refills (`daily`, `weekly`, `monthly`), or null when it never does.
 * @param usage all-time spend on this key.
 * @param includeByokInLimit whether spend through the account's own provider keys counts toward [limit].
 * @param effectiveBudget the budget that will block requests first — a guardrail or workspace budget
 *   can be tighter than [limit], and [limit] doesn't show it. Null when nothing is budgeted.
 * @param expiresAt when the key stops working, or null when it doesn't expire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiKeyInfo(
    val label: String? = null,
    val limit: Double? = null,
    @JsonProperty("limit_remaining") val limitRemaining: Double? = null,
    @JsonProperty("limit_reset") val limitReset: String? = null,
    val usage: Double = 0.0,
    @JsonProperty("usage_daily") val usageDaily: Double = 0.0,
    @JsonProperty("usage_weekly") val usageWeekly: Double = 0.0,
    @JsonProperty("usage_monthly") val usageMonthly: Double = 0.0,
    @JsonProperty("include_byok_in_limit") val includeByokInLimit: Boolean = false,
    @JsonProperty("is_free_tier") val isFreeTier: Boolean = false,
    @JsonProperty("effective_budget") val effectiveBudget: KeyBudget? = null,
    @JsonProperty("expires_at") val expiresAt: Instant? = null,
)

/**
 * One budget layer OpenRouter enforces on the key (a guardrail, a workspace budget, or the key's own
 * limit). A [remainingUsd] of zero or less means requests are refused until [resetsAt].
 *
 * @param scope which layer this is (`key_limit`, `workspace`, `member`, …).
 * @param resetInterval `daily`, `weekly` or `monthly`, or null for a lifetime budget.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyBudget(
    val scope: String? = null,
    @JsonProperty("limit_usd") val limitUsd: Double = 0.0,
    @JsonProperty("spend_usd") val spendUsd: Double = 0.0,
    @JsonProperty("remaining_usd") val remainingUsd: Double = 0.0,
    @JsonProperty("reset_interval") val resetInterval: String? = null,
    @JsonProperty("resets_at") val resetsAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class KeyResponse(
    val data: ApiKeyInfo? = null,
)
