package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyVaultTest {
    private val key = "ufk_abcdefgh23_" + "a".repeat(26) + "234567" + "b".repeat(20)

    /** Stands in for the Keystore: reversible, and visibly not the plain text. */
    private class FakeBox : SecretBox {
        var broken = false

        override fun seal(plain: ByteArray) = byteArrayOf(42) + plain.map { (it.toInt() xor 0x5a).toByte() }

        override fun open(sealed: ByteArray): ByteArray {
            check(!broken && sealed.first() == 42.toByte())
            return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    private class MemoryStore : StringStore {
        val values = mutableMapOf<String, String>()

        override fun get(name: String) = values[name]

        override fun put(name: String, value: String) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }
    }

    @Test
    fun `a pasted key is normalised`() {
        assertEquals(key, ApiKeys.normalize("  ${key.uppercase().substring(0, 20)}\n${key.uppercase().substring(20)} "))
        assertEquals("abcdefgh23", ApiKeys.idOf(key))
    }

    @Test
    fun `things that aren't keys`() {
        assertNull(ApiKeys.normalize(""))
        assertNull(ApiKeys.normalize("ufr_abcdefgh23_" + "a".repeat(52))) // a recovery code
        assertNull(ApiKeys.normalize("ufk_abcdefgh23_" + "a".repeat(51)))
        assertNull(ApiKeys.normalize("ufk_abcdefgh1_" + "a".repeat(52))) // 1 isn't base32
        assertNull(ApiKeys.normalize("Bearer $key"))
    }

    @Test
    fun `only the sealed key is stored, and it comes back`() {
        val store = MemoryStore()
        val vault = KeyVault(store, FakeBox())
        assertNull(vault.load())

        vault.save(key)

        assertEquals(1, store.values.size)
        assertFalse(store.values.values.single().contains("ufk_"))
        assertEquals(key, vault.load())
        assertEquals(key, KeyVault(store, FakeBox()).load())
    }

    @Test
    fun `a key that can't be opened any more is forgotten`() {
        val store = MemoryStore()
        val box = FakeBox()
        KeyVault(store, box).save(key)
        box.broken = true

        assertNull(KeyVault(store, box).load())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `removing the key`() {
        val store = MemoryStore()
        val vault = KeyVault(store, FakeBox())
        vault.save(key)
        vault.clear()
        assertNull(vault.load())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `refuses to store something that isn't a key`() {
        assertThrows(IllegalArgumentException::class.java) { KeyVault(MemoryStore(), FakeBox()).save("hello") }
    }
}
