package net.unfret.android

import android.content.Context
import androidx.core.content.edit
import java.security.KeyStore

/**
 * What the app keeps on the phone: whether the intro was accepted (and which version), the contributor key when the
 * user turned it on, and in debug builds which server to use. Nothing about the pages checked, and no pass: checks are
 * free and tied to no one. None of it is in a backup (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`).
 */
class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        OldPass.forget(
            done = { prefs.getBoolean(OldPass.FORGOTTEN, false) },
            removeEntries = {
                prefs.edit(commit = true) {
                    OldPass.ENTRIES.forEach(::remove)
                    putBoolean(OldPass.FORGOTTEN, true)
                }
            },
            deleteKey = {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(OldPass.KEYSTORE_ALIAS)
            },
            deleteFlagFile = { app.deleteSharedPreferences(OldPass.FLAG_FILE) },
        )
    }

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

    /**
     * The contributor key ([ContributorKey]), only once the user turned it on; sent only with reports and contributor
     * calls, never with a check. Null to turn it off.
     */
    var contributorKey: String?
        get() = prefs.getString(CONTRIBUTOR_KEY, null)?.takeIf(ContributorKey::isValid)
        set(value) {
            require(value == null || ContributorKey.isValid(value))
            prefs.edit { if (value == null) remove(CONTRIBUTOR_KEY) else putString(CONTRIBUTOR_KEY, value) }
        }

    companion object {
        /** `shared_prefs/unfret.xml`: excluded from every backup. */
        const val FILE = "unfret"
        private const val CONSENT = "consent_version"

        /** 3: no pass; checks are free and tied to no one (after 0.1.0). */
        private const val CONSENT_VERSION = 3
        private const val SERVER = "server"
        private const val CONTRIBUTOR_KEY = "contributor_key"
    }
}

/**
 * The pass older versions kept (0.1.0 and its betas): its sealed key and backup code, the Android Keystore key that
 * sealed them, and the backed-up yes that the phone had its free checks. Checks need no pass now, so all of it is
 * deleted, once, the first time this version runs.
 */
object OldPass {
    /** In `shared_prefs/unfret.xml`. */
    val ENTRIES = listOf("api_key_sealed", "recovery_code_sealed", "pass_ended", "first_pass_done")
    const val KEYSTORE_ALIAS = "unfret-api-key"

    /** `shared_prefs/free_checks.xml`. */
    const val FLAG_FILE = "free_checks"
    const val FORGOTTEN = "old_pass_forgotten"

    /** Delete it all unless [done]; a step that fails (no Keystore entry) doesn't stop the others. */
    fun forget(done: () -> Boolean, removeEntries: () -> Unit, deleteKey: () -> Unit, deleteFlagFile: () -> Unit) {
        if (done()) return
        for (step in listOf(deleteKey, deleteFlagFile)) {
            try {
                step()
            } catch (_: Exception) {
                // Nothing to delete, or the Keystore is unavailable: the sealed pass goes with the entries anyway.
            }
        }
        removeEntries()
    }
}
