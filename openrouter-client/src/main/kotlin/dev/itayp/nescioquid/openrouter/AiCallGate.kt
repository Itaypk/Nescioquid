package dev.itayp.nescioquid.openrouter

/**
 * Hook invoked immediately before the outbound OpenRouter call. A gate may throw to refuse
 * the call (the exception propagates to the caller) — e.g. to enforce an opt-out toggle or a
 * per-user rate/budget limit.
 *
 * Kept as a [fun interface] so it can be replaced with a no-op or a mock in tests. Provide an
 * implementation as a Spring bean; the library ships no default gate.
 */
fun interface AiCallGate {
    fun beforeCall(context: AiCallContext, request: ChatRequest)
}
