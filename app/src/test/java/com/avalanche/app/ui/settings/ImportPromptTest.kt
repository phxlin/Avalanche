package com.avalanche.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPromptTest {
    @Test
    fun anEmptyAppGetsAPlainImportWithNoWarning() {
        val prompt = importPrompt(hasData = false)

        assertEquals("Import", prompt.confirmLabel)
        assertFalse(prompt.destructive)
        assertFalse(prompt.title.contains("Replace"))
    }

    @Test
    fun anAppWithDataKeepsTheReplaceWarning() {
        val prompt = importPrompt(hasData = true)

        assertEquals("Replace", prompt.confirmLabel)
        assertTrue(prompt.destructive)
        assertTrue(prompt.text.contains("replaces all debts and payments"))
    }

    @Test
    fun whileTheDebtsAreStillLoadingItErrsOnTheSideOfTheWarning() {
        assertEquals(importPrompt(hasData = true), importPrompt(hasData = null))
    }
}
