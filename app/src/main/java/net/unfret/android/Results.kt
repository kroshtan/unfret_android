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

data class Reason(val title: String, val detail: String, val severity: Severity)

data class Positive(val title: String, val detail: String)

data class Verdict(
    val band: Band,
    val label: String,
    val headline: String,
    val reasons: List<Reason>,
    val positives: List<Positive>,
)

/** An analysis result, as the result screen needs it (`POST /v1/analyze[/stream]`, contract v1). */
data class AnalysisResult(
    val score: Int,
    val explanation: String,
    val verdict: Verdict?,
    /** False when no check was used: a page that couldn't be assessed, or a recent result from the cache. */
    val charged: Boolean?,
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

    companion object {
        fun parse(json: JSONObject): AnalysisResult {
            val score = json.optInt("score", -1)
            return AnalysisResult(
                score = score,
                explanation = json.optString("explanation"),
                verdict = json.optJSONObject("verdict")?.let(::parseVerdict),
                charged = if (json.has("charged") && !json.isNull("charged")) json.optBoolean("charged") else null,
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
                        )
                    }
                },
                positives = objects(json.optJSONArray("positives")).mapNotNull { p ->
                    val title = p.optString("title")
                    if (title.isEmpty()) null else Positive(title, p.optString("detail"))
                },
            )
        }

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
