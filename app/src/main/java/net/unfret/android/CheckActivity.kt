package net.unfret.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
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
    private lateinit var resultView: ResultView

    private lateinit var key: String
    private lateinit var base: String
    private var bundle: String = ""

    /** A check is being read or sent: the button does nothing until it's over (each check costs one). */
    private var busy = false
    private var pollingSince = 0L

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
        val savedKey = settings.vault.load()
        if (!settings.consented || savedKey == null) {
            // Set up first (the intro, a key); the link waits on the home screen.
            Toast.makeText(this, R.string.setup_first, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PENDING_LINK, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
            return
        }
        key = savedKey
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
        resultView = ResultView(findViewById(R.id.result_content), layoutInflater)

        toolbar.setNavigationOnClickListener { finish() }
        showHost(url)
        resultView.onBackToPage = { showPage() }
        resultView.onDone = { finish() }
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
        web.loadUrl(url)
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
        status.setText(if (ready) R.string.page_ready else R.string.page_loading)
        action.setText(R.string.check_page)
        action.isEnabled = ready
        action.setOnClickListener { startCheck() }
    }

    private fun startCheck() {
        if (busy) return
        busy = true
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
        action.setOnClickListener { startCheck() }
    }

    private fun send(extraction: JSONObject) {
        status.setText(R.string.sending)
        background.execute {
            var steps = 0
            val outcome = try {
                val body = AnalyzeRequest.fromExtraction(extraction)
                Result.success(
                    UnfretApi(base, key, HttpUrlTransport()).analyze(body) {
                        steps++
                        val done = steps
                        runOnUiThread {
                            if (!isDestroyed) status.text = resources.getQuantityString(R.plurals.steps_done, done, done)
                        }
                    },
                )
            } catch (e: IllegalArgumentException) {
                Result.failure<AnalysisResult>(ReadError(e))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                outcome.fold(::showResult, ::showProblem)
            }
        }
    }

    private class ReadError(cause: Throwable) : Exception(cause)

    private fun showProblem(error: Throwable) {
        if (error is ReadError) {
            readFailed()
            return
        }
        val problem = Problem.of(error)
        status.setText(problem.message())
        action.isEnabled = true
        when (problem) {
            Problem.NO_CHECKS -> {
                action.setText(R.string.get_checks)
                action.setOnClickListener { openInBrowser(checkoutUrl(base, hasKey = true)) }
            }
            Problem.BAD_KEY -> {
                action.setText(R.string.open_home)
                action.setOnClickListener {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }
            }
            else -> {
                action.setText(R.string.try_again)
                action.setOnClickListener { startCheck() }
            }
        }
    }

    private fun showResult(result: AnalysisResult) {
        resultView.show(result)
        web.visibility = View.INVISIBLE
        bar.visibility = View.GONE
        progress.visibility = View.INVISIBLE
        resultPanel.visibility = View.VISIBLE
        resultPanel.scrollTo(0, 0)
        toolbar.setTitle(R.string.result_title)
        resultPanel.requestFocus()
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
