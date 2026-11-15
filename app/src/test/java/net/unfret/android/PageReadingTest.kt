package net.unfret.android

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReadingTest {
    /** What evaluateJavascript hands back for a script whose value is [value] (a JSON-encoded string). */
    private fun fromWebView(value: String?) = if (value == null) "null" else JSONObject.quote(value)

    @Test
    fun `the reader's states`() {
        assertEquals(PageReading.State.NotStarted, PageReading.parsePoll(fromWebView("null")))
        assertEquals(PageReading.State.Running, PageReading.parsePoll(fromWebView("""{"status":"running"}""")))
        assertEquals(PageReading.State.Gone, PageReading.parsePoll(fromWebView("""{"status":"gone"}""")))
        assertEquals(PageReading.State.Gone, PageReading.parsePoll("null"))
        assertEquals(PageReading.State.Gone, PageReading.parsePoll(null))
        assertEquals(
            PageReading.State.Failed("broken page"),
            PageReading.parsePoll(fromWebView("""{"status":"error","message":"broken page"}""")),
        )
        assertTrue(PageReading.parsePoll("{not json") is PageReading.State.Failed)
    }

    @Test
    fun `a finished reading`() {
        val state = PageReading.parsePoll(
            fromWebView("""{"status":"done","result":{"facts":{"url":"https://shop.test/p","title":"Lamp"},"text":"Lamp\n€ 20"}}"""),
        )
        state as PageReading.State.Done
        assertEquals("Lamp", state.extraction.getJSONObject("facts").getString("title"))
        assertEquals("Lamp\n€ 20", state.extraction.getString("text"))
    }

    @Test
    fun `the request is the extension's`() {
        val facts = JSONObject("""{"platform":"generic","url":"https://shop.test/p?v=1","title":"Lamp","price":19.99,"rating":4.5,"review_count":12,"reviews":[],"seller_profile":null}""")
        val body = AnalyzeRequest.fromExtraction(JSONObject().put("facts", facts).put("text", "Glow lamp\nIn stock"))

        assertEquals(setOf("url", "page_facts", "page_content"), body.keys().asSequence().toSet())
        assertEquals("https://shop.test/p?v=1", body.getString("url"))
        assertEquals("Glow lamp\nIn stock", body.getString("page_content"))
        // The facts go as the reader made them.
        val sent = (JSONTokener(body.toString()).nextValue() as JSONObject).getJSONObject("page_facts")
        assertEquals(facts.toString(), sent.toString())
        assertEquals(19.99, sent.getDouble("price"), 0.0)
        assertTrue(sent.isNull("seller_profile"))
    }

    @Test
    fun `no page text, no page_content`() {
        val body = AnalyzeRequest.fromExtraction(JSONObject("""{"facts":{"url":"https://shop.test/"},"text":""}"""))
        assertFalse(body.has("page_content"))
    }

    @Test
    fun `the text is capped like the extension's`() {
        val long = "a".repeat(AnalyzeRequest.MAX_UPLOAD_TEXT_CHARS + 500)
        val body = AnalyzeRequest.fromExtraction(JSONObject().put("facts", JSONObject().put("url", "https://shop.test/")).put("text", long))
        assertEquals(AnalyzeRequest.MAX_UPLOAD_TEXT_CHARS, body.getString("page_content").length)

        // Without cutting an emoji in half.
        val emoji = "a".repeat(AnalyzeRequest.MAX_UPLOAD_TEXT_CHARS - 1) + "😀" + "b"
        val cut = AnalyzeRequest.fromExtraction(JSONObject().put("facts", JSONObject().put("url", "https://shop.test/")).put("text", emoji))
        assertEquals(AnalyzeRequest.MAX_UPLOAD_TEXT_CHARS - 1, cut.getString("page_content").length)
    }

    @Test
    fun `a reading without facts or address isn't sent`() {
        assertThrows(IllegalArgumentException::class.java) { AnalyzeRequest.fromExtraction(JSONObject("""{"text":"x"}""")) }
        assertThrows(IllegalArgumentException::class.java) {
            AnalyzeRequest.fromExtraction(JSONObject("""{"facts":{"url":"about:blank"},"text":"x"}"""))
        }
    }
}
