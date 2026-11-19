package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassLinkTest {
    @Test
    fun `the QR code's link`() {
        val offer = PassLink.parse("https://unfret.net/pass#pass=$KEY_A", allowDev = false)!!
        assertEquals(KEY_A, offer.key)
        assertEquals(UnfretApi.PRODUCTION, offer.base)
        assertEquals("ABCDE-FGH23", offer.shownId)
        // Other fragment fields (as /check's fragment may carry) and a trailing slash are fine.
        assertEquals(offer, PassLink.parse("https://unfret.net/pass/#url=https%3A%2F%2Fshop.test%2F&pass=$KEY_A", false))
        assertEquals(offer, PassLink.parse("https://UNFRET.net/pass#pass=${KEY_A.replace("_", "%5F")}", false))
    }

    @Test
    fun `the dev deployment's link only in a debug build`() {
        assertNull(PassLink.parse("https://dev.unfret.net/pass#pass=$KEY_A", allowDev = false))
        assertEquals(UnfretApi.DEV, PassLink.parse("https://dev.unfret.net/pass#pass=$KEY_A", allowDev = true)!!.base)
    }

    @Test
    fun `malformed or missing passes`() {
        for (link in listOf(
            null,
            "",
            "https://unfret.net/pass",
            "https://unfret.net/pass#",
            "https://unfret.net/pass#pass=",
            "https://unfret.net/pass#other=$KEY_A",
            "https://unfret.net/pass#pass=${KEY_A.uppercase()}",
            "https://unfret.net/pass#pass=${KEY_A.dropLast(1)}",
            "https://unfret.net/pass#pass=${KEY_A}x",
            "https://unfret.net/pass#pass=%20$KEY_A",
            "https://unfret.net/pass#pass=ufk_abcdefgh23_" + "1".repeat(52),
            "https://unfret.net/pass#pass=%E0%A4%A",
            "https://unfret.net/pass?pass=$KEY_A",
            "not a link at all",
        )) {
            assertNull(link, PassLink.parse(link, allowDev = true))
        }
    }

    @Test
    fun `only unfret's own pass path, over HTTPS`() {
        for (link in listOf(
            "http://unfret.net/pass#pass=$KEY_A",
            "https://unfret.net/check#pass=$KEY_A",
            "https://unfret.net/passes#pass=$KEY_A",
            "https://unfret.net/pass/x#pass=$KEY_A",
            "https://evil.test/pass#pass=$KEY_A",
            "https://unfret.net.evil.test/pass#pass=$KEY_A",
            "https://unfret.net@evil.test/pass#pass=$KEY_A",
            "https://user@unfret.net/pass#pass=$KEY_A",
            "https://unfret.net:8443/pass#pass=$KEY_A",
            "intent://unfret.net/pass#pass=$KEY_A",
        )) {
            assertNull(link, PassLink.parse(link, allowDev = true))
        }
    }

    @Test
    fun `an offer never names its key as text`() {
        assertFalse(PassLink.parse("https://unfret.net/pass#pass=$KEY_A", false).toString().contains(KEY_A))
    }

    @Test
    fun `the question for the same pass, a first pass, or a switch`() {
        val offer = PassLink.Offer(KEY_A, UnfretApi.PRODUCTION)
        assertEquals(PassQuestion.Same, PassQuestion.of(offer, KEY_A, movesFreeChecks = true))
        assertEquals(PassQuestion.Use("ABCDE-FGH23"), PassQuestion.of(offer, null, movesFreeChecks = false))
        assertEquals(PassQuestion.Switch("ABCDE-FGH23", "ZYXWV-UTS23", false), PassQuestion.of(offer, KEY_B, movesFreeChecks = false))
        assertEquals(PassQuestion.Switch("ABCDE-FGH23", "ZYXWV-UTS23", true), PassQuestion.of(offer, KEY_B, movesFreeChecks = true))
    }

    private val credits = "GET /v1/credits" to (200 to """{"checks_remaining":12}""")

    @Test
    fun `a yes keeps the pass once the server knows it`() {
        val vault = KeyVault(MapStore(), XorBox())
        val transport = RoutedTransport(mapOf(credits))

        val outcome = PassTaker(vault, transport).take(PassLink.Offer(KEY_A, UnfretApi.PRODUCTION), UnfretApi.PRODUCTION)

        assertEquals(PassTaker.Outcome.Taken, outcome)
        assertEquals(KEY_A, vault.load())
        assertEquals("Bearer $KEY_A", transport.sent.single().headers["Authorization"])
    }

    @Test
    fun `a pass the server doesn't know, or no connection, changes nothing`() {
        val vault = KeyVault(MapStore(), XorBox())
        vault.save(KEY_B)
        val unknown = RoutedTransport(mapOf("GET /v1/credits" to (401 to """{"error_code":"INVALID_API_KEY","detail":"x"}""")))
        val offer = PassLink.Offer(KEY_A, UnfretApi.PRODUCTION)

        assertEquals(PassTaker.Outcome.Unknown, PassTaker(vault, unknown).take(offer, UnfretApi.PRODUCTION))
        assertEquals(PassTaker.Outcome.Failed(Problem.OFFLINE), PassTaker(vault, RoutedTransport(emptyMap())).take(offer, UnfretApi.PRODUCTION))
        assertEquals(KEY_B, vault.load())
    }

    @Test
    fun `switching from the app's own pass moves its free checks first`() {
        val vault = KeyVault(MapStore(), XorBox())
        vault.save(KEY_B, "ufr_zyxwvuts23_" + "r".repeat(52))
        val transport = RoutedTransport(mapOf(credits, "POST /v1/keys/move-free-checks" to (200 to """{"moved":3}""")))

        val outcome = PassTaker(vault, transport).take(PassLink.Offer(KEY_A, UnfretApi.PRODUCTION), UnfretApi.PRODUCTION)

        assertEquals(PassTaker.Outcome.Taken, outcome)
        val move = transport.sent.last()
        assertEquals("https://unfret.net/v1/keys/move-free-checks", move.url)
        assertEquals("Bearer $KEY_B", move.headers["Authorization"])
        assertEquals("""{"to_key_id":"abcdefgh23"}""", move.body)
        assertEquals(KEY_A, vault.load())
        assertNull(vault.recoveryCode())
    }

    @Test
    fun `a pasted pass keeps its checks, and an unreachable move keeps the current pass`() {
        val pasted = KeyVault(MapStore(), XorBox())
        pasted.save(KEY_B)
        val transport = RoutedTransport(mapOf(credits))
        assertEquals(PassTaker.Outcome.Taken, PassTaker(pasted, transport).take(PassLink.Offer(KEY_A, UnfretApi.PRODUCTION), UnfretApi.PRODUCTION))
        assertEquals(listOf("GET /v1/credits"), transport.paths())

        val own = KeyVault(MapStore(), XorBox())
        own.save(KEY_B, "ufr_zyxwvuts23_" + "r".repeat(52))
        val outcome = PassTaker(own, RoutedTransport(mapOf(credits))).take(PassLink.Offer(KEY_A, UnfretApi.PRODUCTION), UnfretApi.PRODUCTION)
        assertEquals(PassTaker.Outcome.Failed(Problem.OFFLINE), outcome)
        assertEquals(KEY_B, own.load())
        assertTrue(own.recoveryCode() != null)
    }
}
