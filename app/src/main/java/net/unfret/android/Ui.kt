package net.unfret.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Open [url] in the user's own browser (checkout, privacy policy): never inside the app. */
fun Activity.openInBrowser(url: String) {
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

/** The plain-words message for a problem. */
fun Problem.message(): Int = when (this) {
    Problem.SERVICE_OFF -> R.string.problem_service_off
    Problem.BAD_KEY -> R.string.problem_bad_key
    Problem.NO_CHECKS -> R.string.problem_no_checks
    Problem.FAILED -> R.string.problem_failed
    Problem.OFFLINE -> R.string.problem_offline
}

/** Where "Get checks" goes: a new key with checks, or more checks onto the key already in use. */
fun checkoutUrl(base: String, hasKey: Boolean): String = if (hasKey) "$base/checkout?mode=topup" else "$base/checkout"

fun codeUrl(base: String): String = "$base/checkout?mode=code"

/** Where a new pass with free deep checks comes from: the link checker, which gives a browser its free checks once. */
fun freePassUrl(base: String): String = "$base/check"
