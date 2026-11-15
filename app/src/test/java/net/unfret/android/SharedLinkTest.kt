package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinkTest {
    @Test
    fun `a bare link from a browser`() {
        assertEquals(
            "https://www.amazon.de/dp/B0TEST1234?th=1",
            SharedLink.fromSharedText("https://www.amazon.de/dp/B0TEST1234?th=1"),
        )
    }

    @Test
    fun `a link inside a marketplace app's sentence`() {
        assertEquals(
            "https://www.marktplaats.nl/v/fietsen/m2187654321-gazelle",
            SharedLink.fromSharedText("Kijk wat ik gevonden heb op Marktplaats: https://www.marktplaats.nl/v/fietsen/m2187654321-gazelle"),
        )
    }

    @Test
    fun `the first of several links, without the sentence's punctuation`() {
        assertEquals(
            "https://www.vinted.nl/items/5123456789-winterjas",
            SharedLink.fromSharedText("Look at this (https://www.vinted.nl/items/5123456789-winterjas). Or https://example.com/b!"),
        )
        assertEquals("https://shop.test/p/lamp", SharedLink.fromSharedText("\"https://shop.test/p/lamp\","))
    }

    @Test
    fun `brackets that belong to the link stay`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Lamp_(device)",
            SharedLink.fromSharedText("https://en.wikipedia.org/wiki/Lamp_(device)"),
        )
    }

    @Test
    fun `a host and path without a scheme`() {
        assertEquals("https://www.bol.com/nl/nl/p/koffie/9300000/", SharedLink.fromSharedText("see www.bol.com/nl/nl/p/koffie/9300000/ now"))
    }

    @Test
    fun `plain http is opened over https`() {
        assertEquals("https://old-shop.example/item?id=3", SharedLink.fromSharedText("HTTP://old-shop.example/item?id=3"))
    }

    @Test
    fun `no link at all`() {
        assertNull(SharedLink.fromSharedText(null))
        assertNull(SharedLink.fromSharedText("   "))
        assertNull(SharedLink.fromSharedText("Call me about the bike tomorrow"))
        assertNull(SharedLink.fromSharedText("mail jane@example.com/x"))
        assertNull(SharedLink.fromSharedText("ftp://files.example.com/a"))
    }

    @Test
    fun `Unfret's own pages can't be checked`() {
        assertFalse(SharedLink.isCheckable("https://unfret.net/checkout", UnfretApi.PRODUCTION))
        assertFalse(SharedLink.isCheckable("https://UNFRET.net/r/abc", UnfretApi.PRODUCTION))
        assertTrue(SharedLink.isCheckable("https://shop.test/p/1", UnfretApi.PRODUCTION))
        assertTrue(SharedLink.isCheckable("https://unfret.net.evil.example/p/1", UnfretApi.PRODUCTION))
        assertFalse(SharedLink.isCheckable("javascript:alert(1)", UnfretApi.PRODUCTION))
    }
}
