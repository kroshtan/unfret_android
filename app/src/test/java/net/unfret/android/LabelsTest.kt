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
            comment = "  The countdown is real: it's a one-day sale.  ",
            explanation = "Level 4 because: Little shop information.",
            verdict = JSONObject("""{"risk":{"level":4}}"""),
        )

        val outcome = UnfretApi(UnfretApi.DEV, transport, donateOff = false).label(report)

        assertEquals(LabelOutcome("queued", anonymous = true), outcome)
        val sent = transport.sent.single()
        assertEquals("https://dev.unfret.net/v1/labels", sent.url)
        assertFalse(sent.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        val body = JSONObject(sent.body!!)
        assertEquals(setOf("label_token", "request", "level", "wrong", "comment", "explanation", "verdict"), body.keys().asSequence().toSet())
        // The request as sent, without its proof of work (the label token's hash doesn't cover it).
        val sentRequest = body.getJSONObject("request")
        assertFalse(sentRequest.has("proof_of_work"))
        assertEquals(JSONObject(request.toString()).apply { remove("proof_of_work") }.toString(), sentRequest.toString())
        assertTrue(request.has("proof_of_work")) // the caller's copy is left as it was
        assertEquals(2, body.getInt("level"))
        assertEquals("pushy_copy", body.getJSONArray("wrong").getString(0))
        assertEquals("The countdown is real: it's a one-day sale.", body.getString("comment"))
        assertEquals("Level 4 because: Little shop information.", body.getString("explanation"))
    }

    @Test
    fun `a level, a wrong finding or a missing one is needed`() {
        assertFalse(LabelReport("lt1", request, comment = "only words").complete)
        assertThrows(IllegalArgumentException::class.java) { LabelReport("lt1", request).body() }
        assertTrue(LabelReport("lt1", request, level = 0).complete)
        assertTrue(LabelReport("lt1", request, missing = listOf("new_domain")).complete)
        assertThrows(IllegalArgumentException::class.java) { LabelReport("lt1", request, level = 5).body() }
        val long = LabelReport("lt1", request, level = 1, comment = "x".repeat(5000)).body()
        assertEquals(LabelReport.MAX_COMMENT, long.getString("comment").length)
        assertFalse(long.has("explanation"))
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
        assertTrue(MISSING_CHOICES.any { it.type == "new_domain" })
        assertEquals(MISSING_CHOICES.size, MISSING_CHOICES.map { it.type }.toSet().size)
    }

    @Test
    fun `the missing choices come from the catalogue, without modifiers or what was shown, else the offline list`() {
        val transport = RoutedTransport(
            mapOf(
                "GET /v1/catalogue/findings" to listOf(
                    200 to """{"findings":[{"type":"new_domain","title":"Brand-new web shop","level":4,"key":"wont_arrive"},
                    {"type":"fresh_type","title":"Something new","level":2,"key":"overpaying"},
                    {"type":"irreversible_payment_only","title":"Only payments you can't get back","level":null,"key":null},
                    {"type":"","title":"x","level":1}]}""",
                ),
            ),
        )
        val catalogue = UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).findingsCatalogue()
        assertEquals(3, catalogue.size)
        val shown = listOf("new_domain" to "Brand-new web shop")
        assertEquals(listOf(CatalogueFinding("fresh_type", "Something new", 2)), missingChoices(catalogue, shown))
        val offline = missingChoices(null, shown)
        assertFalse(offline.any { it.type == "new_domain" })
        // Without new_domain (shown) and irreversible_payment_only (a modifier, no level).
        assertEquals(MISSING_CHOICES.size - 2, offline.size)
        assertEquals(offline, missingChoices(emptyList(), shown))
    }

    @Test
    fun `every finding behind the level has its own source`() {
        val result = AnalysisResult.parse(
            JSONObject(
                """{"score": 30, "explanation": "x", "verdict": {"reasons": [],
                "risk": {"level": 4, "key": "wont_arrive", "label": "x", "explanation": "y",
                  "because": [{"type": "new_domain", "level": 4, "title": "Brand-new web shop", "source": "Domain registry (RDAP)"},
                              {"type": "pushy_copy", "level": 1, "title": "Pushy sales talk", "source": null}]}}}""",
            ),
        )
        assertEquals(listOf("Domain registry (RDAP)", ""), result.riskFindings.map { it.source })
    }

    @Test
    fun `missing choices are grouped by level, worst first, in the catalogue's order, and can be searched`() {
        val catalogue = listOf(
            CatalogueFinding("a", "Pushy sales talk", 1),
            CatalogueFinding("b", "Brand-new web shop", 4),
            CatalogueFinding("c", "Generic product", 2),
            CatalogueFinding("d", "No company details", 4),
            CatalogueFinding("e", "Only payments you can't get back", null),
        )
        val choices = missingChoices(catalogue, emptyList())
        assertEquals(listOf("b", "d", "c", "a"), choices.map { it.type })
        assertEquals(listOf(4, 2, 1), missingGroups(choices).map { it.first })
        assertEquals(listOf("b", "d"), missingGroups(choices).first().second.map { it.type })
        assertTrue(matchesSearch("Brand-new web shop", ""))
        assertTrue(matchesSearch("Brand-new web shop", "  WEB  new "))
        assertFalse(matchesSearch("Brand-new web shop", "web reviews"))
    }
}
