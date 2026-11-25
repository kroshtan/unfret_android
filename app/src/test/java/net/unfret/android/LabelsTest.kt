package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** "Wrong result?" (`POST /v1/labels`) and the optional contributor key. */
class LabelsTest {
    private val request = JSONObject("""{"url":"https://shop.test/p","page_facts":{"url":"https://shop.test/p"},"proof_of_work":{"challenge":"c","solution":"1"}}""")

    @Test
    fun `a report sends the token, the request exactly as sent, and only what was chosen`() {
        val transport = RoutedTransport(mapOf("POST /v1/labels" to listOf(201 to """{"status":"queued","updated":false,"anonymous":true}""")))
        val report = LabelReport(
            token = "lt1",
            request = request,
            level = 2,
            wrong = listOf("pushy_copy"),
            explanation = "  The countdown is real: it's a one-day sale.  ",
            verdict = JSONObject("""{"risk":{"level":4}}"""),
        )

        val outcome = UnfretApi(UnfretApi.DEV, transport, donateOff = false).label(report)

        assertEquals(LabelOutcome("queued", anonymous = true), outcome)
        val sent = transport.sent.single()
        assertEquals("https://dev.unfret.net/v1/labels", sent.url)
        assertFalse(sent.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        val body = JSONObject(sent.body!!)
        assertEquals(setOf("label_token", "request", "level", "wrong", "explanation", "verdict"), body.keys().asSequence().toSet())
        // The request as sent, without its proof of work (the label token's hash doesn't cover it).
        val sentRequest = body.getJSONObject("request")
        assertFalse(sentRequest.has("proof_of_work"))
        assertEquals(JSONObject(request.toString()).apply { remove("proof_of_work") }.toString(), sentRequest.toString())
        assertTrue(request.has("proof_of_work")) // the caller's copy is left as it was
        assertEquals(2, body.getInt("level"))
        assertEquals("pushy_copy", body.getJSONArray("wrong").getString(0))
        assertEquals("The countdown is real: it's a one-day sale.", body.getString("explanation"))
    }

    @Test
    fun `a level, a wrong finding or a missing one is needed`() {
        assertFalse(LabelReport("lt1", request, explanation = "only words").complete)
        assertThrows(IllegalArgumentException::class.java) { LabelReport("lt1", request).body() }
        assertTrue(LabelReport("lt1", request, level = 0).complete)
        assertTrue(LabelReport("lt1", request, missing = listOf("new_domain")).complete)
        assertThrows(IllegalArgumentException::class.java) { LabelReport("lt1", request, level = 5).body() }
        val long = LabelReport("lt1", request, level = 1, explanation = "x".repeat(5000)).body()
        assertEquals(LabelReport.MAX_EXPLANATION, long.getString("explanation").length)
    }

    @Test
    fun `the contributor key - 32 random bytes, base64url, no padding, only with reports`() {
        val key = ContributorKey.generate(SecureRandom())
        assertEquals(43, key.length)
        assertTrue(ContributorKey.isValid(key))
        assertEquals(32, Base64.getUrlDecoder().decode(key).size)
        assertFalse(key.contains('=') || key.contains('+') || key.contains('/'))
        assertFalse(ContributorKey.isValid("short"))
        assertFalse(ContributorKey.isValid(null))
        assertFalse(ContributorKey.generate() == ContributorKey.generate())

        val body = LabelReport("lt1", request, level = 3, contributorKey = key).body()
        assertEquals(key, body.getString("contributor_key"))
    }

    @Test
    fun `a check never carries the contributor key`() {
        val transport = RoutedTransport(
            mapOf(
                "GET /v1/analyze/challenge" to listOf(200 to """{"challenge":"c","bits":1,"expires_at":1.0,"paused":false}"""),
                "POST /v1/analyze/stream" to listOf(200 to "event: result\ndata: {\"score\": 50, \"explanation\": \"x\"}\n\n"),
            ),
        )
        val api = UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false)
        api.deepCheck(JSONObject("""{"url":"https://shop.test/p"}"""), solve = { "1" })
        api.analyze(JSONObject("""{"url":"https://shop.test/p","tier":"quick"}"""))
        for (sent in transport.sent) {
            val everything = sent.headers.toString() + sent.body.orEmpty() + sent.url
            assertFalse(everything, everything.contains("contributor", ignoreCase = true))
        }
    }

    @Test
    fun `the contributor's profile, name and leaderboard`() {
        val transport = RoutedTransport(
            mapOf(
                "POST /v1/contributors" to listOf(
                    200 to """{"handle":"lampfinder","public":true,"labels":3,"settled":2,"agreed":2,"points":2,"trusted":false,"rank":5}""",
                ),
                "POST /v1/contributors/forget" to listOf(200 to """{"deleted":true}"""),
                "GET /v1/contributors/leaderboard?limit=50" to listOf(
                    200 to """{"entries":[{"rank":1,"handle":"ace","agreed":12,"settled":13},{"rank":2,"handle":null,"agreed":1,"settled":1}]}""",
                ),
            ),
        )
        val api = UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false)
        val key = ContributorKey.generate()
        assertEquals(Contributor("lampfinder", true, 3, 2, 2, 5), api.contributor(key, handle = "lampfinder", public = true))
        val sent = JSONObject(transport.sent.last().body!!)
        assertEquals(setOf("contributor_key", "handle", "public"), sent.keys().asSequence().toSet())
        assertTrue(api.forgetContributor(key))
        assertEquals(listOf(LeaderboardEntry(1, "ace", 12, 13)), api.leaderboard())
    }

    @Test
    fun `refusals in the app's words`() {
        fun refusal(status: Int, code: String) = labelRefusal(ApiError(status, code, "x"))
        assertEquals(R.string.report_expired, refusal(400, "BAD_LABEL_TOKEN"))
        assertEquals(R.string.report_by_you, refusal(409, "ALREADY_LABELLED"))
        assertEquals(R.string.report_limit, refusal(429, "LABEL_LIMIT"))
        assertEquals(R.string.report_paused, refusal(503, "QUEUE_FULL"))
        assertEquals(R.string.report_paused, refusal(503, "LABELS_PAUSED"))
        assertEquals(R.string.handle_taken, refusal(409, "HANDLE_TAKEN"))
        assertNull(refusal(500, "INTERNAL_ERROR"))
        assertNull(labelRefusal(java.io.IOException("offline")))
    }

    @Test
    fun `the findings a report can call wrong, and the missing ones offered`() {
        val result = AnalysisResult.parse(
            JSONObject(
                """{"score": 30, "explanation": "x", "label_token": "lt1", "verdict": {"reasons": [
                  {"type": "new_domain", "title": "Brand-new web shop", "detail": "", "severity": "high"},
                  {"type": "overall", "title": "Nothing clear-cut", "detail": "", "severity": "low"}],
                "risk": {"level": 4, "key": "wont_arrive", "label": "x", "explanation": "y",
                  "because": [{"type": "new_domain", "level": 4, "title": "Brand-new web shop"}, {"type": "pushy_copy", "level": 1, "title": "Pushy sales talk"}]}}}""",
            ),
        )
        assertEquals(listOf("new_domain" to "Brand-new web shop", "pushy_copy" to "Pushy sales talk"), result.shownFindings)
        assertTrue(MISSING_CHOICES.any { it.first == "new_domain" })
        assertEquals(MISSING_CHOICES.size, MISSING_CHOICES.map { it.first }.toSet().size)
    }
}
