package dev.itayp.nescioquid.openrouter

/**
 * Supplies the configured reasoning effort for a given conversation type, or null when none is
 * configured. This is the consumer-specific half of reasoning resolution — [ReasoningResolver]
 * keeps only the generic validation against a model's advertised capabilities. The consumer
 * implements this from its own per-functionality config.
 */
fun interface ReasoningEffortSource {
    fun effortFor(conversationType: String): String?
}
