package net.unfret.android

import org.json.JSONArray
import org.json.JSONObject

/** The verdict bands, with the symbol each is shown with so it never relies on colour alone (the extension's). */
enum class Band(val wire: String, val symbol: String) {
    RELIABLE("reliable", "✓"),
    CONCERNS("concerns", "!"),
    CAREFUL("careful", "⚠"),
    JUNK_OR_SCAM("junk_or_scam", "✕"),
    ;

    companion object {
        fun fromWire(value: String): Band? = entries.firstOrNull { it.wire == value }

        /** The server's thresholds, for a result without a verdict (an older server). */
        fun fromScore(score: Int): Band? = when {
            score < 0 -> null
            score >= 80 -> RELIABLE
            score >= 50 -> CONCERNS
            score >= 25 -> CAREFUL
            else -> JUNK_OR_SCAM
        }
    }
}

enum class Severity { HIGH, MEDIUM, LOW }

data class Reason(
    val title: String,
    val detail: String,
    val severity: Severity,
    val type: String = "",
    /** Where the evidence comes from, in words ("Domain registry (RDAP)"); empty from an older server. */
    val source: String = "",
)

data class Positive(val title: String, val detail: String, val source: String = "")

/** A line of the result card's "What we checked". */
enum class CheckStatus { CHECKED, CONCERN, NOT_CHECKED }

data class CheckItem(val label: String, val status: CheckStatus, val note: String)

/** The same product photo on another shop (Unfret's image index): shown first, with both photos. */
data class SameProduct(
    val title: String,
    val detail: String,
    val where: String,
    /** Thumbnail URLs on the API's own image proxy. */
    val image: String,
    val ownImage: String?,
)

data class Verdict(
    val band: Band,
    val label: String,
    val headline: String,
    val reasons: List<Reason>,
    val positives: List<Positive>,
    // The result card (all absent from an older server's or a cached result).
    val checklist: List<CheckItem> = emptyList(),
    val checksRun: Int? = null,
    val nextStep: String? = null,
    val sameProduct: SameProduct? = null,
)

/**
 * A photo URL the app may load: only the API's own thumbnail proxy (`<base>/v1/images/…`), so the phone never
 * contacts the other shops. Null otherwise.
 */
fun apiImage(url: String?, base: String): String? {
    if (url.isNullOrEmpty()) return null
    val prefix = "${base.trimEnd('/')}/v1/images/"
    if (!url.startsWith(prefix)) return null
    val rest = url.removePrefix(prefix)
    return if (rest.isNotEmpty() && rest.none { it == '?' || it == '#' || it == '@' || it == '\\' } && ".." !in rest) url else null
}

/** An analysis result, as the result screen needs it (`POST /v1/analyze[/stream]`, contract v1). */
data class AnalysisResult(
    val score: Int,
    val explanation: String,
    val verdict: Verdict?,
    /** False when no check was used: a page that couldn't be assessed, or a recent result from the cache. */
    val charged: Boolean?,
    /** Lets this pass report the result as wrong (`POST /v1/reports`) within 7 days; absent for quick checks. */
    val reportToken: String? = null,
    /** The verdict exactly as the server sent it, for a report. */
    val verdictJson: JSONObject? = null,
) {
    /** Whether the page could be assessed at all (score -1 means it couldn't). */
    val assessed: Boolean get() = score >= 0

    val band: Band? get() = if (!assessed) null else verdict?.band ?: Band.fromScore(score)

    /** The reasons and good signs in the order to show them: for a reliable page, the good signs first and at most one reason. */
    val reasonsToShow: List<Reason>
        get() = verdict?.let { if (it.band == Band.RELIABLE) it.reasons.take(1) else it.reasons }.orEmpty()

    val positivesFirst: Boolean get() = verdict?.band == Band.RELIABLE

    /**
     * Whether to show the catalogue's "Several small signals" as the reason: a verdict below "Looks fine" always names
     * one. The server always sends one; a result without any (an older server's, without a verdict) doesn't.
     */
    val needsOverallReason: Boolean get() = band.let { it != null && it != Band.RELIABLE } && reasonsToShow.isEmpty()

    /** On a clean result (looks fine, nothing to note), how many checks found nothing worrying; null otherwise. */
    val cleanChecks: Int?
        get() = verdict?.takeIf { it.band == Band.RELIABLE && it.reasons.isEmpty() }?.checksRun?.takeIf { it > 0 }

    companion object {
        fun parse(json: JSONObject): AnalysisResult {
            val score = json.optInt("score", -1)
            return AnalysisResult(
                score = score,
                explanation = json.optString("explanation"),
                verdict = json.optJSONObject("verdict")?.let(::parseVerdict),
                charged = if (json.has("charged") && !json.isNull("charged")) json.optBoolean("charged") else null,
                reportToken = json.optString("report_token").takeIf { it.isNotEmpty() && !json.isNull("report_token") },
                verdictJson = json.optJSONObject("verdict"),
            )
        }

        private fun parseVerdict(json: JSONObject): Verdict? {
            val band = Band.fromWire(json.optString("band")) ?: return null
            return Verdict(
                band = band,
                label = json.optString("label"),
                headline = json.optString("headline"),
                reasons = objects(json.optJSONArray("reasons")).mapNotNull { r ->
                    val title = r.optString("title")
                    if (title.isEmpty()) {
                        null
                    } else {
                        Reason(
                            title,
                            r.optString("detail"),
                            when (r.optString("severity")) {
                                "high" -> Severity.HIGH
                                "low" -> Severity.LOW
                                else -> Severity.MEDIUM
                            },
                            type = r.optString("type"),
                            source = text(r, "source"),
                        )
                    }
                },
                positives = objects(json.optJSONArray("positives")).mapNotNull { p ->
                    val title = p.optString("title")
                    if (title.isEmpty()) null else Positive(title, p.optString("detail"), text(p, "source"))
                },
                checklist = objects(json.optJSONArray("checklist")).mapNotNull { c ->
                    val label = c.optString("label")
                    if (label.isEmpty()) {
                        null
                    } else {
                        CheckItem(
                            label,
                            when (c.optString("status")) {
                                "concern" -> CheckStatus.CONCERN
                                "not_checked" -> CheckStatus.NOT_CHECKED
                                else -> CheckStatus.CHECKED
                            },
                            text(c, "note"),
                        )
                    }
                },
                checksRun = if (json.has("checks_run") && !json.isNull("checks_run")) json.optInt("checks_run") else null,
                nextStep = json.optJSONObject("next_step")?.let { text(it, "text") }?.takeIf { it.isNotEmpty() },
                sameProduct = json.optJSONObject("same_product")?.let { sp ->
                    val image = text(sp, "image")
                    if (image.isEmpty()) {
                        null
                    } else {
                        SameProduct(
                            text(sp, "title"),
                            text(sp, "detail"),
                            text(sp, "where"),
                            image,
                            text(sp, "own_image").takeIf { it.isNotEmpty() },
                        )
                    }
                },
            )
        }

        /** A string field, empty when absent or null (org.json's optString would say "null"). */
        private fun text(json: JSONObject, name: String): String = if (json.isNull(name)) "" else json.optString(name)

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}

/** `GET /v1/credits`. */
data class Credits(val checksRemaining: Int, val inviteEligible: Boolean?) {
    companion object {
        fun parse(json: JSONObject) = Credits(
            checksRemaining = json.optInt("checks_remaining", 0).coerceAtLeast(0),
            inviteEligible = if (json.has("invite_eligible")) json.optBoolean("invite_eligible") else null,
        )
    }
}
