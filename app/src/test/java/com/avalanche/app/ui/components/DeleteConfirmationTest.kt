package com.avalanche.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteConfirmationTest {
    @Test
    fun thePhraseConfirmsRegardlessOfCaseOrSurroundingSpaces() {
        assertTrue(matchesConfirmPhrase("DELETE"))
        assertTrue(matchesConfirmPhrase("delete"))
        assertTrue(matchesConfirmPhrase("Delete "))
        assertTrue(matchesConfirmPhrase("  dElEtE  "))
    }

    @Test
    fun anythingElseDoesNot() {
        assertFalse(matchesConfirmPhrase(""))
        assertFalse(matchesConfirmPhrase("   "))
        assertFalse(matchesConfirmPhrase("DELET"))
        assertFalse(matchesConfirmPhrase("DELETE ALL"))
        assertFalse(matchesConfirmPhrase("de lete"))
    }

    @Test
    fun aCustomPhraseIsMatchedTheSameWay() {
        assertTrue(matchesConfirmPhrase("erase", phrase = "ERASE"))
        assertFalse(matchesConfirmPhrase("delete", phrase = "ERASE"))
    }
}
