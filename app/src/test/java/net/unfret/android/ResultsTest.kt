package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsTest {
    @Test
    fun `a reliable page shows its good signs first and at most one reason`() {
        val result = AnalysisResult.parse(
            JSONObject(
                """{"score": 91, "explanation": "Looks fine.", "charged": false, "verdict": {"band": "reliable",
                "label": "Looks reliable", "headline": "Looks fine.", "reasons": [
                  {"title": "a", "detail": "", "severity": "low"}, {"title": "b", "detail": "", "severity": "low"}],
                "positives": [{"title": "Known brand", "detail": "On Wikidata."}, {"title": "", "detail": "dropped"}]}}""",
            ),
        )
        assertEquals(Band.RELIABLE, result.band)
        assertTrue(result.positivesFirst)
        assertEquals(listOf("a"), result.reasonsToShow.map { it.title })
        assertEquals(listOf("Known brand"), result.verdict!!.positives.map { it.title })
        assertEquals(false, result.charged)
    }

    @Test
    fun `a page that couldn't be assessed`() {
        val result = AnalysisResult.parse(JSONObject("""{"score": -1, "explanation": "This doesn't look like a product page."}"""))
        assertFalse(result.assessed)
        assertNull(result.band)
        assertNull(result.verdict)
        assertNull(result.charged)
        assertTrue(result.reasonsToShow.isEmpty())
    }

    @Test
    fun `an older server without a verdict gets the band from the score`() {
        assertEquals(Band.RELIABLE, Band.fromScore(80))
        assertEquals(Band.CONCERNS, Band.fromScore(79))
        assertEquals(Band.CONCERNS, Band.fromScore(50))
        assertEquals(Band.CAREFUL, Band.fromScore(25))
        assertEquals(Band.JUNK_OR_SCAM, Band.fromScore(24))
        assertNull(Band.fromScore(-1))
        assertEquals(Band.CONCERNS, AnalysisResult.parse(JSONObject("""{"score": 60, "explanation": "x"}""")).band)
    }

    @Test
    fun `a verdict below Looks fine always names a reason`() {
        // The server always sends one; a result without (an older server's) shows the catalogue's overall reason.
        for (score in listOf(60, 30, 10)) {
            assertTrue(AnalysisResult.parse(JSONObject("""{"score": $score, "explanation": "x"}""")).needsOverallReason)
        }
        val middle = AnalysisResult.parse(
            JSONObject(
                """{"score": 40, "explanation": "x", "verdict": {"band": "careful", "label": "Think twice",
                "headline": "x", "reasons": [{"title": "Fake countdown", "detail": "", "severity": "high"}]}}""",
            ),
        )
        assertFalse(middle.needsOverallReason)
        assertEquals(listOf("Fake countdown"), middle.reasonsToShow.map { it.title })
        assertFalse(AnalysisResult.parse(JSONObject("""{"score": 90, "explanation": "x"}""")).needsOverallReason)
        assertFalse(AnalysisResult.parse(JSONObject("""{"score": -1, "explanation": "x"}""")).needsOverallReason)
    }

    @Test
    fun `an unknown band is no verdict`() {
        val result = AnalysisResult.parse(JSONObject("""{"score": 10, "explanation": "x", "verdict": {"band": "new"}}"""))
        assertNull(result.verdict)
        assertEquals(Band.JUNK_OR_SCAM, result.band)
        assertEquals("✕", result.band!!.symbol)
    }

    @Test
    fun `the result card has sources, the same product, the next step and what was checked`() {
        val result = AnalysisResult.parse(
            JSONObject(
                """{"score": 35, "explanation": "x", "report_token": "rt1", "verdict": {"band": "careful",
                "label": "Think twice", "headline": "x",
                "reasons": [{"type": "new_domain", "title": "Brand-new web shop", "detail": "23 days.", "severity": "high",
                  "source": "Domain registry (RDAP)"}],
                "positives": [{"title": "Payment with buyer protection", "detail": "", "source": null}],
                "same_product": {"title": "Looks like the same product, sold elsewhere for ${'$'}12.99", "detail": "d",
                  "where": "cheap.shop", "image": "https://unfret.net/v1/images/1/thumbnail.jpg", "own_image": null},
                "next_step": {"type": "shop_reviews", "text": "Look for reviews of the shop."},
                "checklist": [{"key": "who", "label": "Who's behind the shop", "status": "concern", "note": "Brand-new web shop"},
                  {"key": "reviews", "label": "Reviews", "status": "not_checked", "note": "The page shows no reviews."},
                  {"key": "tricks", "label": "Hidden tricks", "status": "checked", "note": ""}],
                "checks_run": 11}}""",
            ),
        )
        val verdict = result.verdict!!
        assertEquals("Domain registry (RDAP)", verdict.reasons.single().source)
        assertEquals("new_domain", verdict.reasons.single().type)
        assertEquals("", verdict.positives.single().source) // JSON null is no source, not "null"
        assertEquals("cheap.shop", verdict.sameProduct!!.where)
        assertNull(verdict.sameProduct!!.ownImage)
        assertEquals("Look for reviews of the shop.", verdict.nextStep)
        assertEquals(
            listOf(CheckStatus.CONCERN, CheckStatus.NOT_CHECKED, CheckStatus.CHECKED),
            verdict.checklist.map { it.status },
        )
        assertEquals(11, verdict.checksRun)
        assertNull(result.cleanChecks) // a warning, not a clean result
        assertEquals("rt1", result.reportToken)
        assertEquals("careful", result.verdictJson!!.optString("band")) // sent back as returned with a report
    }

    @Test
    fun `a clean result says how many checks found nothing, and an older one has no card`() {
        val clean = AnalysisResult.parse(
            JSONObject(
                """{"score": 92, "explanation": "x", "verdict": {"band": "reliable", "label": "Looks fine",
                "headline": "x", "reasons": [], "positives": [], "checks_run": 14}}""",
            ),
        )
        assertEquals(14, clean.cleanChecks)
        val older = AnalysisResult.parse(
            JSONObject("""{"score": 92, "explanation": "x", "verdict": {"band": "reliable", "label": "Looks fine", "headline": "x"}}"""),
        )
        assertNull(older.cleanChecks)
        assertTrue(older.verdict!!.checklist.isEmpty())
        assertNull(older.verdict!!.nextStep)
        assertNull(older.verdict!!.sameProduct)
        assertNull(older.reportToken)
    }

    @Test
    fun `photos load only from the API's own thumbnail proxy`() {
        val base = UnfretApi.PRODUCTION
        assertEquals("https://unfret.net/v1/images/7/thumbnail.jpg", apiImage("https://unfret.net/v1/images/7/thumbnail.jpg", base))
        assertNull(apiImage("https://dev.unfret.net/v1/images/7/thumbnail.jpg", base))
        assertNull(apiImage("https://unfret.net.evil.example/v1/images/7/thumbnail.jpg", base))
        assertNull(apiImage("https://unfret.net/v1/credits", base))
        assertNull(apiImage("https://unfret.net/v1/images/../credits", base))
        assertNull(apiImage("https://unfret.net/v1/images/7/thumbnail.jpg?x=1", base))
        assertNull(apiImage("https://cheap.shop/photo.jpg", base))
        assertNull(apiImage(null, base))
    }

    @Test
    fun `a quick result shows the server's suggestion of a deep check, and nothing without one`() {
        fun quick(deepCheck: String, tier: String = "quick") = AnalysisResult.parse(
            JSONObject(
                """{"score": 45, "explanation": "x", "tier": "$tier", "verdict": {"band": "concerns",
                "label": "Worth a look", "headline": "x", "reasons": [], "deep_check": $deepCheck}}""",
            ),
        )
        val offer = """{"suggested": true, "reason": "on_the_fence", "quiet": false,
            "adds": [{"key": "shop_age", "area": "who", "label": "how old this shop is"}],
            "text": "A deep check also looks at how old this shop is.",
            "cost": "Free deep checks are paused until next month.", "action": "donate", "action_label": "Support Unfret"}"""
        val hint = quick(offer).deepHint!!
        assertEquals("A deep check also looks at how old this shop is.", hint.text)
        assertEquals("Free deep checks are paused until next month.", hint.cost)
        assertEquals(DeepCheck.Action.DONATE, hint.action)
        assertEquals("Support Unfret", hint.actionLabel)
        assertFalse(hint.quiet)

        val run = quick(offer.replace("\"donate\"", "\"run_deep\"").replace("\"Support Unfret\"", "null")
            .replace("\"quiet\": false", "\"quiet\": true")).deepHint!!
        assertTrue(run.action == DeepCheck.Action.RUN_DEEP && run.quiet)
        assertEquals("Run a deep check", run.actionLabel)

        // The Play build's server sends no action while paused.
        val none = quick(offer.replace("\"action\": \"donate\", \"action_label\": \"Support Unfret\"", "\"cost2\": 1")).deepHint!!
        assertEquals(DeepCheck.Action.NONE, none.action)
        assertEquals("", none.actionLabel)

        assertNull(quick("""{"suggested": false, "reason": "serious_concerns", "text": ""}""").deepHint)
        assertNull(quick(offer, tier = "deep").deepHint)
        assertNull(quick("null").deepHint) // an older server
    }

    @Test
    fun `what was checked adds up, area by area`() {
        val result = AnalysisResult.parse(
            JSONObject(
                """{"score": 35, "explanation": "x", "verdict": {"band": "careful", "label": "Think twice", "headline": "x",
                "reasons": [], "positives": [],
                "checklist": [{"key": "who", "label": "Who's behind the shop", "status": "concern", "note": "New", "checks": 4},
                  {"key": "reviews", "label": "Reviews", "status": "not_checked", "note": "No reviews.", "checks": 0},
                  {"key": "tricks", "label": "Hidden tricks", "status": "checked", "note": "", "checks": 3}],
                "checks_run": 7}}""",
            ),
        )
        val verdict = result.verdict!!
        assertEquals(listOf(4, 0, 3), verdict.checklist.map { it.checks })
        assertEquals(verdict.checksRun, verdict.checklist.sumOf { it.checks ?: 0 })
        assertEquals(2, verdict.checkedAreas) // "What we checked: 7 checks in 2 areas"
        val older = AnalysisResult.parse(
            JSONObject(
                """{"score": 35, "explanation": "x", "verdict": {"band": "careful", "label": "Think twice", "headline": "x",
                "checklist": [{"key": "who", "label": "Who's behind the shop", "status": "checked", "note": ""}],
                "checks_run": 7}}""",
            ),
        )
        assertNull(older.verdict!!.checkedAreas) // "What we checked · 7 checks", as before
    }

    @Test
    fun `a result whose AI read didn't finish says so`() {
        val line = "Part of this check didn't finish (the AI read of the page), so this answer may be milder than it should be."
        val partial = AnalysisResult.parse(
            JSONObject(
                """{"score": 40, "explanation": "x", "verdict": {"band": "careful", "label": "Think twice", "headline": "x",
                "incomplete": {"steps": ["content"], "text": "$line"}}}""",
            ),
        )
        assertEquals(line, partial.verdict!!.incomplete)
        val whole = AnalysisResult.parse(
            JSONObject("""{"score": 40, "explanation": "x", "verdict": {"band": "careful", "label": "x", "headline": "x", "incomplete": null}}"""),
        )
        assertNull(whole.verdict!!.incomplete)
    }

    @Test
    fun `what a check was, in the server's words or by kind`() {
        val free = AnalysisResult.parse(
            JSONObject(
                """{"score": 40, "explanation": "x", "charged": false, "free_check": true, "tier": "deep",
                "charge_note": "Deep checks are free."}""",
            ),
        )
        assertEquals("Deep checks are free.", free.chargeNote)
        assertEquals(Charge.FREE, free.charge)
        fun kind(json: String) = AnalysisResult.parse(JSONObject(json)).charge
        assertEquals(Charge.REPEAT, kind("""{"score": 40, "explanation": "x", "charged": false}"""))
        assertEquals(Charge.NOT_ASSESSED, kind("""{"score": -1, "explanation": "x", "charged": false}"""))
        assertEquals(Charge.QUICK, kind("""{"score": 40, "explanation": "x", "charged": false, "tier": "quick"}"""))
        assertNull(kind("""{"score": 40, "explanation": "x"}"""))
        assertNull(AnalysisResult.parse(JSONObject("""{"score": 40, "explanation": "x", "charge_note": null}""")).chargeNote)
    }
}
