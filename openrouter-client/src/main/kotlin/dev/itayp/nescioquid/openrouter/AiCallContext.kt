package dev.itayp.nescioquid.openrouter

import java.util.UUID

/**
 * Caller-supplied context attached to every [AiClient.chat] call so the [AiCallGate] and
 * [AiCallListener] can attribute the call to a user, a conversation type, and — when the call
 * belongs to a stored multi-turn conversation — the conversation (and, where the caller has it,
 * the session).
 *
 * The library treats [conversationType] as an opaque string; consumers assign their own vocabulary.
 * This is the unit of AI usage accounting: one [chat] call ⇒ one usage record.
 */
data class AiCallContext(
    val userId: UUID,
    val conversationType: String,
    /** The session this call belongs to, when the caller has it directly. */
    val sessionId: UUID? = null,
    /** The stored conversation this call belongs to (multi-turn flows only). */
    val conversationId: UUID? = null,
)
