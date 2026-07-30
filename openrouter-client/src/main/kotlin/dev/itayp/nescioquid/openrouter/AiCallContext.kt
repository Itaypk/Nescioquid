package dev.itayp.nescioquid.openrouter

/**
 * Caller-supplied context attached to every [AiClient.chat] call so the [AiCallGate] and
 * [AiCallListener] can attribute the call to a user, a conversation type, and — when the call
 * belongs to a stored multi-turn conversation — the conversation (and, where the caller has it,
 * the session).
 *
 * Every id here is an **opaque string**, exactly as [conversationType] already was. The library
 * never parses, compares or stores them; they exist to be handed back to the consumer's own gate
 * and listener, which are the only code that knows what an id means. Typing them as `UUID` (as
 * 0.5.0 did) forced consumers whose keys are not UUIDs to invent a mapping for an *attribution
 * parameter* — which is the library bending the wrong way round.
 *
 * A consumer on UUID keys passes `uuid.toString()`.
 *
 * This is the unit of AI usage accounting: one [AiClient.chat] call ⇒ one usage record.
 */
data class AiCallContext(
    val userId: String,
    val conversationType: String,
    /** The session this call belongs to, when the caller has it directly. */
    val sessionId: String? = null,
    /** The stored conversation this call belongs to (multi-turn flows only). */
    val conversationId: String? = null,
)
