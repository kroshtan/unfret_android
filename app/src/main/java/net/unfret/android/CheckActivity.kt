package net.unfret.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Checks one page on the phone itself: the page opens in a WebView, visible, over the phone's own connection (so
 * shops that refuse servers show it, and the user can deal with a cookie banner or a captcha). When the user taps
 * "Check this page", the extension's extractor reads it, and the facts and cleaned text go to the Unfret API.
 *
 * The WebView is a clean slate each time: no cookies, storage or cache are kept from one check to the next.
 */
class CheckActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var web: WebView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var resultPanel: View
    private lateinit var bar: View
    private lateinit var status: TextView
    private lateinit var action: MaterialButton
    private lateinit var quickButton: MaterialButton
    private lateinit var quickHint: TextView
    private lateinit var resultView: ResultView

    private lateinit var base: String
    private var bundle: String = ""

    /** A check is being read or sent: the button does nothing until it's over. */
    private var busy = false
    private var pollingSince = 0L

    /** The check being read is the free quick check. */
    private var quick = false

    /** Free deep checks are paused (the month's spend ceiling), as the server said: quick checks then. */
    private var paused = false

    /** Said above a result that was meant to be a deep check and became a quick one. */
    private var quickNote: CharSequence? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        val url = SharedLink.fromSharedText(intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            Toast.makeText(this, R.string.no_link_shared, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!settings.consented) {
            // The intro first; the link waits on the home screen.
            Toast.makeText(this, R.string.setup_first, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PENDING_LINK, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
            return
        }
        base = settings.apiBase
        if (!SharedLink.isCheckable(url, base)) {
            Toast.makeText(this, R.string.link_not_checkable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        bundle = assets.open(PageReading.BUNDLE_ASSET).bufferedReader().use { it.readText() }

        // Before this process's first WebView exists: whatever an earlier check left on disk goes.
        if (open == 0) WebViewData.delete(this)
        open++
        setContentView(R.layout.activity_check)
        padForSystemBars(findViewById(R.id.root))
        web = findViewById(R.id.web)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        resultPanel = findViewById(R.id.result)
        bar = findViewById(R.id.bar)
        status = findViewById(R.id.status)
        action = findViewById(R.id.check_page)
        quickButton = findViewById(R.id.quick_check)
        quickHint = findViewById(R.id.quick_hint)
        linkable(status)
        quickButton.setOnClickListener { startCheck(quick = true) }
        resultView = ResultView(findViewById(R.id.result_content), layoutInflater)

        toolbar.setNavigationOnClickListener { finish() }
        showHost(url)
        resultView.onBackToPage = { showPage() }
        resultView.onDone = { finish() }
        resultView.base = base
        resultView.loadImage = ::loadThumbnail
        resultView.onObject = { openInBrowser(Storefront.current.infoPage(base, "/objection")) }
        resultView.onDeepHint = { action ->
            when (action) {
                DeepCheck.Action.RUN_DEEP -> {
                    showPage()
                    startCheck()
                }
                // The direct download only: Storefront.deepHint takes this button out of the Play build.
                DeepCheck.Action.DONATE -> openInBrowser(Storefront.current.donateUrl(base))
                DeepCheck.Action.NONE -> {}
            }
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        resultPanel.isVisible -> showPage()
                        web.canGoBack() -> web.goBack()
                        else -> finish()
                    }
                }
            },
        )
        setUpWebView()
        forgetBrowsingData()
        setReady(false)
        loadFreeChecks()
        web.loadUrl(url)
    }

    private fun api() = UnfretApi(base, HttpUrlTransport(), Storefront.current.donateOff)

    /** Whether free deep checks are paused this month, so the quick check is offered first. */
    private fun loadFreeChecks() {
        background.execute {
            val known = try {
                api().freeChecks()
            } catch (_: Exception) {
                null // the check itself says what's wrong
            }
            runOnUiThread {
                if (isDestroyed || known == null || !known.paused) return@runOnUiThread
                paused = true
                if (!busy && !resultPanel.isVisible) setReady(action.isEnabled)
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        background.shutdownNow()
        if (::web.isInitialized) {
            forgetBrowsingData()
            web.stopLoading()
            web.destroy()
            open--
            if (open == 0 && isFinishing) {
                // The WebView keeps more than its API clears (for example the sites it saw, for bounce-tracking
                // protection). Delete its data and end this process, so none of it outlives the check.
                WebViewData.delete(this)
                super.onDestroy()
                Process.killProcess(Process.myPid())
                return
            }
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled") // the page needs its scripts to show the product, and the reader runs as one
    private fun setUpWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // many shops don't render without it; cleared after each check
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                progress.setProgressCompat(newProgress, true)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                toolbar.title = title?.takeIf { it.isNotBlank() } ?: toolbar.title
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Web pages stay in the WebView, over HTTPS; other schemes (intent:, market:, tel:, app links) open
                // nothing.
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" && request.isForMainFrame) {
                    view.loadUrl(request.url.buildUpon().scheme("https").build().toString())
                    return true
                }
                return scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                showHost(url)
                if (!busy) setReady(false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!busy) setReady(true)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page's renderer crashed or was killed for memory: close instead of taking the app down.
                Toast.makeText(this@CheckActivity, R.string.page_crashed, Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
    }

    /** Cookies, site storage, cache, form data and history of the pages seen here: none of it is kept. */
    private fun forgetBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web.clearCache(true)
        web.clearFormData()
        web.clearHistory()
    }

    private fun showHost(url: String) {
        toolbar.subtitle = url.toUri().host.orEmpty()
    }

    private fun setReady(ready: Boolean) {
        val offer = QuickCheck.offer(paused)
        status.text = when {
            !ready -> getString(R.string.page_loading)
            offer.paused -> pauseMessage(base)
            else -> getString(R.string.page_ready)
        }
        val quickMain = offer.main == QuickCheck.Main.QUICK
        action.setText(if (quickMain) R.string.quick_check else R.string.check_page)
        action.contentDescription = if (quickMain) getString(R.string.quick_check_described) else null
        action.isEnabled = ready
        action.setOnClickListener { startCheck(quick = quickMain) }
        showQuickOption(offer.quickOption, ready)
    }

    /** "Quick check (free)" under the main button, before a check, while the main button is the deep check. */
    private fun showQuickOption(shown: Boolean, enabled: Boolean) {
        quickButton.visibility = if (shown) View.VISIBLE else View.GONE
        quickHint.visibility = quickButton.visibility
        quickButton.isEnabled = enabled
    }

    private fun startCheck(quick: Boolean = false) {
        if (busy) return
        busy = true
        this.quick = quick
        quickNote = null
        showQuickOption(false, false)
        action.isEnabled = false
        status.setText(R.string.reading_page)
        web.evaluateJavascript(bundle + PageReading.START, null)
        pollingSince = SystemClock.elapsedRealtime()
        poll()
    }

    private fun poll() {
        web.evaluateJavascript(PageReading.POLL) { raw ->
            when (val state = PageReading.parsePoll(raw)) {
                PageReading.State.Running, PageReading.State.NotStarted -> {
                    if (SystemClock.elapsedRealtime() - pollingSince > READ_TIMEOUT_MS) {
                        readFailed()
                    } else {
                        main.postDelayed(::poll, POLL_INTERVAL_MS)
                    }
                }
                PageReading.State.Gone, is PageReading.State.Failed -> readFailed()
                is PageReading.State.Done -> send(state.extraction)
            }
        }
    }

    private fun readFailed() {
        busy = false
        status.setText(R.string.read_failed)
        action.setText(R.string.try_again)
        action.isEnabled = true
        val again = quick
        action.setOnClickListener { startCheck(again) }
    }

    private fun send(extraction: JSONObject) {
        status.setText(R.string.sending)
        val asQuick = quick || paused
        background.execute {
            var progress = Progress()
            var refused: ApiError? = null
            val outcome = try {
                val api = api()
                val onStep: (Step) -> Unit = { step ->
                    progress.add(step)
                    val checks = progress.checks
                    val steps = progress.steps
                    runOnUiThread {
                        if (!isDestroyed) {
                            status.text = when {
                                checks == null -> resources.getQuantityString(R.plurals.steps_done, steps, steps)
                                checks == 0 -> getString(R.string.sending)
                                else -> resources.getQuantityString(R.plurals.checks_done, checks, checks)
                            }
                        }
                    }
                }
                val result = if (asQuick) {
                    api.analyze(AnalyzeRequest.fromExtraction(extraction, quick = true), onStep)
                } else {
                    try {
                        // The free deep check's proof of work, on this worker thread (ProofOfWork spreads it over the
                        // phone's cores); the screen closing interrupts it.
                        api.deepCheck(
                            AnalyzeRequest.fromExtraction(extraction),
                            solve = { challenge ->
                                runOnUiThread { if (!isDestroyed) status.setText(R.string.solving) }
                                ProofOfWork.solve(challenge.challenge, challenge.bits)
                            },
                            onSolved = { runOnUiThread { if (!isDestroyed) status.setText(R.string.sending) } },
                            onStep = onStep,
                        ).first
                    } catch (e: ApiError) {
                        // As the extension: a deep check that can't run now becomes a quick one, saying why.
                        if (!QuickCheck.fallsBackToQuick(Problem.of(e))) throw e
                        refused = e
                        progress = Progress() // the quick check's own checks, as its result counts them
                        api.analyze(AnalyzeRequest.fromExtraction(extraction, quick = true), onStep)
                    }
                }
                Result.success(result)
            } catch (e: IllegalArgumentException) {
                Result.failure<AnalysisResult>(ReadError(e))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                quickNote = refused?.let(::fallbackNote)
                outcome.fold(::showResult, ::showProblem)
            }
        }
    }

    /** Why a deep check became a quick one, above its result. */
    private fun fallbackNote(error: ApiError): CharSequence = when (Problem.of(error)) {
        Problem.PAUSED -> {
            paused = true
            pauseMessage(base)
        }
        Problem.TOO_MANY -> {
            val minutes = error.retryAfterS?.let { (it + 59) / 60 }?.coerceAtLeast(1)
            if (minutes != null) {
                resources.getQuantityString(R.plurals.quick_because_busy_minutes, minutes, minutes)
            } else {
                getString(R.string.quick_because_busy)
            }
        }
        else -> getString(R.string.quick_because_unavailable)
    }

    private class ReadError(cause: Throwable) : Exception(cause)

    private fun showProblem(error: Throwable) {
        if (error is ReadError) {
            readFailed()
            return
        }
        val problem = Problem.of(error)
        status.text = if (problem == Problem.PAUSED) pauseMessage(base) else getString(problem.message())
        action.isEnabled = true
        action.setText(R.string.try_again)
        val again = quick
        action.setOnClickListener { startCheck(again) }
    }

    private fun showResult(result: AnalysisResult) {
        resultView.show(result, quickNote)
        web.visibility = View.INVISIBLE
        bar.visibility = View.GONE
        progress.visibility = View.INVISIBLE
        resultPanel.visibility = View.VISIBLE
        resultPanel.scrollTo(0, 0)
        toolbar.setTitle(R.string.result_title)
        resultPanel.requestFocus()
    }

    /** A thumbnail from the API's own image proxy, decoded off the main thread. */
    private fun loadThumbnail(url: String, done: (Bitmap?) -> Unit) {
        background.execute {
            val bytes = UnfretApi.thumbnail(url, base, HttpUrlTransport())
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            runOnUiThread { if (!isDestroyed) done(bitmap) }
        }
    }

    private fun showPage() {
        resultPanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        toolbar.title = web.title
        setReady(web.progress >= 100)
    }

    private companion object {
        /** Check screens alive in this process (two shares in a row make two). */
        var open = 0

        const val POLL_INTERVAL_MS = 250L

        /** The reader watches countdowns for about a second; anything past this is a stuck page. */
        const val READ_TIMEOUT_MS = 20_000L
    }
}
