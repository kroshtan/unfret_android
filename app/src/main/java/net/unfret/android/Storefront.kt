package net.unfret.android

import java.net.URI

/**
 * Where this build sends people for checks.
 *
 * The direct download (unfret.net/android, the release APK) sends them to unfret.net: "Get checks" and "Have a
 * code?" open its checkout. The Google Play build (the `play` build type, the app bundle uploaded to Play) sells
 * nothing and points nowhere to buy, as Google Play's payments policy asks of an app that doesn't sell through Play
 * Billing: no price, no purchase button or link, and no words about buying (its strings are in `src/play/res`).
 * Everything else stays: the free checks on install, quick checks, gift codes (redeemed in the app), a pass pasted or
 * from a link or QR code, and the balance.
 *
 * Decided when the app is built, not from the installer at run time: Google's review and pre-launch devices needn't
 * install through the Play Store, and they must see what Play users see.
 */
class Storefront(val play: Boolean) {
    /** "Get checks" and the line that checks are bought on unfret.net: the direct download only. */
    val sellsChecks: Boolean get() = !play

    /**
     * "Have a gift code?" redeems it in the app (`POST /v1/invites/redeem`), on the pass in use; the direct download's
     * "Have a code?" opens the website's code page instead.
     */
    val redeemsInApp: Boolean get() = play

    /**
     * A quick result's deep-check suggestion, as shown. The Play build shows it only when the deep check can run now
     * (the pass has checks to use), never as "Get checks", and without a price in its words.
     */
    fun deepHint(hint: DeepCheck?): DeepCheck? = when {
        hint == null || !play -> hint
        !hint.runDeep || mentionsBuying(hint.actionLabel) -> null
        mentionsBuying(hint.cost) -> hint.copy(cost = "")
        else -> hint
    }

    /**
     * A sentence of the server's shown as it is (what a check cost): in the Play build only when it says nothing
     * about buying, otherwise null, and the app's own words stand in.
     */
    fun serverWords(text: String?): String? = if (play && text != null && mentionsBuying(text)) null else text

    /** The home screen's balance line, in the Play build, for a pass with no checks left; null otherwise. */
    fun noChecksLine(credits: Credits): Int? = if (play && credits.checksRemaining == 0) R.string.no_deep_checks_left else null

    /**
     * Whether the app may open [url] in the browser. The Play build opens only Unfret's informational pages
     * ([INFO_PAGES]: the privacy policy, objections, about, terms), never the checkout, `/money` or anything else
     * that sells checks.
     */
    fun mayOpen(url: String): Boolean {
        if (!play) return true
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val unfret = uri.host?.lowercase()?.let { it == "unfret.net" || it.endsWith(".unfret.net") } ?: false
        return !unfret || (uri.path.orEmpty().trimEnd('/') in INFO_PAGES)
    }

    companion object {
        /** This build's. */
        val current = Storefront(BuildConfig.PLAY)

        /** The pages of unfret.net the Play build may open: informational only. */
        val INFO_PAGES = setOf("/privacy", "/objection", "/about", "/terms")

        /**
         * Words that sell: a price, a currency, a pack, buying or the checkout (a number right after `%` is a format
         * argument, `%1$s`, not an amount). The Play build's strings and the server's words it shows have none
         * (StorefrontTest reads every string the Play build has).
         */
        private val SELLING = Regex(
            """[$€£]\s?\d|(?<![%\d])\d+\s?[$€£]|\b(?:usd|eur|euros?|dollars?)\b|\bbuy\b|\bbuying\b|\bbought\b|\bpacks?\b|\bprices?\b|\bpricing\b|\bpurchase|\bget checks\b|/checkout|/money""",
            RegexOption.IGNORE_CASE,
        )

        fun mentionsBuying(text: String): Boolean = SELLING.containsMatchIn(text)
    }
}

/**
 * Why `POST /v1/invites/redeem` refused a gift code, in the app's words (never the server's detail, which may speak
 * of packs), by its error code; null for any other failure ([Problem] says those).
 */
fun giftCodeRefusal(error: Throwable): Int? = if (error !is ApiError) {
    null
} else {
    when (error.code) {
        "INVALID_INVITE" -> R.string.gift_code_invalid
        "UNKNOWN_INVITE" -> R.string.gift_code_unknown
        "INVITE_USED" -> R.string.gift_code_used
        "INVITE_OWN_PACK" -> R.string.gift_code_own
        "INVITE_MONTHLY_LIMIT" -> R.string.gift_code_monthly
        "INVITE_ENDED" -> R.string.gift_code_ended
        "TOO_MANY_REQUESTS" -> R.string.gift_code_too_many
        else -> null
    }
}
