package net.unfret.android

import java.util.Base64

/** Encrypts and decrypts small secrets; on the phone with a key that never leaves the Android Keystore. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** Throws when [sealed] can't be opened (another key, tampered with). */
    fun open(sealed: ByteArray): ByteArray
}

/** A few named strings that survive the app closing (SharedPreferences on the phone). */
interface StringStore {
    fun get(name: String): String?

    fun put(name: String, value: String)

    fun remove(name: String)
}

/**
 * The user's Unfret API key, the only secret the app keeps: encrypted by [box] and stored in [store]. Only the
 * ciphertext is ever written. When it can't be decrypted any more (the Keystore key was lost, for example after a
 * restore onto another phone), it is forgotten and the user pastes the key again.
 */
class KeyVault(private val store: StringStore, private val box: SecretBox) {
    fun save(key: String) {
        val normalized = requireNotNull(ApiKeys.normalize(key)) { "not an Unfret API key" }
        val sealed = box.seal(normalized.toByteArray(Charsets.UTF_8))
        store.put(ENTRY, Base64.getEncoder().encodeToString(sealed))
    }

    fun load(): String? {
        val stored = store.get(ENTRY) ?: return null
        val key = try {
            String(box.open(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
        if (key == null || ApiKeys.normalize(key) != key) {
            store.remove(ENTRY)
            return null
        }
        return key
    }

    fun clear() = store.remove(ENTRY)

    private companion object {
        const val ENTRY = "api_key_sealed"
    }
}
