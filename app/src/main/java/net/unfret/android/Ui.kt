package net.unfret.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Open [url] in the user's own browser (privacy policy, objections): never inside the app. The Google Play build opens
 * only what [Storefront.mayOpen] allows.
 */
fun Activity.openInBrowser(url: String) {
    if (!Storefront.current.mayOpen(url)) return
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.no_browser, url), Toast.LENGTH_LONG).show()
    }
}

/** Keep [root]'s content clear of the status bar, navigation bar, cut-outs and keyboard (edge to edge). */
fun padForSystemBars(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
        )
        view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

/** A risk level in words (the server's), for a level without its label and for "Wrong result?". */
fun levelWords(level: Int): Int = when (level) {
    0 -> R.string.level_none
    1 -> R.string.level_manipulation
    2 -> R.string.level_overpaying
    3 -> R.string.level_doesnt_work
    else -> R.string.level_wont_arrive
}

/** The plain-words message for a problem. */
fun Problem.message(): Int = when (this) {
    Problem.SERVICE_OFF -> R.string.problem_service_off
    Problem.PAUSED -> R.string.paused_note
    Problem.TOO_MANY -> R.string.problem_too_many
    Problem.FAILED -> R.string.problem_failed
    Problem.OFFLINE -> R.string.problem_offline
}

/**
 * The words for free deep checks being paused, with this build's link ([Storefront]): the Play build's exact sentence
 * with "unfret.net" linking to the home page; the direct download's, followed by "Support Unfret" linking to
 * `/donate`. [prefix] comes first (what happened instead). Show it in a TextView set up by [linkable].
 */
fun Activity.pauseMessage(base: String, prefix: String? = null): CharSequence {
    val storefront = Storefront.current
    val text = SpannableStringBuilder()
    if (prefix != null) text.append(prefix).append(' ')
    val start = text.length
    text.append(getString(R.string.paused_note))
    if (storefront.linksDonations) {
        text.append(' ')
        link(text, getString(R.string.support_unfret), storefront.donateUrl(base))
    } else {
        val at = text.indexOf(PLAY_LINK_TEXT, start)
        if (at >= 0) text.setSpan(linkSpan(storefront.homeUrl(base)), at, at + PLAY_LINK_TEXT.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return text
}

private const val PLAY_LINK_TEXT = "unfret.net"

private fun Activity.link(text: SpannableStringBuilder, words: String, url: String) {
    val start = text.length
    text.append(words)
    text.setSpan(linkSpan(url), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun Activity.linkSpan(url: String) = object : ClickableSpan() {
    override fun onClick(widget: View) = openInBrowser(url)
}

/** Lets [view]'s links ([pauseMessage]) be tapped. */
fun linkable(view: TextView) {
    view.movementMethod = LinkMovementMethod.getInstance()
}
