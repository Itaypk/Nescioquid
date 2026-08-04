package dev.itayp.nescioquid.openrouter

import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.StringNode

/**
 * Lenient mapper used only to inspect a malformed LLM response for logging — tolerating JSON quirks
 * models commonly emit (unquoted keys, single quotes, trailing comments) so we can still show shape
 * even when the strict mapper used for real parsing rejected the same text outright.
 */
private val LENIENT_MAPPER: JsonMapper = JsonMapper.builder()
    .enable(
        JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES,
        JsonReadFeature.ALLOW_SINGLE_QUOTES,
        JsonReadFeature.ALLOW_JAVA_COMMENTS,
    )
    .build()

/**
 * Renders a sub-agent's malformed/unexpected JSON [response] safe to log. Object keys and
 * array/object structure are preserved (so a schema mismatch is diagnosable), but every leaf value
 * is passed through [redactValue] — by default redacted to its first/last 3 characters, or fully
 * masked at 6 characters or under, to avoid leaking short secrets/ids. LLM responses often echo
 * back user-authored text (e.g. a task title drafted from the user's own request), which typically
 * must not appear in logs at any level; this is the one safe way to still see what shape came back.
 *
 * [redactValue] is a hook for callers whose content isn't sensitive but is simply large — logging a
 * full tool-call argument or scene draft at DEBUG shouldn't dump kilobytes into one line. Pass
 * [truncateValue] (or your own function) to cap length instead of masking; the default,
 * [maskValue], is the one safe choice when the caller cannot vouch for what's in the content.
 *
 * Falls back to redacting the whole trimmed string as a single value when it isn't valid JSON at
 * all, not even under the LLM-quirk leniency above — no raw content leaks either way.
 */
fun redactLlmResponse(response: String, redactValue: (String) -> String = ::maskValue): String {
    val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return runCatching { LENIENT_MAPPER.readTree(cleaned) }
        .map { LENIENT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(redact(it, redactValue)) }
        .getOrElse { "(unparseable) ${redactValue(cleaned)}" }
}

private fun redact(node: JsonNode, redactValue: (String) -> String): JsonNode = when {
    node.isObject -> LENIENT_MAPPER.createObjectNode().also { redacted ->
        node.properties().forEach { (key, child) -> redacted.set(key, redact(child, redactValue)) }
    }
    node.isArray -> LENIENT_MAPPER.createArrayNode().also { redacted ->
        node.forEach { child -> redacted.add(redact(child, redactValue)) }
    }
    node.isNull -> node
    else -> StringNode(redactValue(node.asString()))
}

/** The default [redactLlmResponse] leaf redactor: masks content, on the assumption it may be sensitive. */
fun maskValue(value: String): String =
    if (value.length <= 6) "***" else "${value.take(3)}...${value.takeLast(3)}"

/**
 * A [redactLlmResponse] leaf redactor for content the caller already knows isn't sensitive, just
 * potentially long — keeps the first [maxLength] characters and notes how much was cut, rather
 * than masking most of the value the way [maskValue] does.
 */
fun truncateValue(maxLength: Int = 50): (String) -> String = { value ->
    if (value.length <= maxLength) value else "${value.take(maxLength)}… (${value.length} chars)"
}
