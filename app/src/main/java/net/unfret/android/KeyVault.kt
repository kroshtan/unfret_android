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
 *
 * When the server stops knowing the stored key (401: Unfret retired its old passes, or the key was replaced with its
 * backup code), [rejected] forgets it and leaves a one-time note, so the home screen can say so once
 * ([takeEndedNotice]). The note is a yes, nothing else; pasting a new pass clears it.
 */
class KeyVault(private val store: StringStore, private val box: SecretBox) {
    /**
     * Keep [key]. [recoveryCode] is the backup code of a pass the app made for itself (the server shows it only once);
     * it is sealed the same way, and forgotten with the pass or when another pass is saved.
     */
    fun save(key: String, recoveryCode: String? = null) {
        val normalized = requireNotNull(ApiKeys.normalize(key)) { "not an Unfret API key" }
        store.put(ENTRY, seal(normalized))
        if (recoveryCode != null) store.put(RECOVERY, seal(recoveryCode)) else store.remove(RECOVERY)
        store.remove(ENDED)
    }

    private fun seal(plain: String) = Base64.getEncoder().encodeToString(box.seal(plain.toByteArray(Charsets.UTF_8)))

    /** The backup code of the stored pass, when the app made that pass itself (and so it is the only copy). */
    fun recoveryCode(): String? {
        if (load() == null) return null
        val stored = store.get(RECOVERY) ?: return null
        return try {
            String(box.open(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        } catch (_: Exception) {
            store.remove(RECOVERY)
            null
        }
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
            store.remove(RECOVERY)
            return null
        }
        return key
    }

    fun clear() {
        store.remove(ENTRY)
        store.remove(RECOVERY)
    }

    /**
     * The server rejected [key] (401). If it is still the stored one, forget it and note that it ended.
     *
     * @return Whether it was the stored key (a pass changed in the meantime is left alone).
     */
    fun rejected(key: String): Boolean {
        if (load() != key) return false
        clear()
        store.put(ENDED, "1")
        return true
    }

    /** Whether a stored pass ended since the home screen last said so; reading it clears the note. */
    fun takeEndedNotice(): Boolean {
        val ended = store.get(ENDED) != null
        if (ended) store.remove(ENDED)
        return ended
    }

    private companion object {
        const val ENTRY = "api_key_sealed"
        const val ENDED = "pass_ended"
        const val RECOVERY = "recovery_code_sealed"
    }
}
