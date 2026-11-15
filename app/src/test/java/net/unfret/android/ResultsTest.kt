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
    fun `an unknown band is no verdict`() {
        val result = AnalysisResult.parse(JSONObject("""{"score": 10, "explanation": "x", "verdict": {"band": "new"}}"""))
        assertNull(result.verdict)
        assertEquals(Band.JUNK_OR_SCAM, result.band)
        assertEquals("✕", result.band!!.symbol)
    }

    @Test
    fun `where Get checks goes`() {
        assertEquals("https://unfret.net/checkout", checkoutUrl(UnfretApi.PRODUCTION, hasKey = false))
        assertEquals("https://unfret.net/checkout?mode=topup", checkoutUrl(UnfretApi.PRODUCTION, hasKey = true))
        assertEquals("https://dev.unfret.net/checkout?mode=code", codeUrl(UnfretApi.DEV))
    }
}
