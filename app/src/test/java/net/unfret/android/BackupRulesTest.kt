package net.unfret.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Nothing goes into a backup or a device transfer, on any Android version, and no link opens the app (pass links are
 * gone with the pass). Read from the sources the APK is built from (the tests run in the app module's directory).
 */
class BackupRulesTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File(path))
        .documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** The rules of one section, as (rule, domain, path). */
    private fun rules(section: Element) = section.children().map { Triple(it.tagName, it.getAttribute("domain"), it.getAttribute("path")) }

    private val nothing = listOf("root", "file", "database", "sharedpref", "external").map { Triple("exclude", it, ".") }

    @Test
    fun `Android 12 and later back up and transfer nothing`() {
        val root = xml("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        assertEquals(listOf("cloud-backup", "device-transfer"), root.children().map { it.tagName })
        for (section in root.children()) assertEquals(section.tagName, nothing, rules(section))
    }

    @Test
    fun `Android 11 and older back up nothing`() {
        val root = xml("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertEquals(nothing, rules(root))
    }

    @Test
    fun `the manifest turns backup off and uses those rules`() {
        val application = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("", application.getAttributeNS(android, "backupAgent"))
    }

    @Test
    fun `no link opens the app`() {
        for (manifest in listOf("src/main/AndroidManifest.xml", "src/debug/AndroidManifest.xml")) {
            if (!File(manifest).exists()) continue
            val actions = xml(manifest).getElementsByTagName("action")
            val names = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(android, "name") }
            assertFalse(manifest, "android.intent.action.VIEW" in names)
        }
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.intent.action.SEND"))
    }

    @Test
    fun `an older version's pass is deleted once, even when a step fails`() {
        val done = mutableListOf<String>()
        var forgotten = false
        val forget = {
            OldPass.forget(
                done = { forgotten },
                removeEntries = { done += "entries"; forgotten = true },
                deleteKey = { done += "key"; throw IllegalStateException("no such entry") },
                deleteFlagFile = { done += "flag" },
            )
        }
        forget()
        forget()
        assertEquals(listOf("key", "flag", "entries"), done)
        assertTrue("api_key_sealed" in OldPass.ENTRIES && "recovery_code_sealed" in OldPass.ENTRIES)
    }
}
