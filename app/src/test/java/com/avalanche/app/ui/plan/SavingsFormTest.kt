package com.avalanche.app.ui.plan

import com.avalanche.app.domain.SavingsSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SavingsFormTest {
    @Test
    fun aCompleteFormBecomesASetup() {
        val result = validateSavingsForm("2500.50", "4200", "15", "4.35")

        assertEquals(SavingsSetup(2500.50, 4200.0, 15.0, 4.35), result.setup)
        assertNull(result.balanceError)
        assertNull(result.incomeError)
        assertNull(result.percentError)
        assertNull(result.apyError)
    }

    @Test
    fun anEmptyBalanceMeansNothingSavedYetButTheOtherFieldsAreRequired() {
        assertEquals(0.0, validateSavingsForm("", "4200", "15", "4").setup!!.balance, 0.0)
        assertNull(validateSavingsForm("100", "", "15", "4").setup)
        assertNull(validateSavingsForm("100", "4200", "", "4").setup)
        assertNull(validateSavingsForm("100", "4200", "15", "").setup)
    }

    @Test
    fun emptyRequiredFieldsBlockSavingWithoutShoutingAnError() {
        val result = validateSavingsForm("", "", "", "")

        assertNull(result.setup)
        assertNull(result.incomeError)
        assertNull(result.percentError)
        assertNull(result.apyError)
    }

    @Test
    fun outOfRangeValuesGetAMessageOnTheirOwnField() {
        val result = validateSavingsForm("2000000000", "0", "150", "40")

        assertNull(result.setup)
        assertNotNull(result.balanceError)
        assertEquals("Enter more than 0", result.incomeError)
        assertEquals("Can't be more than 100%", result.percentError)
        assertEquals("Can't be more than 25%", result.apyError)
    }

    @Test
    fun aCommaWorksAsTheDecimalSeparator() {
        assertEquals(4.35, validateSavingsForm("", "4200", "15", "4,35").setup!!.apy, 0.0)
    }

    @Test
    fun zeroPercentAndZeroRateAreFine() {
        val setup = validateSavingsForm("1000", "4200", "0", "0").setup

        assertNotNull(setup)
        assertEquals(0.0, setup!!.monthlyContribution, 0.0)
    }
}
