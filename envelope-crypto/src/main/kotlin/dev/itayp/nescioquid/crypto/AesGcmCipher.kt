package dev.itayp.nescioquid.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 1-byte version prefix and a 12-byte random nonce.
 *
 * Envelope layout: `[version:1][nonce:12][ciphertext+tag:N]`. The current version
 * is [VERSION] (0x01). The version byte exists so a future key rotation can produce
 * envelopes that older code refuses to decrypt rather than silently mis-handling.
 *
 * Callers must supply the AAD (typically the owning entity's UUID bytes) to bind the
 * ciphertext to a specific row — moving an encrypted blob from one entity's row to
 * another's will fail authentication, not silently decrypt under a different key.
 *
 * Nonce-reuse bound: nonces are random 96-bit values, so this is safe for up to on the
 * order of 2^32 encryptions under a *single* key (the GCM birthday bound; a nonce
 * collision under GCM is catastrophic — it leaks plaintext XOR and enables forgery).
 * The [EnvelopeCipher] design keeps each key well below that: keys are per-entity DEKs
 * that encrypt only that tenant's field values, and the KEK is used only to wrap DEKs
 * and the occasional system blob. A caller that puts a single key on a high-volume hot
 * path must rotate keys (or switch to a nonce-reuse-resistant mode) before approaching
 * that bound.
 */
object AesGcmCipher {
    const val VERSION: Byte = 0x01
    const val NONCE_SIZE = 12
    const val TAG_SIZE_BITS = 128
    private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(aad)
        }
        val ct = cipher.doFinal(plaintext)
        return ByteArray(1 + NONCE_SIZE + ct.size).apply {
            this[0] = VERSION
            System.arraycopy(nonce, 0, this, 1, NONCE_SIZE)
            System.arraycopy(ct, 0, this, 1 + NONCE_SIZE, ct.size)
        }
    }

    fun open(key: ByteArray, envelope: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        // Smallest valid envelope is version + nonce + a bare tag (empty plaintext still
        // produces a 16-byte GCM tag); anything shorter can't authenticate.
        require(envelope.size >= 1 + NONCE_SIZE + TAG_SIZE_BYTES) { "envelope too short" }
        require(envelope[0] == VERSION) {
            "unsupported ciphertext version: 0x%02x".format(envelope[0].toInt() and 0xFF)
        }
        val nonce = envelope.copyOfRange(1, 1 + NONCE_SIZE)
        val ct = envelope.copyOfRange(1 + NONCE_SIZE, envelope.size)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(aad)
        }
        return cipher.doFinal(ct)
    }
}
