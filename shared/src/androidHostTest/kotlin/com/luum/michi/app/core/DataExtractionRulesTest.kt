package com.luum.michi.app.core

import com.luum.michi.app.core.auth.repository.tokenPrefsName
import com.luum.michi.app.core.media.domain.ProviderId
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Every provider's token file must be excluded from Android cloud backups and
 * device transfers, as docs/privacy.md promises. The rules live in the app
 * module, so the test reads the XML from the source tree.
 */
class DataExtractionRulesTest {

    private val providers = listOf(ProviderId.AniList, ProviderId.Mal)

    @Test
    fun everyTokenFileIsExcludedFromBackupAndTransfer() {
        val rules = rulesFile()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = document.getElementsByTagName(section).item(0) as? Element
                ?: error("$section is missing from ${rules.path}")
            val excludes = node.getElementsByTagName("exclude")
            val excluded = (0 until excludes.length)
                .map { excludes.item(it) as Element }
                .filter { it.getAttribute("domain") == "sharedpref" }
                .map { it.getAttribute("path") }
                .toSet()
            for (provider in providers) {
                val file = "${tokenPrefsName(provider)}.xml"
                assertTrue(file in excluded, "$file is not excluded in <$section>")
            }
        }
    }

    private fun rulesFile(): File {
        val relative = "androidApp/src/main/res/xml/data_extraction_rules.xml"
        // Gradle runs host tests from the module directory; IDE runs may use the root.
        return listOf(File("../$relative"), File(relative)).firstOrNull { it.isFile }
            ?: error("Cannot find $relative from ${File(".").absolutePath}")
    }
}
