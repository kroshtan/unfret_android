package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup rules hold exactly one file, the free checks' flag ([BackedUpFlag]), in cloud backups and device
 * transfers alike, on every Android version; never the pass ([AppSettings.FILE]). Read from the sources the APK is
 * built from (the tests run in the app module's directory).
 */
class BackupRulesTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val flagFile = BackedUpFlag.FILE + ".xml"

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File(path))
        .documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** The rules of one section, as (rule, domain, path). */
    private fun rules(section: Element) = section.children().map { Triple(it.tagName, it.getAttribute("domain"), it.getAttribute("path")) }

    private val onlyTheFlag = listOf(Triple("include", "sharedpref", "free_checks.xml"))

    @Test
    fun `Android 12 and later back up and transfer only the flag`() {
        val root = xml("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        assertEquals(listOf("cloud-backup", "device-transfer"), root.children().map { it.tagName })
        for (section in root.children()) {
            // An <include> means nothing else is backed up; no other rule widens it.
            assertEquals(section.tagName, onlyTheFlag, rules(section))
        }
    }

    @Test
    fun `Android 11 and older back up only the flag`() {
        val root = xml("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertEquals(onlyTheFlag, rules(root))
    }

    @Test
    fun `the flag's file is the one the rules name, and the pass lives elsewhere`() {
        assertEquals("free_checks.xml", flagFile)
        assertNotEquals(BackedUpFlag.FILE, AppSettings.FILE)
    }

    @Test
    fun `the manifest uses those rules`() {
        val application = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("true", application.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
        // No backup agent of its own: Auto Backup applies the rules above.
        assertEquals("", application.getAttributeNS(android, "backupAgent"))
    }

    @Test
    fun `pass links open the app for unfret_net's pass path only`() {
        val filters = xml("src/main/AndroidManifest.xml").getElementsByTagName("intent-filter")
        val viewFilters = (0 until filters.length).map { filters.item(it) as Element }.filter { filter ->
            filter.getElementsByTagName("action").let { a ->
                (0 until a.length).any { (a.item(it) as Element).getAttributeNS(android, "name") == "android.intent.action.VIEW" }
            }
        }
        val filter = viewFilters.single()
        assertEquals("true", filter.getAttributeNS(android, "autoVerify"))
        assertEquals("PassLinkActivity", (filter.parentNode as Element).getAttributeNS(android, "name").removePrefix("."))
        val data = filter.getElementsByTagName("data").let { d -> (0 until d.length).map { d.item(it) as Element } }
        val attributes = data.flatMap { element ->
            listOf("scheme", "host", "path", "pathPrefix", "pathPattern", "pathAdvancedPattern", "pathSuffix")
                .mapNotNull { name -> element.getAttributeNS(android, name).takeIf { it.isNotEmpty() }?.let { name to it } }
        }
        assertEquals(listOf("scheme" to "https", "host" to "unfret.net", "path" to "/pass"), attributes)
        assertTrue(PassLink.PATH == "/pass")
    }
}
