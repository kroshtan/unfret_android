package net.unfret.android

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
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
 * Home: the intro (once), a field to paste a link, your reports (the optional contributor key, a leaderboard name, the
 * leaderboard), and About. Checks are free and need no pass or account; the direct download also links to what
 * donations pay for ([Storefront]).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var intro: View
    private lateinit var main: View
    private lateinit var link: TextInputEditText
    private lateinit var linkLayout: TextInputLayout
    private lateinit var pending: TextView
    private val storefront = Storefront.current
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var contributorSwitch: MaterialSwitch
    private lateinit var contributorStats: TextView
    private lateinit var contributorHandle: MaterialButton

    /** The profile as the server last said, for the name dialog. */
    private var profile: Contributor? = null

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
        contributorSwitch = findViewById(R.id.contributor_switch)
        contributorStats = findViewById(R.id.contributor_stats)
        contributorHandle = findViewById(R.id.contributor_handle)
        contributorSwitch.isChecked = settings.contributorKey != null
        contributorSwitch.setOnCheckedChangeListener { _, on -> if (on) startContributing() else askStopContributing() }
        contributorHandle.setOnClickListener { askHandle() }
        findViewById<MaterialButton>(R.id.leaderboard).setOnClickListener { showLeaderboard() }
        for (id in intArrayOf(R.id.intro_title, R.id.check_title, R.id.reports_title)) {
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
                loadProfile()
            }
        }

        takeLink(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        loadProfile()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun api() = UnfretApi(settings.apiBase, HttpUrlTransport(), storefront.donateOff)

    // ── Reports: the contributor key, its leaderboard name, the leaderboard ─────────────────────────────────

    /** Make the key on this phone; it goes only with reports and contributor calls, never with a check. */
    private fun startContributing() {
        if (settings.contributorKey == null) settings.contributorKey = ContributorKey.generate()
        loadProfile()
    }

    private fun loadProfile() {
        val key = settings.contributorKey
        contributorHandle.visibility = if (key != null) View.VISIBLE else View.GONE
        contributorStats.visibility = if (key != null) View.VISIBLE else View.GONE
        if (key == null) return
        contributorStats.setText(R.string.contributor_loading)
        background.execute {
            val outcome = runCatching { api().contributor(key) }
            runOnUiThread {
                if (isDestroyed || settings.contributorKey != key) return@runOnUiThread
                outcome.onSuccess { showProfile(it) }.onFailure { contributorStats.setText(Problem.of(it).message()) }
            }
        }
    }

    private fun showProfile(contributor: Contributor) {
        profile = contributor
        val reports = resources.getQuantityString(R.plurals.contributor_reports, contributor.labels, contributor.labels)
        val agreed = resources.getQuantityString(R.plurals.contributor_agreed, contributor.points, contributor.points)
        val place = when {
            contributor.rank != null && contributor.public -> getString(R.string.contributor_rank, contributor.rank, contributor.handle)
            contributor.handle.isNotEmpty() -> getString(R.string.contributor_hidden, contributor.handle)
            else -> getString(R.string.contributor_no_handle)
        }
        contributorStats.text = getString(R.string.contributor_stats, reports, agreed, place)
    }

    /** Turning the key off: the server forgets its profile and the phone deletes the key. */
    private fun askStopContributing() {
        val key = settings.contributorKey ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.contributor_forget_title)
            .setMessage(R.string.contributor_forget_body)
            .setPositiveButton(R.string.contributor_forget) { _, _ ->
                settings.contributorKey = null
                profile = null
                loadProfile()
                background.execute {
                    // Best effort: without the key nobody can use the profile anyway.
                    runCatching { api().forgetContributor(key) }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> contributorSwitch.isChecked = true }
            .setOnCancelListener { contributorSwitch.isChecked = true }
            .show()
    }

    private fun askHandle() {
        val key = settings.contributorKey ?: return
        val view = layoutInflater.inflate(R.layout.dialog_handle, null)
        val field = view.findViewById<TextInputEditText>(R.id.handle)
        val fieldLayout = view.findViewById<TextInputLayout>(R.id.handle_layout)
        val public = view.findViewById<CheckBox>(R.id.handle_public)
        field.setText(profile?.handle.orEmpty())
        public.isChecked = profile?.public ?: true
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.handle_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val handle = field.text?.toString()?.trim().orEmpty()
                save.isEnabled = false
                fieldLayout.error = null
                background.execute {
                    val outcome = runCatching { api().contributor(key, handle, public.isChecked && handle.isNotEmpty()) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        save.isEnabled = true
                        outcome.onSuccess {
                            dialog.dismiss()
                            showProfile(it)
                        }.onFailure { e -> fieldLayout.error = getString(labelRefusal(e) ?: Problem.of(e).message()) }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showLeaderboard() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leaderboard)
            .setMessage(R.string.leaderboard_loading)
            .setPositiveButton(R.string.done, null)
            .show()
        background.execute {
            val outcome = runCatching { api().leaderboard() }
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                outcome.onSuccess { entries ->
                    dialog.setMessage(
                        if (entries.isEmpty()) {
                            getString(R.string.leaderboard_empty)
                        } else {
                            entries.joinToString("\n") { e ->
                                getString(R.string.leaderboard_entry, e.rank, e.handle, resources.getQuantityString(R.plurals.contributor_agreed, e.agreed, e.agreed))
                            } + "\n\n" + getString(R.string.leaderboard_note)
                        },
                    )
                }.onFailure { dialog.setMessage(getString(Problem.of(it).message())) }
            }
        }
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
