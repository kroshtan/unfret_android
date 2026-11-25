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

data class CheckItem(
    val label: String,
    val status: CheckStatus,
    val note: String,
    /** How many of the verdict's checks looked at this area (they add up to checksRun); null from an older server. */
    val checks: Int? = null,
)

/** The same product photo on another shop (Unfret's image index): shown first, with both photos. */
data class SameProduct(
    val title: String,
    val detail: String,
    val where: String,
    /** Thumbnail URLs on the API's own image proxy. */
    val image: String,
    val ownImage: String?,
)

/**
 * A quick result's suggestion of a deep check of the same page, in the server's words: what it would add here, a line
 * about it ([cost]: "Deep checks are free.", or that they are paused), and at most one button ([action]).
 */
data class DeepCheck(
    val text: String,
    val cost: String,
    val action: Action,
    val actionLabel: String,
    /** Looks fine: shown quietly. */
    val quiet: Boolean,
) {
    /** The button: run the deep check now, support Unfret (while free deep checks are paused), or none. */
    enum class Action { RUN_DEEP, DONATE, NONE }
}

data class Verdict(
    /** Deprecated by the server (the risk level replaces it); null when absent or unknown. */
    val band: Band?,
    val label: String,
    val headline: String,
    val reasons: List<Reason>,
    val positives: List<Positive>,
    // The result card (all absent from an older server's or a cached result).
    val checklist: List<CheckItem> = emptyList(),
    val checksRun: Int? = null,
    val nextStep: String? = null,
    val sameProduct: SameProduct? = null,
    /** Only when the server suggests a deep check (a quick result, a server that has the suggestion). */
    val deepCheck: DeepCheck? = null,
    /** Part of the check didn't finish (the assessment is degraded): the server's line, shown under the headline. */
    val incomplete: String? = null,
) {
    /**
     * For "What we checked: 16 checks in 5 areas": the areas with a check, when every area says how many (their counts
     * add up to [checksRun]); null for an older server's result, which keeps "What we checked · 16 checks".
     */
    val checkedAreas: Int?
        get() = if ((checksRun ?: 0) > 0 && checklist.isNotEmpty() && checklist.all { it.checks != null }) {
            checklist.count { (it.checks ?: 0) > 0 }
        } else {
            null
        }
}

/**
 * The one overall risk level (`verdict.risk`, contract v1): higher is worse. 0 no concerns, 1 pressure and tricks, 2
 * may cost more than it's worth, 3 may not work as described, 4 may not arrive or may not be safe. [because] lists
 * every finding behind the level, worst first; [explanation] is the one-line why ("Level 2 because: …", with a raise
 * or lowering by payment protection).
 */
data class Risk(
    val level: Int,
    val key: String,
    val label: String,
    val because: List<Because>,
    val explanation: String,
) {
    data class Because(val type: String, val level: Int?, val title: String)

    companion object {
        const val MAX = 4

        /** The level each key stands for: "read the key when in doubt" (the numbering was turned round once). */
        val KEYS = listOf("none", "manipulation", "overpaying", "doesnt_work", "wont_arrive")

        fun parse(json: JSONObject?): Risk? {
            if (json == null) return null
            val key = if (json.isNull("key")) "" else json.optString("key")
            val level = KEYS.indexOf(key).takeIf { it >= 0 }
                ?: json.optInt("level", -1).takeIf { it in 0..MAX }
                ?: return null
            val because = (0 until (json.optJSONArray("because")?.length() ?: 0)).mapNotNull { i ->
                val b = json.getJSONArray("because").optJSONObject(i) ?: return@mapNotNull null
                val title = if (b.isNull("title")) "" else b.optString("title")
                if (title.isEmpty()) {
                    null
                } else {
                    Because(b.optString("type"), if (b.has("level") && !b.isNull("level")) b.optInt("level") else null, title)
                }
            }
            return Risk(
                level = level,
                key = key,
                label = if (json.isNull("label")) "" else json.optString("label"),
                // Worst first, whatever order they came in; a finding with no level of its own last.
                because = because.withIndex().sortedWith(compareBy({ -(it.value.level ?: -1) }, { it.index })).map { it.value },
                explanation = if (json.isNull("explanation")) "" else json.optString("explanation"),
            )
        }
    }
}

/**
 * A finding behind the risk level, as shown: its title, and the detail and source of the reason of the same type when
 * the result has one (`verdict.reasons`).
 */
data class RiskFinding(val type: String, val title: String, val level: Int?, val detail: String, val source: String)

/** What a check cost, when the server didn't say it in words (an older server): the same sentences, without a count. */
enum class Charge { QUICK, NOT_ASSESSED, REPEAT, FREE }

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
    /** Lets anyone who sees this result report it as wrong (`POST /v1/labels`, "Wrong result?"). */
    val labelToken: String? = null,
    /** The verdict exactly as the server sent it, for a report. */
    val verdictJson: JSONObject? = null,
    /** "quick" for the rules-only quick check; "deep" or empty (an older server) otherwise. */
    val tier: String = "",
    /** True for a free deep check (every shopper's deep check). */
    val freeCheck: Boolean? = null,
    /** What the check cost, as the server says it ("Deep checks are free."). */
    val chargeNote: String? = null,
    /** The overall risk level; null from an older server (the band is shown then). */
    val risk: Risk? = null,
) {
    /**
     * The findings behind the risk level, worst first, each once, with the reason's detail and source where the result
     * has the reason. Empty without a risk level.
     */
    val riskFindings: List<RiskFinding>
        get() {
            val risk = risk ?: return emptyList()
            val reasons = verdict?.reasons.orEmpty().filter { it.type.isNotEmpty() }.associateBy { it.type }
            val seen = mutableSetOf<String>()
            return risk.because.filter { it.type.isEmpty() || seen.add(it.type) }.map { b ->
                val reason = reasons[b.type]
                RiskFinding(b.type, b.title, b.level, reason?.detail.orEmpty(), reason?.source.orEmpty())
            }
        }

    /** What the check cost, for a result without [chargeNote]; null when nothing can be said. */
    val charge: Charge?
        get() = when {
            tier == "quick" -> Charge.QUICK
            !assessed -> Charge.NOT_ASSESSED
            freeCheck == true -> Charge.FREE
            charged == false -> Charge.REPEAT
            else -> null
        }

    /** "Wrong result?" is offered: an assessed result with a label token. */
    val reportable: Boolean get() = labelToken != null && assessed

    /**
     * The findings the result showed, by type, for "Which are wrong?": the risk level's, then the reasons shown, each
     * once (a type the server didn't name can't be reported).
     */
    val shownFindings: List<Pair<String, String>>
        get() {
            val all = riskFindings.map { it.type to it.title } + reasonsToShow.map { it.type to it.title }
            return all.filter { it.first.isNotEmpty() && it.first != "overall" }.distinctBy { it.first }
        }

    /** Whether the page could be assessed at all (score -1 means it couldn't). */
    val assessed: Boolean get() = score >= 0

    val band: Band? get() = if (!assessed) null else verdict?.band ?: Band.fromScore(score)

    /** The reasons and good signs in the order to show them: for a reliable page, the good signs first and at most one reason. */
    val reasonsToShow: List<Reason>
        get() = verdict?.let { if (it.band == Band.RELIABLE) it.reasons.take(1) else it.reasons }.orEmpty()

    val positivesFirst: Boolean get() = risk?.let { it.level == 0 } ?: (verdict?.band == Band.RELIABLE)

    /**
     * Whether to show the catalogue's "overall" reason ("Nothing clear-cut"): a verdict below "Looks fine" always names
     * one. The server always sends one; a result without any (an older server's, without a verdict) doesn't.
     */
    val needsOverallReason: Boolean get() = band.let { it != null && it != Band.RELIABLE } && reasonsToShow.isEmpty()

    /** The suggestion to show under the result: a quick result's, when the server makes one. */
    val deepHint: DeepCheck? get() = verdict?.deepCheck?.takeIf { tier == "quick" && assessed }

    /** On a clean result (looks fine, nothing to note), how many checks found nothing worrying; null otherwise. */
    val cleanChecks: Int?
        get() {
            val clean = risk?.let { it.level == 0 && it.because.isEmpty() } ?: (verdict?.band == Band.RELIABLE && verdict.reasons.isEmpty())
            return verdict?.takeIf { clean }?.checksRun?.takeIf { it > 0 }
        }

    companion object {
        fun parse(json: JSONObject): AnalysisResult {
            val score = json.optInt("score", -1)
            return AnalysisResult(
                score = score,
                explanation = json.optString("explanation"),
                verdict = json.optJSONObject("verdict")?.let(::parseVerdict),
                charged = if (json.has("charged") && !json.isNull("charged")) json.optBoolean("charged") else null,
                labelToken = text(json, "label_token").takeIf { it.isNotEmpty() },
                verdictJson = json.optJSONObject("verdict"),
                tier = text(json, "tier"),
                freeCheck = if (json.has("free_check") && !json.isNull("free_check")) json.optBoolean("free_check") else null,
                chargeNote = text(json, "charge_note").takeIf { it.isNotEmpty() },
                risk = if (score >= 0) Risk.parse(json.optJSONObject("verdict")?.optJSONObject("risk")) else null,
            )
        }

        private fun parseVerdict(json: JSONObject): Verdict {
            return Verdict(
                band = Band.fromWire(json.optString("band")),
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
                            checks = if (c.has("checks") && !c.isNull("checks")) c.optInt("checks") else null,
                        )
                    }
                },
                checksRun = if (json.has("checks_run") && !json.isNull("checks_run")) json.optInt("checks_run") else null,
                nextStep = json.optJSONObject("next_step")?.let { text(it, "text") }?.takeIf { it.isNotEmpty() },
                deepCheck = json.optJSONObject("deep_check")?.let(::parseDeepCheck),
                incomplete = json.optJSONObject("incomplete")?.let { text(it, "text") }?.takeIf { it.isNotEmpty() },
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

        /** The suggestion, only when it is one (`suggested`, with its words). */
        private fun parseDeepCheck(json: JSONObject): DeepCheck? {
            val words = text(json, "text")
            if (!json.optBoolean("suggested") || words.isEmpty()) return null
            val action = when (text(json, "action")) {
                "run_deep" -> DeepCheck.Action.RUN_DEEP
                "donate" -> DeepCheck.Action.DONATE
                else -> DeepCheck.Action.NONE
            }
            return DeepCheck(
                text = words,
                cost = text(json, "cost"),
                action = action,
                actionLabel = text(json, "action_label").ifEmpty {
                    when (action) {
                        DeepCheck.Action.RUN_DEEP -> "Run a deep check"
                        DeepCheck.Action.DONATE -> "Support Unfret"
                        DeepCheck.Action.NONE -> ""
                    }
                },
                quiet = json.optBoolean("quiet"),
            )
        }

        /** A string field, empty when absent or null (org.json's optString would say "null"). */
        private fun text(json: JSONObject, name: String): String = if (json.isNull(name)) "" else json.optString(name)

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}

/**
 * `GET /v1/pricing`'s `free`: whether free deep checks are paused until tomorrow (the spend ceiling; quick
 * checks still work).
 */
data class FreeChecks(val paused: Boolean) {
    companion object {
        fun parse(json: JSONObject?) = FreeChecks(paused = json?.optBoolean("paused", false) ?: false)
    }
}

/** `GET /v1/analyze/challenge`: the proof of work one free deep check needs, or [paused] (then it isn't solved). */
data class DeepChallenge(val challenge: String, val bits: Int, val paused: Boolean, val pause: JSONObject? = null) {
    companion object {
        fun parse(json: JSONObject) = DeepChallenge(
            challenge = json.getString("challenge"),
            bits = json.getInt("bits"),
            paused = json.optBoolean("paused", false),
            pause = json.optJSONObject("pause"),
        )
    }
}
