package dev.itayp.nescioquid.crypto

import java.util.UUID

/**
 * Persistence seam for [EnvelopeCipher]: stores each entity's wrapped DEK keyed by the
 * entity's UUID. Keeping DEK storage behind this interface is what lets [EnvelopeCipher]
 * stay free of any persistence/framework dependency (and therefore reusable) — the
 * consumer supplies the concrete store (e.g. a JPA-backed implementation).
 *
 * Implementations own persistence-only concerns such as key version and creation time.
 */
interface DekStore {
    /** Whether a wrapped DEK already exists for [id] (cheap existence check, no unwrap). */
    fun exists(id: UUID): Boolean

    /** The wrapped DEK for [id], or null when none has been stored yet. */
    fun findWrappedDek(id: UUID): ByteArray?

    /** Persists [wrappedDek] for [id]. Called once, when the entity's key is first created. */
    fun saveWrappedDek(id: UUID, wrappedDek: ByteArray)
}
