package net.unfret.android

import java.net.URI

/**
 * The page address in what another app shared. Browsers share the bare address; marketplace and social apps
 * often share a sentence with the link in it ("Check out this ad on Marktplaats: https://…"), sometimes only a
 * host and path. The first web address wins.
 */
object SharedLink {
    private val WITH_SCHEME = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""(?<![\w@.:/-])(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,24}/[^\s<>"']*""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}>'\"’”»"

    /**
     * The first web address in [text], or null when there is none. Plain `http://` becomes `https://`: the app
     * opens pages over HTTPS only.
     */
    fun fromSharedText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val found = WITH_SCHEME.find(text)?.value ?: BARE.find(text)?.value?.let { "https://$it" } ?: return null
        val url = trimTrailing(found).replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        return if (isWebAddress(url)) url else null
    }

    /** Sentence punctuation after a link isn't part of it; a closing bracket is, when the link opened one. */
    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in TRAILING) {
            val c = url[end - 1]
            if (c == ')' && url.substring(0, end).count { it == '(' } >= url.substring(0, end).count { it == ')' }) break
            end--
        }
        return url.substring(0, end)
    }

    /** Whether [url] is an http(s) address with a host. */
    fun isWebAddress(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        return (scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()
    }

    /**
     * Whether the page at [url] may be checked: a web page that isn't Unfret itself (the extension's
     * `isAnalysable`, without the browser stores, which open in their own apps on a phone).
     */
    fun isCheckable(url: String, apiBase: String): Boolean {
        if (!isWebAddress(url)) return false
        val page = URI(url)
        val api = URI(apiBase)
        return !(page.host.equals(api.host, ignoreCase = true) && page.scheme.equals(api.scheme, ignoreCase = true))
    }
}
