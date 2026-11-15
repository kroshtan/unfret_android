package net.unfret.android

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a key generated inside the Android Keystore (hardware-backed where the phone has it). */
class KeystoreBox(private val alias: String = "unfret-api-key") : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val existing = keyStore().getKey(alias, null) as? SecretKey ?: error("no key")
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

class PrefsStore(private val prefs: SharedPreferences) : StringStore {
    override fun get(name: String): String? = prefs.getString(name, null)

    override fun put(name: String, value: String) {
        prefs.edit { putString(name, value) }
    }

    override fun remove(name: String) {
        prefs.edit { remove(name) }
    }
}

/**
 * What the app keeps on the phone: the sealed API key, whether the intro was accepted (and which version), and in
 * debug builds which server to use. Nothing about the pages checked.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("unfret", Context.MODE_PRIVATE)

    val vault = KeyVault(PrefsStore(prefs), KeystoreBox())

    var consented: Boolean
        get() = prefs.getInt(CONSENT, 0) >= CONSENT_VERSION
        set(value) {
            prefs.edit { putInt(CONSENT, if (value) CONSENT_VERSION else 0) }
        }

    /** The API to talk to: always unfret.net in release builds; debug builds may pick the dev deployment. */
    var apiBase: String
        get() = if (BuildConfig.DEBUG) prefs.getString(SERVER, null)?.takeIf { it in UnfretApi.BASES } ?: UnfretApi.PRODUCTION else UnfretApi.PRODUCTION
        set(value) {
            require(value in UnfretApi.BASES)
            prefs.edit { putString(SERVER, value) }
        }

    private companion object {
        const val CONSENT = "consent_version"
        const val CONSENT_VERSION = 1
        const val SERVER = "server"
    }
}
