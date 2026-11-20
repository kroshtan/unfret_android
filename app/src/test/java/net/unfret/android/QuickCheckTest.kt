package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class QuickCheckTest {
    private fun offer(hasPass: Boolean = true, credits: Credits? = null, refused: Problem? = null) =
        QuickCheck.offer(hasPass, credits, refused)

    @Test
    fun `with checks to use, the deep check first and the quick check under it`() {
        val withChecks = offer(credits = Credits(7, null, freeLeft = 5))
        assertEquals(QuickCheck.Main.DEEP, withChecks.main)
        assertNull(withChecks.why)
        assertTrue(withChecks.quickOption)
        // Before the balance is known, the same.
        assertEquals(QuickCheck.Offer(QuickCheck.Main.DEEP, null), offer(credits = null))
        // Free checks paused, but bought ones left: the deep check runs on those.
        assertTrue(offer(credits = Credits(4, null, freeLeft = 3, freePaused = true)).quickOption)
    }

    @Test
    fun `no checks left - the main button is the quick check, offered once`() {
        val none = offer(credits = Credits(0, null))
        assertEquals(QuickCheck.Offer(QuickCheck.Main.QUICK, QuickCheck.Why.NO_CHECKS), none)
        assertFalse(none.quickOption)
    }

    @Test
    fun `only free checks while today's are used - the quick check`() {
        val paused = offer(credits = Credits(3, null, freeLeft = 3, freePaused = true))
        assertEquals(QuickCheck.Offer(QuickCheck.Main.QUICK, QuickCheck.Why.FREE_PAUSED), paused)
        assertFalse(paused.quickOption)
    }

    @Test
    fun `without a pass - the quick check, which needs none`() {
        assertEquals(QuickCheck.Offer(QuickCheck.Main.QUICK, QuickCheck.Why.NO_PASS), offer(hasPass = false))
    }

    @Test
    fun `a deep check the server refused makes the quick check the main one`() {
        assertEquals(QuickCheck.Why.NO_CHECKS, offer(credits = Credits(2, null), refused = Problem.NO_CHECKS).why)
        assertEquals(QuickCheck.Why.FREE_PAUSED, offer(credits = Credits(2, null), refused = Problem.FREE_PAUSED).why)
    }

    @Test
    fun `only a deep check that can't be paid for becomes a quick one`() {
        assertTrue(QuickCheck.fallsBackToQuick(Problem.NO_CHECKS))
        assertTrue(QuickCheck.fallsBackToQuick(Problem.FREE_PAUSED))
        for (other in listOf(Problem.TOO_MANY, Problem.BAD_KEY, Problem.FAILED, Problem.OFFLINE, Problem.SERVICE_OFF)) {
            assertFalse(other.name, QuickCheck.fallsBackToQuick(other))
        }
    }

    @Test
    fun `the quick checks' per-minute limit is its own problem`() {
        assertEquals(Problem.TOO_MANY, Problem.of(ApiError(429, "TOO_MANY_REQUESTS", "Too many quick checks in a minute; wait a moment.")))
        assertEquals(Problem.FREE_PAUSED, Problem.of(ApiError(429, "FREE_CHECKS_PAUSED", "x")))
    }

    private class OneAnswer(private val body: String) : Transport {
        val sent = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): HttpResponse {
            sent += request
            return HttpResponse(200, BufferedReader(StringReader(body)))
        }
    }

    @Test
    fun `a quick check without a pass goes without Authorization`() {
        val transport = OneAnswer("event: result\ndata: {\"score\": 80, \"explanation\": \"Fine.\", \"charged\": false, \"tier\": \"quick\"}\n\n")
        val extraction = JSONObject().put("facts", JSONObject().put("url", "https://shop.test/p"))

        val result = UnfretApi(UnfretApi.PRODUCTION, null, transport).analyze(AnalyzeRequest.fromExtraction(extraction, quick = true))

        assertEquals("quick", result.tier)
        val request = transport.sent.single()
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals("quick", JSONObject(request.body!!).getString("tier"))
        // A deep check needs a pass: it isn't sent without one.
        assertThrows(IllegalArgumentException::class.java) {
            UnfretApi(UnfretApi.PRODUCTION, null, transport).analyze(AnalyzeRequest.fromExtraction(extraction))
        }
        assertEquals(1, transport.sent.size)
    }
}
