package dev.itayp.nescioquid.openrouter

/**
 * Observer invoked by the client after each call resolves, for usage accounting / metrics.
 * Kept as a seam so the client core carries no dependency on the consumer's persistence or
 * metrics; the consumer wires its own implementation as a bean.
 *
 * Both parameters are the modality-agnostic supertypes, so one implementation covers every endpoint
 * the client speaks. [AiResponse] exposes the model, provider and [CallUsage] every response carries;
 * narrow to [ChatResponse] / [ImageResponse] / [TranscriptionResponse] for anything beyond that.
 *
 * Implementations must be best-effort — accounting must never break the AI call.
 */
interface AiCallListener {
    fun recordSuccess(context: AiCallContext, request: AiRequest, response: AiResponse)
    fun recordFailure(context: AiCallContext, request: AiRequest)
}
