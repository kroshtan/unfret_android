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

/**
 * Home: the intro (once), a field to paste a link, and About. Checks are free and need no pass or account; the direct
 * download also links to what donations pay for ([Storefront]).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var intro: View
    private lateinit var main: View
    private lateinit var link: TextInputEditText
    private lateinit var linkLayout: TextInputLayout
    private lateinit var pending: TextView
    private val storefront = Storefront.current

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
        for (id in intArrayOf(R.id.intro_title, R.id.check_title)) {
            ViewCompat.setAccessibilityHeading(findViewById(id), true)
        }

        findViewById<MaterialButton>(R.id.agree).setOnClickListener {
            settings.consented = true
            render()
        }
        findViewById<MaterialButton>(R.id.intro_privacy).setOnClickListener { openInBrowser(storefront.infoPage(settings.apiBase, "/privacy")) }
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
        // The Google Play build has no donate or support link anywhere.
        val support = findViewById<MaterialButton>(R.id.support)
        if (storefront.linksDonations) {
            support.visibility = View.VISIBLE
            support.setOnClickListener { openInBrowser(storefront.supportUrl(settings.apiBase)) }
        }

        val devServer = findViewById<MaterialSwitch>(R.id.dev_server)
        if (BuildConfig.DEBUG) {
            devServer.visibility = View.VISIBLE
            devServer.isChecked = settings.apiBase == UnfretApi.DEV
            devServer.setOnCheckedChangeListener { _, checked ->
                settings.apiBase = if (checked) UnfretApi.DEV else UnfretApi.PRODUCTION
            }
        }

        takeLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
        render()
    }

    /** A link handed over from the share sheet before the intro was accepted. */
    private fun takeLink(intent: Intent?) {
        val shared = intent?.getStringExtra(EXTRA_PENDING_LINK) ?: return
        link.setText(shared)
        pending.visibility = View.VISIBLE
    }

    private fun render() {
        val agreed = settings.consented
        intro.visibility = if (agreed) View.GONE else View.VISIBLE
        main.visibility = if (agreed) View.VISIBLE else View.GONE
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
        pending.visibility = View.GONE
        startActivity(Intent(this, CheckActivity::class.java).putExtra(Intent.EXTRA_TEXT, url))
    }

    private fun showAbout() {
        var version = getString(R.string.version, BuildConfig.VERSION_NAME)
        if (isBeta(BuildConfig.VERSION_NAME)) version += "\n\n" + getString(R.string.beta_note)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.intro_title)
            .setMessage(getString(R.string.intro_body) + "\n\n" + version)
            .setPositiveButton(R.string.done, null)
            .setNeutralButton(R.string.privacy_policy) { _, _ -> openInBrowser(storefront.infoPage(settings.apiBase, "/privacy")) }
            .show()
    }

    companion object {
        /** A shared link that arrived before the intro was accepted. */
        const val EXTRA_PENDING_LINK = "net.unfret.android.PENDING_LINK"
    }
}
