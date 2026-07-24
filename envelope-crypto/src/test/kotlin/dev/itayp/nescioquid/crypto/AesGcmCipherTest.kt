package dev.itayp.nescioquid.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class AesGcmCipherTest {
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val aad = "user-id-aad".toByteArray()

    @Test
    fun `seal then open recovers plaintext`() {
        val plaintext = "hello world".toByteArray()
        val envelope = AesGcmCipher.seal(key, plaintext, aad)
        assertArrayEquals(plaintext, AesGcmCipher.open(key, envelope, aad))
    }

    @Test
    fun `seal then open recovers empty plaintext`() {
        val envelope = AesGcmCipher.seal(key, ByteArray(0), aad)
        assertArrayEquals(ByteArray(0), AesGcmCipher.open(key, envelope, aad))
    }

    @Test
    fun `seal then open with empty AAD round-trips`() {
        val plaintext = "no aad".toByteArray()
        val envelope = AesGcmCipher.seal(key, plaintext, ByteArray(0))
        assertArrayEquals(plaintext, AesGcmCipher.open(key, envelope, ByteArray(0)))
    }

    @Test
    fun `tampered ciphertext fails`() {
        val envelope = AesGcmCipher.seal(key, "secret".toByteArray(), aad)
        envelope[envelope.size - 1] = (envelope[envelope.size - 1] + 1).toByte()
        assertThrows(AEADBadTagException::class.java) { AesGcmCipher.open(key, envelope, aad) }
    }

    @Test
    fun `wrong AAD fails`() {
        val envelope = AesGcmCipher.seal(key, "secret".toByteArray(), aad)
        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.open(key, envelope, "different-aad".toByteArray())
        }
    }

    @Test
    fun `wrong key fails`() {
        val envelope = AesGcmCipher.seal(key, "secret".toByteArray(), aad)
        val otherKey = ByteArray(32).also(SecureRandom()::nextBytes)
        assertThrows(AEADBadTagException::class.java) { AesGcmCipher.open(otherKey, envelope, aad) }
    }

    @Test
    fun `unknown version byte rejected`() {
        val envelope = AesGcmCipher.seal(key, "secret".toByteArray(), aad)
        envelope[0] = 0x42
        assertThrows(IllegalArgumentException::class.java) { AesGcmCipher.open(key, envelope, aad) }
    }

    @Test
    fun `envelope shorter than header is rejected`() {
        val tooShort = ByteArray(5).also { it[0] = AesGcmCipher.VERSION }
        assertThrows(IllegalArgumentException::class.java) { AesGcmCipher.open(key, tooShort, aad) }
    }

    @Test
    fun `seal produces fresh nonces each call`() {
        val plaintext = "same".toByteArray()
        val a = AesGcmCipher.seal(key, plaintext, aad)
        val b = AesGcmCipher.seal(key, plaintext, aad)
        // Nonce bytes are positions 1..12. With overwhelming probability they differ.
        val nonceA = a.copyOfRange(1, 1 + AesGcmCipher.NONCE_SIZE)
        val nonceB = b.copyOfRange(1, 1 + AesGcmCipher.NONCE_SIZE)
        assert(!nonceA.contentEquals(nonceB)) { "AES-GCM nonces must not repeat under the same key" }
    }
}
