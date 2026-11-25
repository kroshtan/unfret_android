package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Free checks, quick and deep, with no pass: what the screen offers, and the deep check's proof of work. */
class QuickCheckTest {
    private val result = "event: result\ndata: {\"score\": 80, \"explanation\": \"Fine.\", \"charged\": false, \"free_check\": true}\n\n"
    private val challenge = """{"challenge":"c.1.4.m","bits":4,"expires_at":1.0,"paused":false}"""
    private val page = JSONObject("""{"url":"https://shop.test/p","page_facts":{"url":"https://shop.test/p"}}""")

    @Test
    fun `the deep check first and the quick check under it, only the quick check while paused`() {
        val open = QuickCheck.offer(paused = false)
        assertEquals(QuickCheck.Main.DEEP, open.main)
        assertTrue(open.quickOption)
        val paused = QuickCheck.offer(paused = true)
        assertEquals(QuickCheck.Main.QUICK, paused.main)
        assertFalse(paused.quickOption)
    }

    @Test
    fun `a deep check that can't run now becomes a quick one, others don't`() {
        assertTrue(QuickCheck.fallsBackToQuick(Problem.PAUSED))
        assertTrue(QuickCheck.fallsBackToQuick(Problem.TOO_MANY))
        assertTrue(QuickCheck.fallsBackToQuick(Problem.SERVICE_OFF))
        assertFalse(QuickCheck.fallsBackToQuick(Problem.FAILED))
        assertFalse(QuickCheck.fallsBackToQuick(Problem.OFFLINE))
    }

    @Test
    fun `a free deep check - the challenge, solved, then the check with its proof and no pass`() {
        val transport = RoutedTransport(
            mapOf(
                "GET /v1/analyze/challenge" to listOf(200 to challenge),
                "POST /v1/analyze/stream" to listOf(200 to result),
            ),
        )
        val solved = mutableListOf<DeepChallenge>()
        val (answer, sent) = UnfretApi(UnfretApi.DEV, transport, donateOff = false).deepCheck(page, solve = { c ->
            solved += c
            ProofOfWork.solve(c.challenge, c.bits)
        })

        assertEquals(80, answer.score)
        assertEquals(listOf("GET /v1/analyze/challenge", "POST /v1/analyze/stream"), transport.paths())
        assertEquals("c.1.4.m", solved.single().challenge)
        val post = transport.sent.last()
        assertEquals(sent.toString(), post.body) // the request exactly as sent, for "Wrong result?"
        val body = JSONObject(post.body!!)
        assertEquals(setOf("url", "page_facts", "proof_of_work"), body.keys().asSequence().toSet())
        val proof = body.getJSONObject("proof_of_work")
        assertEquals("c.1.4.m", proof.getString("challenge"))
        assertTrue(ProofOfWork.solves("c.1.4.m", proof.getString("solution"), 4))
        // Nothing names the user or the phone.
        for (request in transport.sent) {
            assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertFalse("X-Unfret-Donate" in request.headers)
        }
        assertFalse(page.has("proof_of_work")) // the page itself is left as it was
    }

    @Test
    fun `a refused challenge is fetched and solved once more`() {
        val transport = RoutedTransport(
            mapOf(
                "GET /v1/analyze/challenge" to listOf(200 to challenge),
                "POST /v1/analyze/stream" to listOf(
                    400 to """{"detail":"Expired","error_code":"BAD_CHALLENGE"}""",
                    200 to result,
                ),
            ),
        )
        val api = UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = true)
        api.deepCheck(page, solve = { "7" })
        assertEquals(4, transport.sent.size)
        assertTrue(transport.sent.all { it.headers["X-Unfret-Donate"] == "off" })

        // Twice refused: given up, with the server's code.
        val stubborn = RoutedTransport(
            mapOf(
                "GET /v1/analyze/challenge" to listOf(200 to challenge),
                "POST /v1/analyze/stream" to listOf(428 to """{"detail":"x","error_code":"PROOF_OF_WORK_REQUIRED"}"""),
            ),
        )
        val error = assertThrows(ApiError::class.java) {
            UnfretApi(UnfretApi.PRODUCTION, stubborn, donateOff = false).deepCheck(page, solve = { "7" })
        }
        assertEquals("PROOF_OF_WORK_REQUIRED", error.code)
        assertEquals(4, stubborn.sent.size)
    }

    @Test
    fun `a paused challenge is never solved`() {
        val transport = RoutedTransport(
            mapOf(
                "GET /v1/analyze/challenge" to listOf(
                    200 to """{"challenge":"c","bits":20,"expires_at":1.0,"paused":true,
                    "pause":{"reason":"spend_ceiling","retry_after_s":86400,"quick_checks":true,"message_key":"free_checks_paused"}}""",
                ),
            ),
        )
        val error = assertThrows(ApiError::class.java) {
            UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = true).deepCheck(page, solve = { error("solved") })
        }
        assertEquals(Problem.PAUSED, Problem.of(error))
        assertEquals(86400, error.retryAfterS)
        assertEquals(listOf("GET /v1/analyze/challenge"), transport.paths())
    }

    @Test
    fun `too many deep checks from this network or none available - its own problem`() {
        for ((status, code, problem) in listOf(
            Triple(429, "TOO_MANY_REQUESTS", Problem.TOO_MANY),
            Triple(503, "SERVICE_UNAVAILABLE", Problem.SERVICE_OFF),
            Triple(429, "FREE_CHECKS_PAUSED", Problem.PAUSED),
        )) {
            val transport = RoutedTransport(
                mapOf(
                    "GET /v1/analyze/challenge" to listOf(200 to challenge),
                    "POST /v1/analyze/stream" to listOf(status to """{"detail":"x","error_code":"$code","metadata":{"retry_after_s":120}}"""),
                ),
            )
            val error = assertThrows(ApiError::class.java) {
                UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).deepCheck(page, solve = { "1" })
            }
            assertEquals(problem, Problem.of(error))
            assertEquals(2, transport.sent.size) // not retried
        }
    }

    @Test
    fun `a quick check goes without a proof of work`() {
        val transport = RoutedTransport(mapOf("POST /v1/analyze/stream" to listOf(200 to result)))
        val extraction = JSONObject().put("facts", JSONObject().put("url", "https://shop.test/p"))
        UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).analyze(AnalyzeRequest.fromExtraction(extraction, quick = true))
        val body = JSONObject(transport.sent.single().body!!)
        assertEquals("quick", body.getString("tier"))
        assertFalse(body.has("proof_of_work"))
        assertThrows(IllegalArgumentException::class.java) {
            UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).deepCheck(body, solve = { "1" })
        }
    }
}
