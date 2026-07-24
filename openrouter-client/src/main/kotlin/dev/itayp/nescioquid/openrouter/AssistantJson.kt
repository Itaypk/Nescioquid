package dev.itayp.nescioquid.openrouter

import tools.jackson.databind.ObjectMapper

/**
 * Parses a single JSON object out of an LLM response, tolerating surrounding prose or Markdown code
 * fences (```json ... ```) around it. Returns the deserialized [type].
 *
 * Throws if no `{ ... }` span is present or the span fails to deserialize, so callers can log the
 * details or propagate; they decide how to recover (e.g. fall back to an empty result).
 *
 * Useful for sub-agents that are asked for a JSON object but can't fully be relied on to omit
 * fences/prose.
 */
fun <T : Any> parseAssistantJsonResponse(objectMapper: ObjectMapper, raw: String, type: Class<T>): T {
    return objectMapper.readValue(extractJsonObjectSpan(raw), type)
}

/**
 * Returns the `{ ... }` span from an LLM response, tolerating surrounding prose or code fences.
 * Throws if no balanced-looking object span is present. Useful when the caller wants to inspect
 * the parsed tree before binding it to a type (e.g. branching on which of two shapes came back).
 */
fun extractJsonObjectSpan(raw: String): String {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start in 0 until end) { "No JSON object found in assistant response" }
    return raw.substring(start, end + 1)
}
