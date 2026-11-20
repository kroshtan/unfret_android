package net.unfret.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Google Play build sells nothing and points nowhere to buy; the direct download keeps "Get checks" and the
 * checkout. The strings are read from the sources the builds are made from (the tests run in the app module's
 * directory).
 */
class StorefrontTest {
    private val direct = Storefront(play = false)
    private val play = Storefront(play = true)

    private val runNow = DeepCheck("A deep check also looks at how old this shop is.", "Uses 1 check (you have 3).", true, "Run a deep check", false)
    private val getChecks = DeepCheck(
        "A deep check also looks at how old this shop is.",
        "Deep checks come in packs, from $4.99 for 25.",
        false,
        "Get checks",
        false,
    )

    @Test
    fun `the direct download sells checks on the website`() {
        assertTrue(direct.sellsChecks)
        assertFalse(direct.redeemsInApp)
        assertEquals(getChecks, direct.deepHint(getChecks))
        assertEquals(runNow, direct.deepHint(runNow))
        assertNull(direct.noChecksLine(Credits(0, null)))
        assertTrue(direct.mayOpen("https://unfret.net/checkout?mode=topup"))
        assertEquals("Uses 1 of 25 checks, from \$4.99.", direct.serverWords("Uses 1 of 25 checks, from \$4.99."))
    }

    @Test
    fun `the Play build has no checkout and redeems gift codes in the app`() {
        assertFalse(play.sellsChecks)
        assertTrue(play.redeemsInApp)
    }

    @Test
    fun `the Play build suggests a deep check only when it can run now, without a price`() {
        assertNull(play.deepHint(null))
        assertNull(play.deepHint(getChecks))
        assertEquals(runNow, play.deepHint(runNow))
        val priced = runNow.copy(cost = "Free deep checks are back at 00:00 UTC. Deep checks also come in packs, from $4.99 for 25.")
        assertEquals(runNow.copy(cost = ""), play.deepHint(priced))
        assertNull(play.deepHint(runNow.copy(actionLabel = "Get checks")))
    }

    @Test
    fun `the Play build's balance with no checks left says quick checks are free`() {
        assertEquals(R.string.no_deep_checks_left, play.noChecksLine(Credits(0, null)))
        assertNull(play.noChecksLine(Credits(3, null)))
    }

    @Test
    fun `the Play build's server sentences say nothing about buying`() {
        assertEquals("This check used one of your checks.", play.serverWords("This check used one of your checks."))
        assertNull(play.serverWords("This check used one of your checks. Buy more on unfret.net."))
        assertNull(play.serverWords(null))
    }

    @Test
    fun `the Play build opens only Unfret's informational pages, as their Play variant`() {
        for (url in listOf(
            "https://unfret.net/privacy?app=play",
            "https://unfret.net/privacy/?app=play",
            "https://unfret.net/objection?app=play",
            "https://unfret.net/about?app=play",
            "https://unfret.net/terms?app=play",
            "https://dev.unfret.net/privacy?app=play",
            "https://example.com/some/shop",
        )) {
            assertTrue(url, play.mayOpen(url))
        }
        for (url in listOf(
            "https://unfret.net/privacy",
            "https://unfret.net/objection",
            "https://unfret.net/terms?app=other",
            "https://unfret.net/about?app=play&next=/checkout",
            "https://unfret.net/checkout?app=play",
            "https://unfret.net/money?app=play",
            "https://unfret.net/checkout",
            "https://unfret.net/checkout?mode=topup",
            "https://unfret.net/checkout?mode=code",
            "https://UNFRET.net/money",
            "https://dev.unfret.net/checkout",
            "https://unfret.net/",
            "not a url with spaces",
        )) {
            assertFalse(url, play.mayOpen(url))
        }
    }

    @Test
    fun `the Play build links the Play variant of the informational pages, the direct download the normal pages`() {
        for (path in Storefront.INFO_PAGES) {
            val variant = play.infoPage("https://unfret.net", path)
            assertEquals("https://unfret.net$path?app=play", variant)
            assertTrue(variant, play.mayOpen(variant))
            assertEquals("https://unfret.net$path", direct.infoPage("https://unfret.net/", path))
        }
        assertEquals("https://dev.unfret.net/objection?app=play", play.infoPage("https://dev.unfret.net/", "/objection"))
    }

    @Test
    fun `words that sell`() {
        for (text in listOf("from $4.99", "4,99 €", "€5", "Buy checks", "deep checks come in packs", "the price", "Get checks", "/checkout")) {
            assertTrue(text, Storefront.mentionsBuying(text))
        }
        // A format argument isn't a currency.
        for (text in listOf("Unfret pass %1\$s on this phone", "%1\$d checks", "Before you pay on a marketplace", "Gift code")) {
            assertFalse(text, Storefront.mentionsBuying(text))
        }
    }

    private fun strings(path: String): Map<String, List<String>> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        val elements = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        return elements.associate { element ->
            val items = element.getElementsByTagName("item")
            val texts = if (items.length == 0) listOf(element.textContent) else (0 until items.length).map { items.item(it).textContent }
            element.getAttribute("name") to texts
        }
    }

    @Test
    fun `every string of the Play build is without words about buying`() {
        val main = strings("src/main/res/values/strings.xml")
        val overrides = strings("src/play/res/values/strings.xml")
        // An override of a string that no longer exists would leave the new one, with its words, in the Play build.
        assertEquals(emptySet<String>(), overrides.keys - main.keys)
        val playBuild = main + overrides
        val selling = playBuild.filter { (_, texts) -> texts.any(Storefront::mentionsBuying) }.keys
        assertEquals(emptySet<String>(), selling)
        // The direct download's own words are about buying on unfret.net, so the overrides are needed.
        assertTrue(main.getValue("get_checks").any(Storefront::mentionsBuying))
    }

    private fun error(status: Int, code: String) = ApiError(status, code, "the server's words")

    @Test
    fun `a refused gift code in the app's words`() {
        assertEquals(R.string.gift_code_invalid, giftCodeRefusal(error(400, "INVALID_INVITE")))
        assertEquals(R.string.gift_code_unknown, giftCodeRefusal(error(404, "UNKNOWN_INVITE")))
        assertEquals(R.string.gift_code_used, giftCodeRefusal(error(409, "INVITE_USED")))
        assertEquals(R.string.gift_code_own, giftCodeRefusal(error(403, "INVITE_OWN_PACK")))
        assertEquals(R.string.gift_code_monthly, giftCodeRefusal(error(429, "INVITE_MONTHLY_LIMIT")))
        assertEquals(R.string.gift_code_ended, giftCodeRefusal(error(410, "INVITE_ENDED")))
        assertEquals(R.string.gift_code_too_many, giftCodeRefusal(error(429, "TOO_MANY_REQUESTS")))
        // Anything else is a Problem: a pass Unfret doesn't know, the service off, no connection.
        assertNull(giftCodeRefusal(error(401, "INVALID_API_KEY")))
        assertNull(giftCodeRefusal(error(503, "SERVICE_UNAVAILABLE")))
        assertNull(giftCodeRefusal(IOException("offline")))
    }

    private class FakeTransport(private val status: Int, private val body: String) : Transport {
        val sent = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): HttpResponse {
            sent += request
            return HttpResponse(status, BufferedReader(StringReader(body)))
        }
    }

    private val key = "ufk_abcdefgh23_" + "c".repeat(52)

    @Test
    fun `a gift code is redeemed on the pass in use`() {
        val transport = FakeTransport(200, """{"checks_added":5,"usd_micros_added":1000000,"balance_usd_micros":1400000,"checks_remaining":7}""")
        assertEquals(5, UnfretApi(UnfretApi.PRODUCTION, key, transport).redeemGiftCode("  ufi_abcd-efgh \n"))
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://unfret.net/v1/invites/redeem", request.url)
        assertEquals("Bearer $key", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("ufi_abcd-efgh", JSONObject(request.body!!).getString("code"))
    }

    @Test
    fun `a refused gift code is an ApiError with its code`() {
        val transport = FakeTransport(409, """{"detail":"This gift code has already been used.","error_code":"INVITE_USED"}""")
        val error = assertThrows(ApiError::class.java) { UnfretApi(UnfretApi.PRODUCTION, key, transport).redeemGiftCode("ufi_x") }
        assertEquals(409, error.status)
        assertEquals("INVITE_USED", error.code)
    }
}
