package net.unfret.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * "Wrong result?", the one report flow (`POST /v1/labels`, contract v1): the level the shopper thinks is right, the
 * findings shown that are wrong, findings that are missing, and an optional comment, sent with the result's
 * `label_token` and the analysed request as sent, without its `proof_of_work` (the token's hash doesn't cover it, and
 * the report has no use for it). The report keeps the page it is about (scrubbed), never who
 * sent it. At least one of [level], [wrong] and [missing] is needed.
 */
data class LabelReport(
    val token: String,
    val request: JSONObject,
    val level: Int? = null,
    val wrong: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    /** The shopper's own words (`comment`, at most [MAX_COMMENT]); the server removes personal details from it. */
    val comment: String = "",
    /** The result's own explanation, as returned (`explanation`). */
    val explanation: String = "",
    val verdict: JSONObject? = null,
    /** Only when the user turned the contributor key on ([ContributorKey]). */
    val contributorKey: String? = null,
) {
    val complete: Boolean get() = level != null || wrong.isNotEmpty() || missing.isNotEmpty()

    fun body(): JSONObject {
        require(complete) { "a level, a wrong finding or a missing one is needed" }
        require(level == null || level in 0..Risk.MAX) { "level $level" }
        val page = JSONObject(request.toString()).apply { remove("proof_of_work") }
        val body = JSONObject().put("label_token", token).put("request", page)
        if (level != null) body.put("level", level)
        if (wrong.isNotEmpty()) body.put("wrong", JSONArray(wrong))
        if (missing.isNotEmpty()) body.put("missing", JSONArray(missing))
        comment.trim().take(MAX_COMMENT).takeIf { it.isNotEmpty() }?.let { body.put("comment", it) }
        explanation.takeIf { it.isNotEmpty() }?.let { body.put("explanation", it) }
        if (verdict != null) body.put("verdict", verdict)
        if (contributorKey != null) body.put("contributor_key", contributorKey)
        return body
    }

    companion object {
        const val MAX_COMMENT = 500
    }
}

/** `POST /v1/labels`'s answer: queued, already_reported or already_known. */
data class LabelOutcome(val status: String, val anonymous: Boolean)

/** `POST /v1/contributors`: the contributor's handle and how their reports went. */
data class Contributor(
    val handle: String,
    val public: Boolean,
    val labels: Int,
    val agreed: Int,
    val points: Int,
    val rank: Int?,
) {
    companion object {
        fun parse(json: JSONObject) = Contributor(
            handle = if (json.isNull("handle")) "" else json.optString("handle"),
            public = json.optBoolean("public", false),
            labels = json.optInt("labels", 0),
            agreed = json.optInt("agreed", 0),
            points = json.optInt("points", json.optInt("agreed", 0)),
            rank = if (json.has("rank") && !json.isNull("rank")) json.optInt("rank") else null,
        )
    }
}

/** One row of the public leaderboard (pseudonymous handles of contributors who opted in). */
data class LeaderboardEntry(val rank: Int, val handle: String, val agreed: Int, val settled: Int)

/**
 * The optional contributor key: 32 random bytes in base64url without padding (43 characters), made and kept on the
 * phone, sent only with reports and contributor calls ([UnfretApi.label], [UnfretApi.contributor]), never with a
 * check. Losing it starts a new profile.
 */
object ContributorKey {
    private val FORM = Regex("^[A-Za-z0-9_-]{43}$")

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isValid(key: String?): Boolean = key != null && FORM.matches(key)
}

/**
 * Findings a shopper may say are missing (`missing`), offline only: the server's catalogue
 * (`GET /v1/catalogue/findings`, [UnfretApi.findingsCatalogue]) is used whenever it could be loaded. The same list as
 * the extension's (`src/shared/report.ts`). Those the result already showed are left out.
 */
val MISSING_CHOICES: List<CatalogueFinding> = listOf(
    CatalogueFinding("new_domain", "Brand-new web shop", 4),
    CatalogueFinding("no_imprint", "No company details", 4),
    CatalogueFinding("far_below_market", "Far below the usual price", 4),
    CatalogueFinding("seller_lookalike", "Seller named like the brand", 4),
    CatalogueFinding("safety_gate_alert", "On the EU list of dangerous products", 4),
    CatalogueFinding("irreversible_payment_only", "Only payments you can't get back", null),
    CatalogueFinding("claim_contradicted", "A claim that doesn't add up", 3),
    CatalogueFinding("implausible_claims", "Hard-to-believe claims", 3),
    CatalogueFinding("generic_product", "Generic product", 2),
    CatalogueFinding("dropshipping_tools", "Dropshipping", 2),
    CatalogueFinding("ships_from_far", "Ships from far away", 2),
    CatalogueFinding("drip_pricing", "Price goes up at checkout", 2),
    CatalogueFinding("no_return_policy", "No return policy found", 2),
    CatalogueFinding("fake_urgency_app", "Urgency tricks", 1),
    CatalogueFinding("preticked_addon", "Paid extra already ticked", 1),
    CatalogueFinding("was_never_charged", "'Was' price not charged recently", 1),
    CatalogueFinding("duplicate_reviews", "Copy-paste reviews", 1),
    CatalogueFinding("pushy_copy", "Pushy sales talk", 1),
)

/** Why `POST /v1/labels` or `/v1/contributors` refused, in the app's words, by error code; null for other failures. */
fun labelRefusal(error: Throwable): Int? = if (error !is ApiError) {
    null
} else {
    when (error.code) {
        "BAD_LABEL_TOKEN" -> R.string.report_expired
        "ALREADY_LABELLED", "ALREADY_REPORTED_BY_YOU" -> R.string.report_by_you
        "LABEL_LIMIT" -> R.string.report_limit
        "LABELS_PAUSED", "LABELS_OFF", "QUEUE_FULL" -> R.string.report_paused
        "BAD_HANDLE" -> R.string.handle_invalid
        "HANDLE_TAKEN" -> R.string.handle_taken
        else -> null
    }
}

/**
 * The missing-finding choices for a result: the catalogue's types with a level (a payment modifier has none), or the
 * offline list ([MISSING_CHOICES]) without the catalogue, each once, minus what the result showed; worst level first
 * (the catalogue's own order within a level), as the extension groups them.
 */
fun missingChoices(catalogue: List<CatalogueFinding>?, shown: List<Pair<String, String>>): List<CatalogueFinding> {
    val source = catalogue?.takeIf { it.isNotEmpty() } ?: MISSING_CHOICES
    val showed = shown.map { it.first }.toSet()
    return source
        .filter { it.level != null && it.type !in showed && it.type != "overall" }
        .distinctBy { it.type }
        .sortedByDescending { it.level } // stable: the catalogue's order within a level
}

/** [choices] grouped by level, worst first, for headings in the level's words. */
fun missingGroups(choices: List<CatalogueFinding>): List<Pair<Int, List<CatalogueFinding>>> =
    choices.filter { it.level != null }.groupBy { it.level!! }.toList().sortedByDescending { it.first }

/** Whether [title] matches what was typed in the search field: every word, anywhere, in any case. */
fun matchesSearch(title: String, query: String): Boolean =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { title.contains(it, ignoreCase = true) }

/** One entry of `GET /v1/catalogue/findings`: a finding type, its title, and its default level (null: a modifier). */
data class CatalogueFinding(val type: String, val title: String, val level: Int?)
