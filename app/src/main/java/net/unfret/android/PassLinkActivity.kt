package net.unfret.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Opened by a pass link (`https://unfret.net/pass#pass=ufk_…`, the QR code on unfret.net): reads the pass from the
 * link's fragment, checks its form, and hands it to the home screen, which asks before using it (after the intro on a
 * first launch). No window of its own and no entry in Recents; the pass is never logged. The home screen gets it as an
 * extra, which Android keeps in memory only (a task's saved copy for Recents keeps the address, not extras, and this
 * activity's task is left out of Recents).
 */
class PassLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val offer = PassLink.parse(intent?.dataString, allowDev = BuildConfig.DEBUG)
        val home = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (offer == null) {
            Toast.makeText(this, R.string.pass_link_invalid, Toast.LENGTH_LONG).show()
        } else {
            home.putExtra(MainActivity.EXTRA_OFFERED_PASS, offer.key).putExtra(MainActivity.EXTRA_OFFERED_BASE, offer.base)
        }
        startActivity(home)
        finish()
    }
}
