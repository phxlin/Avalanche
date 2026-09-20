package com.avalanche.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {
    @Test
    fun namesAreJoinedTheWayYouWouldSayThem() {
        assertEquals("", joinNames(emptyList()))
        assertEquals("Card A", joinNames(listOf("Card A")))
        assertEquals("Card A and Card B", joinNames(listOf("Card A", "Card B")))
        assertEquals("Card A, Card B and Loan", joinNames(listOf("Card A", "Card B", "Loan")))
    }

    @Test
    fun editableNumberDoesNotRoundBelowTheFieldsPrecision() {
        // Regression: prefilling a 3-decimal APR with two decimals silently rounded it on every save.
        assertEquals("22.875", editableNumber(22.875, maxDecimals = 3))
        assertEquals("22.88", editableNumber(22.875))
        assertEquals("6.5", editableNumber(6.5, maxDecimals = 3))
        assertEquals("100", editableNumber(100.0))
        assertEquals("1000", editableNumber(1000.0, maxDecimals = 3))
        assertEquals("", editableNumber(0.0))
    }

    @Test
    fun decimalInputKeepsDigitsAndOneSeparatorWithinTheLimit() {
        assertEquals("12.34", sanitizeDecimalInput("12.345"))
        assertEquals("12.345", sanitizeDecimalInput("12.3456", maxDecimals = 3))
        assertEquals("12,3", sanitizeDecimalInput("12,3"))
        assertEquals("1,23", sanitizeDecimalInput("1,2.3")) // a second separator is dropped, its digits are kept
        assertEquals("12", sanitizeDecimalInput("1a2"))
        assertEquals("12", sanitizeDecimalInput("12.", maxDecimals = 0))
        assertEquals("", sanitizeDecimalInput("abc"))
    }

    @Test
    fun parsingAcceptsEitherDecimalSeparatorAndRejectsGarbage() {
        assertEquals(12.5, parseDecimal("12,5")!!, 0.0)
        assertEquals(12.5, parseDecimal(" 12.5 ")!!, 0.0)
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("."))
        assertNull(parseDecimal("1e999"))
    }

    @Test
    fun percentagesRoundInsteadOfTruncating() {
        fun fmt(fraction: Float) = formatPercent(fraction).replace(',', '.')
        assertEquals("32%", fmt(0.31999998f))
        assertEquals("32.25%", fmt(0.3225f))
        assertEquals("0%", fmt(0f))
        assertEquals("100%", fmt(1f))
    }

    @Test
    fun aprShowsTwoDecimalsWithoutTrailingZeros() {
        assertEquals("22.9%", formatApr(22.9).replace(',', '.'))
        assertEquals("20%", formatApr(20.0))
        assertEquals("6.5%", formatApr(6.5).replace(',', '.'))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("1 month", formatDuration(1))
        assertEquals("11 months", formatDuration(11))
        assertEquals("2 years", formatDuration(24))
        assertEquals("1 yr 11 mo", formatDuration(23))
    }
}
