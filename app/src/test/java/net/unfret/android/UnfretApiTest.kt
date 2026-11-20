package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

class UnfretApiTest {
    private val key = "ufk_abcdefgh23_" + "c".repeat(52)

    private class FakeTransport(private val status: Int, private val body: String) : Transport {
        val sent = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): HttpResponse {
            sent += request
            return HttpResponse(status, BufferedReader(StringReader(body)))
        }
    }

    private val verdict = """{"band":"careful","label":"Be careful","headline":"Several things don't add up.",
        "reasons":[{"type":"new_domain","title":"A very new shop","detail":"Registered 3 weeks ago.","severity":"high"},
                   {"type":"x","title":"Fake countdown","detail":"","severity":"low"}],
        "positives":[{"type":"y","title":"Lists a company address","detail":"In the imprint."}],"language":"en"}"""

    @Test
    fun `credits, with the key and nothing about the phone`() {
        val transport = FakeTransport(200, """{"address":"key:abcdefgh23","checks_remaining":7,"balance_usd_micros":1400000,"invite_eligible":false}""")
        val credits = UnfretApi(UnfretApi.PRODUCTION, key, transport).credits()

        assertEquals(Credits(7, false), credits)
        val request = transport.sent.single()
        assertEquals("GET", request.method)
        assertEquals("https://unfret.net/v1/credits", request.url)
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertTrue(request.headers.getValue("User-Agent").startsWith("Unfret-Android/"))
        assertEquals(setOf("Authorization", "Accept-Language", "User-Agent"), request.headers.keys)
    }

    @Test
    fun `remaining checks are never shown below zero`() {
        assertEquals(0, Credits.parse(JSONObject("""{"checks_remaining":-2}""")).checksRemaining)
        assertNull(Credits.parse(JSONObject("{}")).inviteEligible)
    }

    @Test
    fun `the streamed analysis`() {
        val stream = """
            : keep-alive

            event: step
            data: {"skill": "classify", "page_type": "product", "findings": [], "check": false}

            event: step
            data: {"skill": "domain_age", "check": true,
            data:  "findings": [{"type": "new_domain", "evidence": "3 weeks"}]}

            event: step
            data: {"skill": "triage", "findings": []}

            event: result
            data: {"score": 31, "explanation": "Several things don't add up.", "charged": true, "verdict": ${JSONObject(verdict)}}

        """.trimIndent()
        val transport = FakeTransport(200, stream)
        val steps = mutableListOf<Step>()
        val body = JSONObject("""{"url":"https://shop.test/p","page_facts":{"url":"https://shop.test/p"}}""")

        val result = UnfretApi(UnfretApi.DEV, key, transport).analyze(body) { steps += it }

        assertEquals(listOf(Step("classify", false), Step("domain_age", true), Step("triage", null)), steps)
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://dev.unfret.net/v1/analyze/stream", request.url)
        assertEquals("text/event-stream", request.headers["Accept"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(body.toString(), request.body)
        assertEquals(31, result.score)
        assertEquals(Band.CAREFUL, result.band)
        assertEquals(true, result.charged)
        val v = result.verdict!!
        assertEquals("Be careful", v.label)
        assertEquals(listOf(Severity.HIGH, Severity.LOW), v.reasons.map { it.severity })
        assertEquals("Lists a company address", v.positives.single().title)
        assertFalse(result.positivesFirst)
    }

    @Test
    fun `an error event`() {
        val transport = FakeTransport(200, "event: error\ndata: {\"status\": 402, \"error_code\": \"INSUFFICIENT_CREDITS\", \"detail\": \"No credits\"}\n\n")
        val error = assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, key, transport).analyze(JSONObject()) }
        assertEquals(Problem.NO_CHECKS, Problem.of(error))
    }

    @Test
    fun `a stream that ends without a result`() {
        val transport = FakeTransport(200, "event: step\ndata: {\"skill\": \"classify\"}\n\n")
        val error = assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, key, transport).analyze(JSONObject()) }
        assertEquals("STREAM_ENDED", error.code)
        assertEquals(Problem.FAILED, Problem.of(error))
    }

    @Test
    fun `error answers, in plain words`() {
        fun problem(status: Int, body: String): Problem = Problem.of(
            assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, key, FakeTransport(status, body)).credits() },
        )
        assertEquals(Problem.BAD_KEY, problem(401, """{"detail":"Invalid API key","error_code":"INVALID_API_KEY"}"""))
        assertEquals(Problem.NO_CHECKS, problem(402, """{"detail":"x","error_code":"INSUFFICIENT_CREDITS"}"""))
        assertEquals(Problem.SERVICE_OFF, problem(503, "<html>down</html>"))
        assertEquals(Problem.FAILED, problem(500, ""))
        assertEquals(Problem.OFFLINE, Problem.of(IOException("no route")))
    }

    @Test
    fun `only Unfret's servers`() {
        assertThrows(IllegalArgumentException::class.java) { UnfretApi("https://evil.example", key, FakeTransport(200, "{}")) }
        assertThrows(IllegalArgumentException::class.java) { UnfretApi("http://unfret.net", key, FakeTransport(200, "{}")) }
    }

    @Test
    fun `a report sends the request as it was sent, the result as returned, and the consent`() {
        val transport = FakeTransport(201, """{"status":"already_reported","reports_used":1,"reports_per_month":5}""")
        val request = JSONObject("""{"url":"https://shop.example/p","page_facts":{"title":"Lamp"}}""")
        val outcome = UnfretApi(UnfretApi.DEV, key, transport).report("rt1", "junk", request, "x", JSONObject(verdict))

        assertEquals("already_reported", outcome)
        val sent = transport.sent.single()
        assertEquals("https://dev.unfret.net/v1/reports", sent.url)
        assertEquals("Bearer $key", sent.headers["Authorization"])
        val body = JSONObject(sent.body!!)
        assertEquals("rt1", body.getString("report_token"))
        assertEquals("junk", body.getString("reason"))
        assertTrue(body.getBoolean("consent"))
        assertEquals("https://shop.example/p", body.getJSONObject("request").getString("url"))
        assertEquals("careful", body.getJSONObject("verdict").getString("band"))
    }

    @Test
    fun `a refused report says why`() {
        val transport = FakeTransport(429, """{"detail":"limit","error_code":"REPORT_LIMIT"}""")
        val error = assertThrows(ApiError::class.java) {
            UnfretApi(UnfretApi.PRODUCTION, key, transport).report("rt1", "other", JSONObject(), "x", null)
        }
        assertEquals("REPORT_LIMIT", error.code)
    }

    @Test
    fun `thumbnails come only from the API's own image proxy, without the pass`() {
        val transport = FakeTransport(200, "jpeg")
        val url = "https://unfret.net/v1/images/3/thumbnail.jpg"
        assertEquals("jpeg", String(UnfretApi.thumbnail(url, UnfretApi.PRODUCTION, transport)!!))
        assertFalse("Authorization" in transport.sent.single().headers)
        assertNull(UnfretApi.thumbnail("https://cheap.shop/p.jpg", UnfretApi.PRODUCTION, transport))
        assertNull(UnfretApi.thumbnail(url, "https://evil.example", transport))
        assertEquals(1, transport.sent.size) // nothing else was fetched
    }

    @Test
    fun `a new pass is the challenge, then the pass, with no key and nothing about the phone`() {
        val transport = RoutedTransport(
            mapOf(
                "GET /v1/keys/challenge" to (200 to """{"challenge":"c.1.18.m","bits":18,"expires_at":1.0}"""),
                "POST /v1/keys" to (201 to """{"key_id":"abcdefgh23","key":"$KEY_A","recovery_code":"ufr_x","account":"key:abcdefgh23","balance_usd_micros":0,"free_checks":5}"""),
            ),
        )

        assertEquals(KeyChallenge("c.1.18.m", 18), UnfretApi.keyChallenge(UnfretApi.DEV, transport))
        val issued = UnfretApi.createKey(UnfretApi.DEV, transport, "c.1.18.m", "4711", free = true)

        assertEquals(KEY_A, issued.key)
        assertEquals(5, issued.freeChecks)
        val (get, post) = transport.sent
        assertEquals("https://dev.unfret.net/v1/keys/challenge", get.url)
        assertEquals("https://dev.unfret.net/v1/keys", post.url)
        val sent = JSONObject(post.body!!)
        assertEquals(setOf("challenge", "solution", "free"), sent.keys().asSequence().toSet())
        assertEquals("c.1.18.m", sent.getString("challenge"))
        assertEquals("4711", sent.getString("solution"))
        assertTrue(sent.getBoolean("free"))
        assertEquals(setOf("Accept-Language", "User-Agent", "Content-Type"), post.headers.keys)
        assertEquals(setOf("Accept-Language", "User-Agent"), get.headers.keys)
    }

    @Test
    fun `free checks in the balance`() {
        val credits = Credits.parse(JSONObject("""{"checks_remaining":5,"free_checks_remaining":5,"free_checks_paused":false}"""))
        assertEquals(Credits(5, null, freeLeft = 5, freePaused = false), credits)
        assertFalse(credits.quickOnly)
        // No checks, or only free ones while today's are used: only the quick check runs.
        assertTrue(Credits(0, null).quickOnly)
        assertTrue(Credits(3, null, freeLeft = 3, freePaused = true).quickOnly)
        assertFalse(Credits(4, null, freeLeft = 3, freePaused = true).quickOnly)
    }

    @Test
    fun `today's free checks used up is its own problem`() {
        val paused = ApiError(429, "FREE_CHECKS_PAUSED", "Free checks are back tomorrow.")
        assertEquals(Problem.FREE_PAUSED, Problem.of(paused))
    }
}
