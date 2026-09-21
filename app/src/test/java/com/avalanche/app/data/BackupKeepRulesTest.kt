package com.avalanche.app.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gson reads and writes the backup by field name, and R8 renames fields in release builds unless a keep rule says
 * otherwise. A DTO without a rule exports as {"a": ..., "b": ...} in a release build and can't be read by any other
 * build. This can't run R8, but it does catch the mistake that causes it: a new DTO class with no rule.
 */
class BackupKeepRulesTest {
    // Unit tests run with the module directory (app/) as the working directory; fall back for a run from the root.
    private fun moduleFile(path: String): File = File(path).takeIf { it.exists() } ?: File("app/$path")

    /** Classes in Backup.kt that aren't read or written as JSON, so they need no rule. */
    private val notSerialized = setOf("ParsedBackup", "BackupException")

    @Test
    fun everyBackupDtoHasAKeepRule() {
        val source = moduleFile("src/main/java/com/avalanche/app/data/Backup.kt").readText()
        val rules = moduleFile("proguard-rules.pro").readText()

        val dtos = Regex("""^(?:data )?class (\w+)""", RegexOption.MULTILINE).findAll(source)
            .map { it.groupValues[1] }
            .filter { it !in notSerialized }
            .toList()

        assertTrue("found no DTOs in Backup.kt, so the pattern is out of date", dtos.size >= 5)
        for (name in dtos) {
            assertTrue(
                "proguard-rules.pro has no keep rule for com.avalanche.app.data.$name",
                Regex("""-keep class com\.avalanche\.app\.data\.$name \{ \*; \}""").containsMatchIn(rules),
            )
        }
    }
}
