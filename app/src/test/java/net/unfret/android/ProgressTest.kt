package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun `progress counts the checks the result counts, not every step`() {
        // A deep check of a marketplace listing: 15 steps, of which 9 are the result's "What we checked: 9 checks".
        val checks = listOf(
            "reviews", "market_price", "listing", "dark_patterns", "safety_gate", "injection", "brand_presence",
            "html_source_signals", "pricing",
        )
        val notChecks = listOf("preprocess", "classify", "review_copies", "reverse_image", "content", "triage")
        val progress = Progress()

        (notChecks.take(2) + checks + notChecks.drop(2)).forEach {
            progress.add(Step(it, it in checks))
        }

        assertEquals(15, progress.steps)
        assertEquals(9, progress.checks)
    }

    @Test
    fun `an older server's steps are counted as steps`() {
        val progress = Progress()
        listOf("classify", "listing", "triage").forEach { progress.add(Step(it, null)) }

        assertEquals(3, progress.steps)
        assertNull(progress.checks)
    }

    @Test
    fun `before the first check there are none yet`() {
        val progress = Progress()
        progress.add(Step("classify", false))

        assertEquals(0, progress.checks)
    }
}
