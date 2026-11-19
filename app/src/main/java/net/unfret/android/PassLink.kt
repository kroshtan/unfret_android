package net.unfret.android

import java.net.URI
import java.net.URLDecoder

/**
 * A pass handed over by a link: the "Use on your phone" QR code on unfret.net (My checks) encodes
 * `https://unfret.net/pass#pass=ufk_…`, the fragment as the website's `/check` reads it (a browser never sends a
 * fragment anywhere, and neither does the app). Any app or page can open such a link, so the app always asks first
 * ([PassQuestion]) and checks the pass with the server before keeping it.
 */
object PassLink {
    const val PATH = "/pass"

    /** The pass in the link and the server it belongs to. Its [toString] never names the key. */
    class Offer(val key: String, val base: String) {
        val shownId: String get() = ApiKeys.shownId(key).orEmpty()

        override fun toString() = "PassLink.Offer($shownId, $base)"

        override fun equals(other: Any?) = other is Offer && other.key == key && other.base == base

        override fun hashCode() = key.hashCode() * 31 + base.hashCode()
    }

    /**
     * The pass in [link], or null when it isn't a pass link: HTTPS, unfret.net (dev.unfret.net too when [allowDev]: a
     * debug build), the path `/pass`, and a `pass` in the fragment that is a whole, well-formed key (no case or space
     * fixed up: a link is copied by a machine, so anything else is a malformed link).
     */
    fun parse(link: String?, allowDev: Boolean): Offer? {
        if (link.isNullOrEmpty()) return null
        val uri = try {
            URI(link)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1) return null
        val base = when (uri.host?.lowercase()) {
            "unfret.net" -> UnfretApi.PRODUCTION
            "dev.unfret.net" -> if (allowDev) UnfretApi.DEV else return null
            else -> return null
        }
        if (uri.rawPath != PATH && uri.rawPath != "$PATH/") return null
        val key = fragmentValue(uri.rawFragment ?: return null, "pass") ?: return null
        if (ApiKeys.normalize(key) != key) return null
        return Offer(key, base)
    }

    /** The first [name] in a fragment read as `URLSearchParams` reads it (`a=1&b=2`, `+` is a space). */
    private fun fragmentValue(fragment: String, name: String): String? {
        for (part in fragment.split('&')) {
            val eq = part.indexOf('=')
            val rawName = if (eq < 0) part else part.substring(0, eq)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val decodedName = decode(rawName) ?: continue
            if (decodedName == name) return decode(rawValue)
        }
        return null
    }

    private fun decode(text: String): String? = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * The question before a pass from a link is used, in the website's words (`/check`'s "Use this pass here?") and, when
 * the phone already uses another pass, the extension's ("Use the new pass" / "Keep the current one"): the pass on the
 * phone may be the only copy of one with checks on it.
 */
sealed interface PassQuestion {
    /** The link holds the pass the phone uses already: nothing to ask. */
    data object Same : PassQuestion

    /** No pass on the phone: use this one, or not now. */
    data class Use(val offeredId: String) : PassQuestion

    /**
     * The phone uses [currentId]: switch, or keep it. [movesFreeChecks] when the current pass is the one the app made
     * for itself and has free checks left: they move to the new pass on a switch (as in the extension), so none are
     * lost.
     */
    data class Switch(val offeredId: String, val currentId: String, val movesFreeChecks: Boolean) : PassQuestion

    companion object {
        fun of(offer: PassLink.Offer, current: String?, movesFreeChecks: Boolean): PassQuestion = when {
            current == offer.key -> Same
            current == null -> Use(offer.shownId)
            else -> Switch(offer.shownId, ApiKeys.shownId(current).orEmpty(), movesFreeChecks)
        }
    }
}

/**
 * Taking a pass from a link once the user said yes: like a pasted pass it is kept only once the server knows it
 * (`GET /v1/credits` with it). When it replaces the app's own pass, that pass's free checks left move to it first
 * (`POST /v1/keys/move-free-checks`), as the extension does.
 */
class PassTaker(private val vault: KeyVault, private val transport: Transport) {
    sealed interface Outcome {
        data object Taken : Outcome

        /** The server doesn't know the pass in the link. */
        data object Unknown : Outcome

        /** Nothing changed: [problem] says why. */
        data class Failed(val problem: Problem) : Outcome
    }

    fun take(offer: PassLink.Offer, currentBase: String): Outcome {
        try {
            UnfretApi(offer.base, offer.key, transport).credits()
        } catch (e: Exception) {
            val problem = Problem.of(e)
            return if (problem == Problem.BAD_KEY) Outcome.Unknown else Outcome.Failed(problem)
        }
        val current = vault.load()
        if (current != null && current != offer.key && vault.recoveryCode() != null && currentBase == offer.base) {
            try {
                UnfretApi(currentBase, current, transport).moveFreeChecks(ApiKeys.idOf(offer.key).orEmpty())
            } catch (e: Exception) {
                // Unreachable: switch later, so the free checks aren't lost. An answer (the old pass ended, nothing to
                // move) doesn't stop the switch.
                if (e !is ApiError) return Outcome.Failed(Problem.of(e))
            }
        }
        vault.save(offer.key)
        return Outcome.Taken
    }
}
