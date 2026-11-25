package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unfret Dev: its own package and name, the dev server, and a key that isn't the release key. */
class DevBuildTest {
    private fun digest(path: String) = File(path).readText().trim()

    @Test
    fun `the dev key is pinned and is never the release key`() {
        val dev = digest("../docs/dev-cert.sha256")
        assertTrue(dev, Regex("^[0-9a-f]{64}$").matches(dev))
        assertNotEquals(digest("../docs/release-cert.sha256"), dev)
    }

    @Test
    fun `Unfret Dev is its own app, named so, talking to dev`() {
        val gradle = File("build.gradle.kts").readText()
        val dev = gradle.substringAfter("create(\"dev\")").substringBefore("\n        }")
        assertTrue(dev, "applicationIdSuffix = \".dev\"" in dev)
        assertTrue(dev, "buildConfigField(\"boolean\", \"DEV\", \"true\")" in dev)
        val strings = File("src/dev/res/values/strings.xml").readText()
        assertTrue("<string name=\"app_name\">Unfret Dev</string>" in strings)
        assertEquals("https://dev.unfret.net", UnfretApi.DEV)
        // This (debug) build isn't Unfret Dev.
        assertEquals(false, BuildConfig.DEV)
    }
}
