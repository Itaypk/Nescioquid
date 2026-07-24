package dev.itayp.nescioquid.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException

/**
 * Exercises the reusable [EnvelopeCipher] core directly against a fake in-memory [DekStore],
 * i.e. with no persistence/framework in play — which is the property that makes it reusable.
 */
class EnvelopeCipherTest {

    /** Minimal in-memory DekStore standing in for a persistence-backed implementation. */
    private class FakeDekStore : DekStore {
        val wrapped = ConcurrentHashMap<UUID, ByteArray>()
        override fun exists(id: UUID) = wrapped.containsKey(id)
        override fun findWrappedDek(id: UUID): ByteArray? = wrapped[id]
        override fun saveWrappedDek(id: UUID, wrappedDek: ByteArray) {
            wrapped[id] = wrappedDek
        }
    }

    private val kek = ByteArray(32) { 0x24 }
    private val systemAad = "system".toByteArray()

    private fun newCipher(store: DekStore = FakeDekStore()) =
        EnvelopeCipher(kekProvider = { kek }, dekStore = store, systemAad = systemAad)

    @Test
    fun `ensureKey then encrypt-decrypt round-trips`() {
        val cipher = newCipher()
        val id = UUID.randomUUID()
        cipher.ensureKey(id)

        val envelope = cipher.encrypt(id, "hello")
        assertEquals("hello", cipher.decrypt(id, envelope))
    }

    @Test
    fun `ensureKey persists a wrapped DEK and is idempotent`() {
        val store = FakeDekStore()
        val cipher = newCipher(store)
        val id = UUID.randomUUID()

        assertFalse(store.exists(id))
        cipher.ensureKey(id)
        assertTrue(store.exists(id))
        val firstWrapped = store.findWrappedDek(id)

        cipher.ensureKey(id) // no-op: must not re-wrap a new DEK
        assertEquals(firstWrapped, store.findWrappedDek(id))
    }

    @Test
    fun `ciphertext is bound to its id (AAD isolation)`() {
        // Two ids share the same store/KEK; a blob sealed under one must not open under the other.
        val cipher = newCipher()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        cipher.ensureKey(a)
        cipher.ensureKey(b)

        val envelopeA = cipher.encrypt(a, "for A")!!
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(b, envelopeA) }
    }

    @Test
    fun `encrypt without a key fails`() {
        val cipher = newCipher()
        assertThrows(IllegalStateException::class.java) { cipher.encrypt(UUID.randomUUID(), "x") }
    }

    @Test
    fun `null plaintext and ciphertext short-circuit to null`() {
        val cipher = newCipher()
        val id = UUID.randomUUID()
        assertNull(cipher.encrypt(id, null))
        assertNull(cipher.decrypt(id, null))
    }

    @Test
    fun `system envelopes round-trip under the KEK without a DEK`() {
        val cipher = newCipher()
        val envelope = cipher.encryptSystem("pending-email@example.com")
        assertEquals("pending-email@example.com", cipher.decryptSystem(envelope))
    }

    @Test
    fun `encrypt produces a fresh envelope each call`() {
        val cipher = newCipher()
        val id = UUID.randomUUID()
        cipher.ensureKey(id)
        val a = cipher.encrypt(id, "same")!!
        val b = cipher.encrypt(id, "same")!!
        assertNotEquals(a.toList(), b.toList())
        assertEquals(cipher.decrypt(id, a), cipher.decrypt(id, b))
    }
}
