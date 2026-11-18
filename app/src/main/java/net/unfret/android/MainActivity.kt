package net.unfret.android

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Home: the intro (once), a field to paste a link, and the account: remaining checks, the key, and the website
 * for buying checks or redeeming a code (nothing is sold in the app).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private var key: String? = null

    /** The stored pass ended (the server no longer knows it): said once, on this screen, until a pass is set. */
    private var passEnded = false

    /** Bumped whenever the key or server changes, so a slow answer about the old one is dropped. */
    private var generation = 0

    private lateinit var intro: View
    private lateinit var main: View
    private lateinit var link: TextInputEditText
    private lateinit var linkLayout: TextInputLayout
    private lateinit var pending: TextView
    private lateinit var credits: TextView
    private lateinit var keyStatus: TextView
    private lateinit var setKey: MaterialButton
    private lateinit var removeKey: MaterialButton
    private lateinit var endedNotice: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars(findViewById(R.id.root))
        settings = AppSettings(this)

        intro = findViewById(R.id.intro)
        main = findViewById(R.id.main)
        link = findViewById(R.id.link)
        linkLayout = findViewById(R.id.link_layout)
        pending = findViewById(R.id.pending)
        credits = findViewById(R.id.credits)
        keyStatus = findViewById(R.id.key_status)
        setKey = findViewById(R.id.set_key)
        removeKey = findViewById(R.id.remove_key)
        endedNotice = findViewById(R.id.pass_ended)
        passEnded = savedInstanceState?.getBoolean(STATE_PASS_ENDED) == true
        for (id in intArrayOf(R.id.intro_title, R.id.check_title, R.id.account_title)) {
            ViewCompat.setAccessibilityHeading(findViewById(id), true)
        }

        findViewById<MaterialButton>(R.id.agree).setOnClickListener {
            settings.consented = true
            render()
        }
        findViewById<MaterialButton>(R.id.intro_privacy).setOnClickListener { openInBrowser("${settings.apiBase}/privacy") }
        findViewById<MaterialButton>(R.id.about).setOnClickListener { showAbout() }
        findViewById<MaterialButton>(R.id.check).setOnClickListener { checkLink() }
        link.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                checkLink()
                true
            } else {
                false
            }
        }
        findViewById<MaterialButton>(R.id.get_checks).setOnClickListener {
            openInBrowser(checkoutUrl(settings.apiBase, key != null))
        }
        findViewById<MaterialButton>(R.id.have_code).setOnClickListener { openInBrowser(codeUrl(settings.apiBase)) }
        findViewById<MaterialButton>(R.id.new_pass).setOnClickListener { openInBrowser(freePassUrl(settings.apiBase)) }
        setKey.setOnClickListener { askForKey() }
        removeKey.setOnClickListener { confirmRemoveKey() }

        val devServer = findViewById<MaterialSwitch>(R.id.dev_server)
        if (BuildConfig.DEBUG) {
            devServer.visibility = View.VISIBLE
            devServer.isChecked = settings.apiBase == UnfretApi.DEV
            devServer.setOnCheckedChangeListener { _, checked ->
                settings.apiBase = if (checked) UnfretApi.DEV else UnfretApi.PRODUCTION
                refreshCredits()
            }
        }

        takeLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from the website, checks may have arrived.
        refreshCredits()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PASS_ENDED, passEnded)
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    /** A link handed over from the share sheet before the app was set up. */
    private fun takeLink(intent: Intent?) {
        val shared = intent?.getStringExtra(EXTRA_PENDING_LINK) ?: return
        link.setText(shared)
        pending.visibility = View.VISIBLE
    }

    private fun render() {
        val agreed = settings.consented
        intro.visibility = if (agreed) View.GONE else View.VISIBLE
        main.visibility = if (agreed) View.VISIBLE else View.GONE
        key = settings.vault.load()
        val current = key
        if (settings.vault.takeEndedNotice()) passEnded = true
        if (current != null) passEnded = false
        endedNotice.visibility = if (passEnded) View.VISIBLE else View.GONE
        setKey.setText(if (current == null) R.string.paste_key else R.string.change_key)
        removeKey.visibility = if (current == null) View.GONE else View.VISIBLE
        keyStatus.text = if (current == null) getString(R.string.no_key) else getString(R.string.key_in_use, ApiKeys.shownId(current))
        if (current == null) credits.text = ""
    }

    private fun refreshCredits() {
        render()
        val current = key ?: return
        val base = settings.apiBase
        val asked = ++generation
        credits.setText(R.string.credits_loading)
        background.execute {
            var ended = false
            val text = try {
                val c = UnfretApi(base, current, HttpUrlTransport()).credits()
                resources.getQuantityString(R.plurals.checks_left, c.checksRemaining, c.checksRemaining)
            } catch (e: Exception) {
                val problem = Problem.of(e)
                ended = problem == Problem.BAD_KEY
                getString(problem.message())
            }
            runOnUiThread {
                if (isDestroyed || asked != generation) return@runOnUiThread
                // The stored pass ended (Unfret retired its old passes, or it was replaced): forget it, say so once.
                if (ended && settings.vault.rejected(current)) {
                    generation++
                    render()
                } else {
                    credits.text = text
                }
            }
        }
    }

    private fun checkLink() {
        val url = SharedLink.fromSharedText(link.text?.toString())
        if (url == null) {
            linkLayout.error = getString(R.string.link_invalid)
            return
        }
        if (!SharedLink.isCheckable(url, settings.apiBase)) {
            linkLayout.error = getString(R.string.link_not_checkable)
            return
        }
        linkLayout.error = null
        if (key == null) {
            askForKey()
            return
        }
        pending.visibility = View.GONE
        startActivity(Intent(this, CheckActivity::class.java).putExtra(Intent.EXTRA_TEXT, url))
    }

    private fun askForKey() {
        val view = layoutInflater.inflate(R.layout.dialog_key, null)
        val field = view.findViewById<TextInputEditText>(R.id.key)
        val fieldLayout = view.findViewById<TextInputLayout>(R.id.key_layout)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.key_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val typed = ApiKeys.normalize(field.text?.toString().orEmpty())
                if (typed == null) {
                    fieldLayout.error = getString(R.string.key_invalid)
                    return@setOnClickListener
                }
                fieldLayout.error = null
                fieldLayout.helperText = getString(R.string.checking_key)
                save.isEnabled = false
                val base = settings.apiBase
                // Like the extension: the key is kept only once the server knows it.
                background.execute {
                    val problem = try {
                        UnfretApi(base, typed, HttpUrlTransport()).credits()
                        null
                    } catch (e: Exception) {
                        Problem.of(e)
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        save.isEnabled = true
                        fieldLayout.helperText = null
                        if (problem == null) {
                            settings.vault.save(typed)
                            dialog.dismiss()
                            refreshCredits()
                        } else {
                            fieldLayout.error = getString(if (problem == Problem.BAD_KEY) R.string.key_rejected else problem.message())
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmRemoveKey() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_key_title)
            .setMessage(R.string.remove_key_body)
            .setPositiveButton(R.string.remove) { _, _ ->
                settings.vault.clear()
                generation++
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        var version = getString(R.string.version, BuildConfig.VERSION_NAME)
        if (isBeta(BuildConfig.VERSION_NAME)) version += "\n\n" + getString(R.string.beta_note)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.intro_title)
            .setMessage(getString(R.string.intro_body) + "\n\n" + version)
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.privacy_policy) { _, _ -> openInBrowser("${settings.apiBase}/privacy") }
            .show()
    }

    companion object {
        /** A shared link that arrived before the intro was accepted or a key was set. */
        const val EXTRA_PENDING_LINK = "net.unfret.android.PENDING_LINK"

        private const val STATE_PASS_ENDED = "pass_ended"
    }
}
