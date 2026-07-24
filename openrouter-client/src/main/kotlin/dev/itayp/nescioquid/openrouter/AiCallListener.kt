package dev.itayp.nescioquid.openrouter

/**
 * Observer invoked by [AiClient] after each call resolves, for usage accounting / metrics.
 * Kept as a seam so the client core carries no dependency on the consumer's persistence or
 * metrics; the consumer wires its own implementation as a bean.
 *
 * Implementations must be best-effort — accounting must never break the AI call.
 */
interface AiCallListener {
    fun recordSuccess(context: AiCallContext, request: ChatRequest, response: ChatResponse)
    fun recordFailure(context: AiCallContext, request: ChatRequest)
}
