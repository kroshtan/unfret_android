package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Neither build sells anything. The Google Play build has no donate or support link and asks the server to leave
 * donations out; the direct download links to `/donate` and `/support`. The strings are read from the sources the
 * builds are made from (the tests run in the app module's directory).
 */
class StorefrontTest {
    private val direct = Storefront(play = false)
    private val play = Storefront(play = true)

    private val runNow = DeepCheck("A deep check also looks at how old this shop is.", "Deep checks are free.", DeepCheck.Action.RUN_DEEP, "Run a deep check", false)
    private val donate = DeepCheck(
        "A deep check also looks at how old this shop is.",
        "Free deep checks are paused until tomorrow: they run on donations.",
        DeepCheck.Action.DONATE,
        "Support Unfret",
        false,
    )

    @Test
    fun `the direct download links to donations, the Play build doesn't and asks the server not to`() {
        assertTrue(direct.linksDonations)
        assertFalse(direct.donateOff)
        assertFalse(play.linksDonations)
        assertTrue(play.donateOff)
        assertEquals("https://unfret.net/donate", direct.donateUrl("https://unfret.net"))
        assertEquals("https://dev.unfret.net/support", direct.supportUrl("https://dev.unfret.net/"))
        assertEquals("https://unfret.net/?app=play", play.homeUrl("https://unfret.net"))
        assertEquals("Support Unfret", direct.supportLabel)
        assertEquals("", play.supportLabel)
        assertEquals(donate, direct.deepHint(donate))
        assertEquals(runNow, direct.deepHint(runNow))
        assertTrue(direct.mayOpen("https://unfret.net/donate"))
    }

    @Test
    fun `the Play build's deep-check suggestion has no donate button or words`() {
        assertNull(play.deepHint(null))
        assertEquals(runNow, play.deepHint(runNow))
        val shown = play.deepHint(donate)!!
        assertEquals(DeepCheck.Action.NONE, shown.action)
        assertEquals("", shown.actionLabel)
        assertEquals("", shown.cost)
        assertEquals(donate.text, shown.text)
    }

    @Test
    fun `the Play build's server sentences say nothing about buying or donating`() {
        assertEquals("Deep checks are free.", play.serverWords("Deep checks are free."))
        assertNull(play.serverWords("Free deep checks run on donations."))
        assertNull(play.serverWords("Buy more on unfret.net."))
        assertNull(play.serverWords(null))
        assertEquals("Free deep checks run on donations.", direct.serverWords("Free deep checks run on donations."))
    }

    @Test
    fun `the Play build opens only Unfret's informational pages, as their Play variant, and the home page`() {
        for (url in listOf(
            "https://unfret.net/privacy?app=play",
            "https://unfret.net/privacy/?app=play",
            "https://unfret.net/objection?app=play",
            "https://unfret.net/about?app=play",
            "https://unfret.net/terms?app=play",
            "https://dev.unfret.net/privacy?app=play",
            "https://unfret.net/?app=play",
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
            "https://unfret.net/donate",
            "https://unfret.net/support",
            "https://unfret.net/donate?app=play",
            "https://unfret.net/?next=/donate",
            "https://UNFRET.net/money",
            "https://dev.unfret.net/checkout",
            "https://unfret.net",
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
        assertTrue(play.mayOpen(play.homeUrl("https://unfret.net")))
    }

    @Test
    fun `words that sell, and words about donating`() {
        for (text in listOf("from $4.99", "4,99 €", "€5", "Buy checks", "deep checks come in packs", "the price", "Get checks", "/checkout")) {
            assertTrue(text, Storefront.mentionsBuying(text))
        }
        // A format argument isn't a currency.
        for (text in listOf("%1\$d checks", "Before you pay on a marketplace", "Checks are free.")) {
            assertFalse(text, Storefront.mentionsBuying(text))
        }
        for (text in listOf("Free checks run on donations.", "Donate", "Support Unfret", "https://unfret.net/donate", "unfret.net/support")) {
            assertTrue(text, Storefront.mentionsDonating(text))
        }
        assertFalse(Storefront.mentionsDonating("Deep checks are free. More info at unfret.net"))
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
    fun `no build has words that sell, and the Play build speaks of donations only in its pause line, exactly`() {
        val main = strings("src/main/res/values/strings.xml")
        val overrides = strings("src/play/res/values/strings.xml")
        // An override of a string that no longer exists would leave the new one, with its words, in the Play build.
        assertEquals(emptySet<String>(), overrides.keys - main.keys)
        assertEquals(emptySet<String>(), main.filter { (_, texts) -> texts.any(Storefront::mentionsBuying) }.keys)
        val playBuild = main + overrides
        assertEquals(emptySet<String>(), playBuild.filter { (_, texts) -> texts.any(Storefront::mentionsBuying) }.keys)
        val donating = playBuild.filter { (_, texts) -> texts.any(Storefront::mentionsDonating) }.keys
        assertEquals(setOf("paused_note"), donating)
        assertEquals(
            listOf("Free checks run on donations. Quick checks still work; deep checks are back tomorrow. More info at unfret.net"),
            playBuild.getValue("paused_note"),
        )
    }
}
