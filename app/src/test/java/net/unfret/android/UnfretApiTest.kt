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
    fun `whether free deep checks are paused, with nothing about the phone`() {
        val transport = FakeTransport(200, """{"free":{"quick_checks":true,"deep_checks":true,"paused":true,"checks_per_pass":0}}""")
        assertEquals(FreeChecks(paused = true), UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).freeChecks())
        val request = transport.sent.single()
        assertEquals("https://unfret.net/v1/pricing", request.url)
        assertTrue(request.headers.getValue("User-Agent").startsWith("Unfret-Android/"))
        assertEquals(setOf("Accept-Language", "User-Agent"), request.headers.keys)
        assertEquals(FreeChecks(paused = false), FreeChecks.parse(null)) // an older server
    }

    @Test
    fun `the Play build asks the server to leave donations out`() {
        val transport = FakeTransport(200, """{"free":{"paused":false}}""")
        UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = true).freeChecks()
        assertEquals("off", transport.sent.single().headers["X-Unfret-Donate"])
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

        val result = UnfretApi(UnfretApi.DEV, transport, donateOff = false).analyze(body) { steps += it }

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
        val transport = FakeTransport(
            200,
            "event: error\ndata: {\"status\": 429, \"error_code\": \"FREE_CHECKS_PAUSED\", \"detail\": \"Paused\", \"metadata\": {\"retry_after_s\": 600}}\n\n",
        )
        val error = assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).analyze(JSONObject()) }
        assertEquals(Problem.PAUSED, Problem.of(error))
        assertEquals(600, error.retryAfterS)
    }

    @Test
    fun `a stream that ends without a result`() {
        val transport = FakeTransport(200, "event: step\ndata: {\"skill\": \"classify\"}\n\n")
        val error = assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, transport, donateOff = false).analyze(JSONObject()) }
        assertEquals("STREAM_ENDED", error.code)
        assertEquals(Problem.FAILED, Problem.of(error))
    }

    @Test
    fun `error answers, in plain words`() {
        fun problem(status: Int, body: String): Problem = Problem.of(
            assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, FakeTransport(status, body), donateOff = false).freeChecks() },
        )
        assertEquals(Problem.PAUSED, problem(429, """{"detail":"x","error_code":"FREE_CHECKS_PAUSED"}"""))
        assertEquals(Problem.TOO_MANY, problem(429, """{"detail":"x","error_code":"TOO_MANY_REQUESTS","metadata":{"retry_after_s":90}}"""))
        assertEquals(Problem.SERVICE_OFF, problem(503, """{"detail":"x","error_code":"SERVICE_UNAVAILABLE"}"""))
        assertEquals(Problem.SERVICE_OFF, problem(503, "<html>down</html>"))
        assertEquals(Problem.FAILED, problem(500, ""))
        assertEquals(Problem.OFFLINE, Problem.of(IOException("no route")))
    }

    @Test
    fun `only Unfret's servers`() {
        assertThrows(IllegalArgumentException::class.java) { UnfretApi("https://evil.example", FakeTransport(200, "{}"), false) }
        assertThrows(IllegalArgumentException::class.java) { UnfretApi("http://unfret.net", FakeTransport(200, "{}"), false) }
    }

    @Test
    fun `thumbnails come only from the API's own image proxy`() {
        val transport = FakeTransport(200, "jpeg")
        val url = "https://unfret.net/v1/images/3/thumbnail.jpg"
        assertEquals("jpeg", String(UnfretApi.thumbnail(url, UnfretApi.PRODUCTION, transport)!!))
        assertFalse("Authorization" in transport.sent.single().headers)
        assertNull(UnfretApi.thumbnail("https://cheap.shop/p.jpg", UnfretApi.PRODUCTION, transport))
        assertNull(UnfretApi.thumbnail(url, "https://evil.example", transport))
        assertEquals(1, transport.sent.size) // nothing else was fetched
    }
}
