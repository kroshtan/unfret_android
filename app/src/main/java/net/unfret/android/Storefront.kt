package net.unfret.android

import java.net.URI

/**
 * What this build may say and link to about money: donations, and (never) buying.
 *
 * Every check is free and nothing is sold. Free deep checks run on donations: the direct download (unfret.net/android,
 * the release APK) links to `unfret.net/donate` when they are paused and has a "Support Unfret" link to
 * `unfret.net/support`. The Google Play build (the `play` build type, the app bundle uploaded to Play) has no donate or
 * support link anywhere (Google Play's payments policy): it sends `X-Unfret-Donate: off`, so the server's words leave
 * donations out too, and while free deep checks are paused it says only "Free checks run on donations. Quick checks
 * still work; deep checks are back next month. More info at unfret.net", linking to the home page. Neither build shows
 * a price or a purchase button.
 *
 * Decided when the app is built, not from the installer at run time: Google's review and pre-launch devices needn't
 * install through the Play Store, and they must see what Play users see.
 */
class Storefront(val play: Boolean) {
    /** `X-Unfret-Donate: off` on every request: the Play build. */
    val donateOff: Boolean get() = play

    /** The donate link (with the pause, and a quick result's "Support Unfret") and "Support Unfret": the direct download. */
    val linksDonations: Boolean get() = !play

    /** Always `/donate`, which redirects to the current payment link (never a payment page directly). */
    fun donateUrl(base: String): String = base.trimEnd('/') + "/donate"

    /** What a donation pays for. */
    fun supportUrl(base: String): String = base.trimEnd('/') + "/support"

    /** The home page: the Play build's "More info at unfret.net". */
    fun homeUrl(base: String): String = base.trimEnd('/') + "/"

    /**
     * A quick result's deep-check suggestion, as shown. The Play build never shows its donate button, nor a line about
     * buying or donating (with `X-Unfret-Donate: off` the server sends neither; this is the app's own guard).
     */
    fun deepHint(hint: DeepCheck?): DeepCheck? = when {
        hint == null || !play -> hint
        else -> hint.copy(
            action = if (hint.action == DeepCheck.Action.DONATE) DeepCheck.Action.NONE else hint.action,
            actionLabel = if (hint.action == DeepCheck.Action.DONATE || mentionsMoney(hint.actionLabel)) "" else hint.actionLabel,
            cost = if (mentionsMoney(hint.cost)) "" else hint.cost,
        )
    }

    /**
     * A sentence of the server's shown as it is (what a check cost): in the Play build only when it says nothing about
     * buying or donating, otherwise null, and the app's own words stand in.
     */
    fun serverWords(text: String?): String? = if (play && text != null && mentionsMoney(text)) null else text

    /**
     * The address of one of Unfret's informational pages ([INFO_PAGES]) on [base]. The Play build opens the site's
     * variant for it ([PLAY_VARIANT]: no menu, and no link to buying, the text itself unchanged); the direct download
     * opens the normal page.
     */
    fun infoPage(base: String, path: String): String = base.trimEnd('/') + path + if (play) "?$PLAY_VARIANT" else ""

    /**
     * Whether the app may open [url] in the browser. The Play build opens only Unfret's informational pages
     * ([INFO_PAGES]: the privacy policy, objections, about, terms) as their Play variant ([infoPage]), and the home page
     * ("More info at unfret.net"); never `/donate`, `/support`, the checkout or anything else on unfret.net.
     */
    fun mayOpen(url: String): Boolean {
        if (!play) return true
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val unfret = uri.host?.lowercase()?.let { it == "unfret.net" || it.endsWith(".unfret.net") } ?: false
        if (!unfret) return true
        val path = uri.path.orEmpty()
        return (path.trimEnd('/') in INFO_PAGES && uri.rawQuery == PLAY_VARIANT) || (path == "/" && uri.rawQuery == null)
    }

    companion object {
        /** This build's. */
        val current = Storefront(BuildConfig.PLAY)

        /** The pages of unfret.net the Play build may open as their Play variant: informational only. */
        val INFO_PAGES = setOf("/privacy", "/objection", "/about", "/terms")

        /** The query that asks unfret.net for an informational page's Play variant (no menu, no link to buying). */
        const val PLAY_VARIANT = "app=play"

        /**
         * Words that sell: a price, a currency, a pack, buying or the checkout (a number right after `%` is a format
         * argument, `%1$s`, not an amount). Neither build's strings have any (StorefrontTest).
         */
        private val SELLING = Regex(
            """[$€£]\s?\d|(?<![%\d])\d+\s?[$€£]|\b(?:usd|eur|euros?|dollars?)\b|\bbuy\b|\bbuying\b|\bbought\b|\bpacks?\b|\bprices?\b|\bpricing\b|\bpurchase|\bget checks\b|/checkout|/money""",
            RegexOption.IGNORE_CASE,
        )

        /** Words about donating: the Play build's strings have none but its pause line (StorefrontTest). */
        private val DONATING = Regex("""\bdonat|\bsupport unfret\b|/donate|/support\b""", RegexOption.IGNORE_CASE)

        fun mentionsBuying(text: String): Boolean = SELLING.containsMatchIn(text)

        fun mentionsDonating(text: String): Boolean = DONATING.containsMatchIn(text)

        fun mentionsMoney(text: String): Boolean = mentionsBuying(text) || mentionsDonating(text)
    }
}
