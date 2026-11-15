package net.unfret.android

import android.content.Context
import java.io.File

/** The WebView's files on disk: cookies, site storage, its cache and its own databases. */
object WebViewData {
    fun delete(context: Context) {
        File(context.applicationInfo.dataDir, "app_webview").deleteRecursively()
        File(context.cacheDir, "WebView").deleteRecursively()
    }
}
