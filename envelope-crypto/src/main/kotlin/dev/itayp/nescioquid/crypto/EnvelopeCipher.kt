package dev.itayp.nescioquid.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic per-entity envelope encryption over [AesGcmCipher].
 *
 * Each entity (a user, a board, …) has a random 32-byte DEK wrapped under the app-wide KEK
 * and persisted via a [DekStore]. On first access in this process the DEK is unwrapped and
 * cached in memory; later calls hit the cache. Values are sealed with the entity's UUID
 * bytes bound as AAD, so a ciphertext from one entity cannot be opened in another's context.
 *
 * This class deliberately carries no persistence/framework dependency — wrapped-DEK storage
 * lives entirely behind [DekStore]. The KEK is supplied through [kekProvider] and resolved
 * lazily on first cryptographic use, so a missing/malformed key surfaces when encryption is
 * first attempted (and only after the cheap [DekStore.exists] check), not at construction time.
 */
class EnvelopeCipher(
    kekProvider: () -> ByteArray,
    private val dekStore: DekStore,
    /** Fixed AAD for KEK-level (non-entity) envelopes; must stay distinct from any 16-byte id AAD. */
    private val systemAad: ByteArray,
) {
    private val kek: ByteArray by lazy(kekProvider)
    private val dekCache = ConcurrentHashMap<UUID, ByteArray>()
    private val random = SecureRandom()

    /** Creates and stores a wrapped DEK for [id] if none exists yet. Idempotent. */
    fun ensureKey(id: UUID) {
        if (dekStore.exists(id)) return
        val dek = ByteArray(32).also(random::nextBytes)
        dekStore.saveWrappedDek(id, AesGcmCipher.seal(kek, dek, idAad(id)))
        dekCache[id] = dek
    }

    fun encrypt(id: UUID, plaintext: String?): ByteArray? {
        if (plaintext == null) return null
        return AesGcmCipher.seal(dekFor(id), plaintext.toByteArray(Charsets.UTF_8), idAad(id))
    }

    fun decrypt(id: UUID, ciphertext: ByteArray?): String? {
        if (ciphertext == null) return null
        return String(AesGcmCipher.open(dekFor(id), ciphertext, idAad(id)), Charsets.UTF_8)
    }

    /**
     * Encrypts directly under the KEK, for data not owned by any entity yet — e.g. a pending
     * magic-link email before an account exists. Bound to [systemAad] so these envelopes can't
     * be swapped in for per-entity ciphertext.
     */
    fun encryptSystem(plaintext: String?): ByteArray? {
        if (plaintext == null) return null
        return AesGcmCipher.seal(kek, plaintext.toByteArray(Charsets.UTF_8), systemAad)
    }

    fun decryptSystem(ciphertext: ByteArray?): String? {
        if (ciphertext == null) return null
        return String(AesGcmCipher.open(kek, ciphertext, systemAad), Charsets.UTF_8)
    }

    private fun dekFor(id: UUID): ByteArray =
        dekCache.computeIfAbsent(id) {
            val wrapped = dekStore.findWrappedDek(it)
                ?: throw IllegalStateException("No data key for $it; ensureKey must be called before use")
            AesGcmCipher.open(kek, wrapped, idAad(it))
        }

    private fun idAad(id: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()
}
