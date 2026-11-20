package net.unfret.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Home: the intro (once), a field to paste a link, and the account: remaining checks, the key, and the website
 * for buying checks or redeeming a code (nothing is sold in the app). The Google Play build has no "Get checks" and
 * redeems a gift code here ([Storefront]).
 *
 * The first launch, in order ([firstLaunchStep]): the intro; a pass from a link, if one opened the app (asked about
 * first); otherwise the app makes its own pass ([FreePass], with free checks unless this phone had them already) and
 * says how it went on the setup card.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private var key: String? = null

    /** The stored pass ended (the server no longer knows it): said once, on this screen, until a pass is set. */
    private var passEnded = false

    /** The free checks left on the stored pass, as the server last said (for the link question's wording). */
    private var freeLeft = 0

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
    private lateinit var endedNext: TextView
    private lateinit var makePass: MaterialButton
    private lateinit var showRecovery: MaterialButton
    private lateinit var setupCard: View
    private lateinit var setupTitle: TextView
    private lateinit var setupProgress: LinearProgressIndicator
    private lateinit var setupBody: TextView
    private lateinit var setupNote: TextView
    private lateinit var setupAction: MaterialButton
    private lateinit var haveCode: MaterialButton
    private val storefront = Storefront.current
    private lateinit var flag: BackedUpFlag
    private val handler = Handler(Looper.getMainLooper())

    /** What the setup card shows: the app's own pass being made, how it went, or nothing (null). */
    private var setup: FreePass.Outcome? = null
    private var making = false
    private val onSetupDone: (FreePass.Outcome) -> Unit = ::setupDone

    /** A pass from a link, waiting for the user's answer (it lives in this activity's intent, never on disk). */
    private var offered: PassLink.Offer? = null
    private var offerDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars(findViewById(R.id.root))
        settings = AppSettings(this)
        flag = BackedUpFlag(this)

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
        endedNext = findViewById(R.id.pass_ended_next)
        makePass = findViewById(R.id.make_pass)
        showRecovery = findViewById(R.id.show_recovery)
        setupCard = findViewById(R.id.setup)
        setupTitle = findViewById(R.id.setup_title)
        setupProgress = findViewById(R.id.setup_progress)
        setupBody = findViewById(R.id.setup_body)
        setupNote = findViewById(R.id.setup_note)
        setupAction = findViewById(R.id.setup_action)
        passEnded = savedInstanceState?.getBoolean(STATE_PASS_ENDED) == true
        setup = savedInstanceState?.let(::restoreSetup)
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
        val getChecks = findViewById<MaterialButton>(R.id.get_checks)
        getChecks.setOnClickListener { openInBrowser(checkoutUrl(settings.apiBase, key != null)) }
        haveCode = findViewById(R.id.have_code)
        if (storefront.sellsChecks) {
            haveCode.setOnClickListener { openInBrowser(codeUrl(settings.apiBase)) }
        } else {
            // The Google Play build sells nothing: no "Get checks", no word of buying on unfret.net; a gift code is
            // redeemed here, on the pass in use.
            getChecks.visibility = View.GONE
            findViewById<View>(R.id.checks_on_website).visibility = View.GONE
            haveCode.setText(R.string.have_gift_code)
            haveCode.setOnClickListener { askForGiftCode() }
        }
        findViewById<MaterialButton>(R.id.new_pass).setOnClickListener { startFreePass() }
        makePass.setOnClickListener { startFreePass() }
        setKey.setOnClickListener { askForKey() }
        removeKey.setOnClickListener { confirmRemoveKey() }
        showRecovery.setOnClickListener { showRecoveryCode() }

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
        takeOffer(intent)
        PassSetup.listen(onSetupDone)
        making = PassSetup.busy
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
        takeOffer(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Back from the website, checks may have arrived.
        refreshCredits()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PASS_ENDED, passEnded)
        when (val shown = setup) {
            is FreePass.Outcome.Free -> outState.putInt(STATE_SETUP_FREE, shown.checks)
            FreePass.Outcome.HadFree, FreePass.Outcome.NoneToday -> outState.putString(STATE_SETUP, shown.toString())
            is FreePass.Outcome.Failed -> outState.putString(STATE_SETUP_FAILED, shown.problem.name)
            else -> {}
        }
    }

    private fun restoreSetup(state: Bundle): FreePass.Outcome? {
        if (state.containsKey(STATE_SETUP_FREE)) return FreePass.Outcome.Free(state.getInt(STATE_SETUP_FREE))
        state.getString(STATE_SETUP_FAILED)?.let { name ->
            return FreePass.Outcome.Failed(Problem.entries.firstOrNull { it.name == name } ?: Problem.FAILED)
        }
        return when (state.getString(STATE_SETUP)) {
            FreePass.Outcome.HadFree.toString() -> FreePass.Outcome.HadFree
            FreePass.Outcome.NoneToday.toString() -> FreePass.Outcome.NoneToday
            else -> null
        }
    }

    override fun onDestroy() {
        PassSetup.stopListening(onSetupDone)
        handler.removeCallbacksAndMessages(null)
        offerDialog?.dismiss()
        offerDialog = null
        background.shutdownNow()
        super.onDestroy()
    }

    /** A link handed over from the share sheet before the app was set up. */
    private fun takeLink(intent: Intent?) {
        val shared = intent?.getStringExtra(EXTRA_PENDING_LINK) ?: return
        link.setText(shared)
        pending.visibility = View.VISIBLE
    }

    /** A pass from a pass link ([PassLinkActivity]), checked again here: any app can start this activity. */
    private fun takeOffer(intent: Intent?) {
        val pass = intent?.getStringExtra(EXTRA_OFFERED_PASS) ?: return
        val base = intent.getStringExtra(EXTRA_OFFERED_BASE)
        val allowed = base == UnfretApi.PRODUCTION || (BuildConfig.DEBUG && base == UnfretApi.DEV)
        offerDialog?.dismiss()
        offerDialog = null
        offered = if (allowed && ApiKeys.normalize(pass) == pass) PassLink.Offer(pass, base) else null
        if (offered == null) clearOffer()
    }

    private fun clearOffer() {
        offered = null
        intent?.removeExtra(EXTRA_OFFERED_PASS)
        intent?.removeExtra(EXTRA_OFFERED_BASE)
    }

    private fun render() {
        val agreed = settings.consented
        intro.visibility = if (agreed) View.GONE else View.VISIBLE
        main.visibility = if (agreed) View.VISIBLE else View.GONE
        key = settings.vault.load()
        val current = key
        // A pass on the phone (pasted, from a link, or from before this version) settles the first launch.
        if (current != null && !settings.firstPassDone) settings.firstPassDone = true
        if (settings.vault.takeEndedNotice()) passEnded = true
        if (current != null) passEnded = false
        val hadFree = flag.received()
        endedNotice.visibility = if (passEnded) View.VISIBLE else View.GONE
        endedNext.setText(if (hadFree) R.string.pass_ended_next_had_free else R.string.pass_ended_next)
        setKey.setText(if (current == null) R.string.paste_key else R.string.change_key)
        removeKey.visibility = if (current == null) View.GONE else View.VISIBLE
        if (storefront.redeemsInApp) haveCode.visibility = if (current == null) View.GONE else View.VISIBLE
        showRecovery.visibility = if (current != null && settings.vault.recoveryCode() != null) View.VISIBLE else View.GONE
        if (current == null) freeLeft = 0
        keyStatus.text = when {
            current != null -> getString(R.string.key_in_use, ApiKeys.shownId(current))
            making -> ""
            hadFree -> getString(R.string.no_key_had_free)
            else -> getString(R.string.no_key_free)
        }
        makePass.setText(if (hadFree) R.string.make_pass else R.string.make_pass_free)
        makePass.visibility = if (current == null && !passEnded && !making) View.VISIBLE else View.GONE
        if (current == null) credits.text = ""
        renderSetup()
        when (firstLaunchStep(agreed, offered != null, current != null, settings.firstPassDone)) {
            FirstLaunchStep.ASK_OFFERED_PASS -> askOfferedPass()
            FirstLaunchStep.MAKE_FREE_PASS -> if (setup == null && !making) startFreePass()
            FirstLaunchStep.INTRO, FirstLaunchStep.HOME -> {}
        }
    }

    // ── The app's own pass ───────────────────────────────────────────────────────────────────────────────

    private fun startFreePass() {
        if (making || settings.vault.load() != null) return
        making = true
        setup = null
        val base = settings.apiBase
        PassSetup.start(FreePass(settings.vault, flag, ApiKeyMaker(base, HttpUrlTransport())))
        render()
    }

    private fun setupDone(outcome: FreePass.Outcome) {
        if (isDestroyed) return
        making = false
        setup = if (outcome == FreePass.Outcome.HasPass) null else outcome
        if (outcome !is FreePass.Outcome.Failed) settings.firstPassDone = true
        generation++
        refreshCredits()
    }

    private fun renderSetup() {
        handler.removeCallbacksAndMessages(SLOW_TOKEN)
        val shown = setup
        if (!making && shown == null) {
            setupCard.visibility = View.GONE
            return
        }
        setupCard.visibility = View.VISIBLE
        setupProgress.visibility = if (making) View.VISIBLE else View.GONE
        setupNote.visibility = View.VISIBLE
        setupAction.visibility = if (making) View.GONE else View.VISIBLE
        setupAction.setText(R.string.got_it)
        setupAction.setOnClickListener {
            setup = null
            renderSetup()
        }
        when {
            making -> {
                setupTitle.setText(if (flag.received()) R.string.setup_making else R.string.setup_making_free)
                setupBody.setText(R.string.setup_making_body)
                // The proof of work is a second on most phones; if it takes longer, say why it's still going.
                setupNote.visibility = View.GONE
                setupNote.setText(R.string.setup_slow)
                handler.postAtTime({ if (making) setupNote.visibility = View.VISIBLE }, SLOW_TOKEN, android.os.SystemClock.uptimeMillis() + SLOW_MS)
            }
            shown is FreePass.Outcome.Free -> {
                setupTitle.text = resources.getQuantityString(R.plurals.free_checks, shown.checks, shown.checks)
                setupBody.setText(R.string.setup_free_body)
                setupNote.setText(R.string.setup_free_after)
            }
            shown == FreePass.Outcome.HadFree || shown == FreePass.Outcome.NoneToday -> {
                setupTitle.setText(R.string.setup_ready)
                setupBody.setText(if (shown == FreePass.Outcome.HadFree) R.string.setup_had_free else R.string.setup_none_today)
                setupNote.setText(R.string.setup_paste_instead)
            }
            shown is FreePass.Outcome.Failed -> {
                setupTitle.setText(R.string.setup_failed)
                setupBody.setText(
                    when (shown.problem) {
                        Problem.OFFLINE -> R.string.problem_offline
                        Problem.SERVICE_OFF -> R.string.problem_service_off
                        else -> R.string.setup_refused
                    },
                )
                setupNote.setText(R.string.setup_paste_instead)
                setupAction.setText(R.string.try_again)
                setupAction.setOnClickListener { startFreePass() }
            }
        }
    }

    // ── A pass from a link ───────────────────────────────────────────────────────────────────────────────

    /** Ask before using the pass from a link: always (any app can open such a link), after the intro on a first launch. */
    private fun askOfferedPass() {
        val offer = offered ?: return
        if (offerDialog != null) return
        // Its free checks move along only from the app's own pass, and are worth a word only when there are some.
        val question = PassQuestion.of(offer, key, settings.vault.recoveryCode() != null && freeLeft > 0)
        if (question == PassQuestion.Same) {
            Toast.makeText(this, getString(R.string.pass_offer_same, offer.shownId), Toast.LENGTH_LONG).show()
            clearOffer()
            return
        }
        var body = getString(R.string.pass_offer_body, offer.shownId)
        if (question is PassQuestion.Switch) {
            body += "\n\n" + getString(R.string.pass_offer_other, question.currentId)
            if (question.movesFreeChecks) body += " " + getString(R.string.pass_offer_moves_free)
        }
        val switching = question is PassQuestion.Switch
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pass_offer_title)
            .setMessage(body)
            .setPositiveButton(if (switching) R.string.pass_offer_switch else R.string.pass_offer_use, null)
            .setNegativeButton(if (switching) R.string.pass_offer_keep else R.string.pass_offer_not_now) { _, _ -> declineOffer() }
            .setOnCancelListener { declineOffer() }
            .create()
        dialog.setOnShowListener {
            val use = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            use.setOnClickListener {
                use.isEnabled = false
                dialog.setMessage(body + "\n\n" + getString(R.string.checking_key))
                val currentBase = settings.apiBase
                background.execute {
                    val outcome = PassTaker(settings.vault, HttpUrlTransport()).take(offer, currentBase)
                    runOnUiThread {
                        if (isDestroyed || offerDialog !== dialog) return@runOnUiThread
                        use.isEnabled = true
                        when (outcome) {
                            PassTaker.Outcome.Taken -> {
                                if (offer.base != currentBase) settings.apiBase = offer.base // a debug build's dev link
                                settings.firstPassDone = true
                                setup = null
                                offerDialog = null
                                clearOffer()
                                dialog.dismiss()
                                Toast.makeText(this, getString(R.string.pass_taken, offer.shownId), Toast.LENGTH_LONG).show()
                                generation++
                                refreshCredits()
                            }
                            PassTaker.Outcome.Unknown -> {
                                use.isEnabled = false
                                dialog.setMessage(body + "\n\n" + getString(R.string.key_rejected))
                            }
                            is PassTaker.Outcome.Failed -> dialog.setMessage(body + "\n\n" + getString(outcome.problem.message()))
                        }
                    }
                }
            }
        }
        offerDialog = dialog
        dialog.show()
    }

    /** Not now (or keep the current one): nothing changes. On a first launch the free pass is then the user's to ask for. */
    private fun declineOffer() {
        offerDialog = null
        clearOffer()
        settings.firstPassDone = true
        render()
    }

    private fun showRecoveryCode() {
        val code = settings.vault.recoveryCode() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_recovery, null)
        view.findViewById<TextView>(R.id.recovery_code).text = code
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recovery_title)
            .setView(view)
            .setPositiveButton(R.string.copy) { _, _ ->
                val clip = ClipData.newPlainText(getString(R.string.recovery_title), code)
                // Not shown in the clipboard's preview, nor offered to keyboards' clipboard history (Android 13+).
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.done, null)
            .show()
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
                runOnUiThread { if (asked == generation) freeLeft = c.freeLeft }
                val none = storefront.noChecksLine(c)
                // As the extension: "You have 5 free checks" while all the checks left are free ones.
                if (none != null) {
                    getString(none)
                } else if (c.freeLeft > 0 && c.freeLeft == c.checksRemaining) {
                    resources.getQuantityString(R.plurals.free_checks, c.checksRemaining, c.checksRemaining)
                } else {
                    resources.getQuantityString(R.plurals.checks_left, c.checksRemaining, c.checksRemaining)
                }
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
        // Without a pass the check screen offers the free quick check.
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
                            settings.firstPassDone = true
                            setup = null
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

    /** The Google Play build's "Have a gift code?": the code's checks go onto the pass in use ([UnfretApi.redeemGiftCode]). */
    private fun askForGiftCode() {
        val current = key ?: return
        val view = layoutInflater.inflate(R.layout.dialog_gift_code, null)
        val field = view.findViewById<TextInputEditText>(R.id.gift_code)
        val fieldLayout = view.findViewById<TextInputLayout>(R.id.gift_code_layout)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gift_code_title)
            .setView(view)
            .setPositiveButton(R.string.redeem, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val redeem = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            redeem.setOnClickListener {
                val code = field.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) {
                    fieldLayout.error = getString(R.string.gift_code_empty)
                    return@setOnClickListener
                }
                fieldLayout.error = null
                fieldLayout.helperText = getString(R.string.redeeming)
                redeem.isEnabled = false
                val base = settings.apiBase
                background.execute {
                    val outcome = runCatching { UnfretApi(base, current, HttpUrlTransport()).redeemGiftCode(code) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        redeem.isEnabled = true
                        fieldLayout.helperText = null
                        outcome.onSuccess { added ->
                            dialog.dismiss()
                            Toast.makeText(this, resources.getQuantityString(R.plurals.gift_code_added, added, added), Toast.LENGTH_LONG).show()
                            refreshCredits()
                        }.onFailure { e ->
                            // Why the code was refused (used, unknown, one this month already), or what went wrong.
                            fieldLayout.error = getString(giftCodeRefusal(e) ?: Problem.of(e).message())
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

        /** A pass from a pass link, and the server it belongs to ([PassLinkActivity]). */
        const val EXTRA_OFFERED_PASS = "net.unfret.android.OFFERED_PASS"
        const val EXTRA_OFFERED_BASE = "net.unfret.android.OFFERED_BASE"

        private const val STATE_PASS_ENDED = "pass_ended"
        private const val STATE_SETUP = "setup"
        private const val STATE_SETUP_FREE = "setup_free"
        private const val STATE_SETUP_FAILED = "setup_failed"
        private const val SLOW_MS = 4_000L
        private val SLOW_TOKEN = Any()
    }
}
