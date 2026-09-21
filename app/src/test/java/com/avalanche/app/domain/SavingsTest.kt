package com.avalanche.app.domain

import java.time.YearMonth
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsTest {
    private val start = YearMonth.of(2026, 9)

    private fun setup(balance: Double = 0.0, income: Double = 4000.0, percent: Double = 10.0, apy: Double = 0.0) =
        SavingsSetup(balance, income, percent, apy)

    /** One debt of 1,000 paid 100 a month at 0% APR, so the balance curve is 1000, 900, ... 0 over ten months. */
    private fun tenMonthPlan(balance: Double = 1000.0, min: Double = 100.0, apr: Double = 0.0) =
        PayoffCalculator.simulate(listOf(PlanDebt(1, "Card", true, balance, apr, min)), 0.0, Strategy.AVALANCHE, start = start)

    // ---------- the curve ----------

    @Test
    fun theMonthlyContributionIsTheSharedPartOfIncome() {
        assertEquals(400.0, setup(income = 4000.0, percent = 10.0).monthlyContribution, 1e-9)
        assertEquals(0.0, setup(percent = 0.0).monthlyContribution, 0.0)
    }

    @Test
    fun theMonthlyRateCompoundsBackToTheStatedApy() {
        for (apy in listOf(0.0, 0.5, 4.5, 12.0, 25.0)) {
            val yearly = (1.0 + SavingsProjector.monthlyRate(apy)).pow(12)
            assertEquals("APY $apy", 1.0 + apy / 100.0, yearly, 1e-12)
        }
    }

    @Test
    fun withoutInterestTheBalanceGrowsByTheSameAmountEveryMonth() {
        val curve = SavingsProjector.curve(setup(balance = 500.0, income = 2000.0, percent = 10.0, apy = 0.0), 3)

        assertEquals(listOf(500.0, 700.0, 900.0, 1100.0), curve)
    }

    @Test
    fun withoutSavingTheBalanceGrowsByExactlyTheApyInAYear() {
        val curve = SavingsProjector.curve(setup(balance = 10_000.0, percent = 0.0, apy = 4.5), 12)

        assertEquals(10_450.0, curve.last(), 1e-6)
    }

    @Test
    fun aMonthEarnsInterestOnTheOpeningBalanceBeforeTheDepositLands() {
        val rate = SavingsProjector.monthlyRate(6.0)
        val curve = SavingsProjector.curve(setup(balance = 1000.0, income = 1000.0, percent = 10.0, apy = 6.0), 1)

        // The 100 saved this month has not earned anything yet.
        assertEquals(1000.0 * (1 + rate) + 100.0, curve[1], 1e-9)
    }

    @Test
    fun theCurveStartsAtTheBalanceAndHasOnePointPerMonth() {
        val curve = SavingsProjector.curve(setup(balance = 250.0), 24)

        assertEquals(25, curve.size)
        assertEquals(250.0, curve.first(), 0.0)
        assertEquals(listOf(250.0), SavingsProjector.curve(setup(balance = 250.0), 0))
        assertEquals(listOf(250.0), SavingsProjector.curve(setup(balance = 250.0), -3))
    }

    // ---------- next to a payoff plan ----------

    @Test
    fun savingsPassTheRemainingDebtInTheFirstMonthTheyReachIt() {
        val plan = tenMonthPlan()
        val projection = SavingsProjector.project(setup(income = 2000.0, percent = 10.0), plan, monthlyDebtPayment = 100.0)

        // Saving 200 a month against 1000, 900, 800, ...: 600 < 700 after three months, 800 >= 600 after four.
        assertEquals(4, projection.crossoverMonthIndex)
        assertFalse(projection.alreadyAhead)
        assertEquals(YearMonth.of(2026, 12), plan.monthAt(projection.crossoverMonthIndex!!))
        assertEquals(11, projection.curve.size)
    }

    @Test
    fun theSavingsAndInterestWhenDebtFreeAreReported() {
        val plan = tenMonthPlan()
        val flat = SavingsProjector.project(setup(balance = 300.0, income = 2000.0, percent = 10.0, apy = 0.0), plan, 100.0)

        assertEquals(10, plan.monthsToDebtFree)
        assertEquals(2300.0, flat.savedAtDebtFree!!, 1e-9)
        assertEquals(0.0, flat.interestEarnedAtDebtFree!!, 1e-9)

        val earning = SavingsProjector.project(setup(balance = 300.0, income = 2000.0, percent = 10.0, apy = 5.0), plan, 100.0)
        assertTrue(earning.savedAtDebtFree!! > 2300.0)
        assertEquals(earning.savedAtDebtFree!! - 2300.0, earning.interestEarnedAtDebtFree!!, 1e-9)
    }

    @Test
    fun savingsThatAlreadyCoverTheDebtAreFlaggedInsteadOfGivenACrossoverMonth() {
        val projection = SavingsProjector.project(setup(balance = 5000.0), tenMonthPlan(), 100.0)

        assertTrue(projection.alreadyAhead)
        assertNull(projection.crossoverMonthIndex)
    }

    @Test
    fun anEmptyAccountThatNeverGrowsNeverPassesTheDebt() {
        val projection = SavingsProjector.project(setup(balance = 0.0, percent = 0.0, apy = 0.0), tenMonthPlan(), 100.0)

        assertFalse(projection.alreadyAhead)
        assertNull(projection.crossoverMonthIndex) // 0 >= 0 at the end doesn't count as passing anything
        assertEquals(0.0, projection.savedAtDebtFree!!, 0.0)
    }

    @Test
    fun aPlanWithNoPayoffDateStillGetsASetupButNoCurve() {
        // 30% APR on 10,000 is 250 a month in interest against a 10 minimum: it never clears.
        val plan = tenMonthPlan(balance = 10_000.0, min = 10.0, apr = 30.0)
        assertFalse(plan.converges)

        val projection = SavingsProjector.project(setup(balance = 500.0), plan, 10.0)

        assertTrue(projection.curve.isEmpty())
        assertNull(projection.savedAtDebtFree)
        assertNull(projection.interestEarnedAtDebtFree)
        assertNull(projection.crossoverMonthIndex)
        assertFalse(projection.alreadyAhead)
    }

    @Test
    fun theDebtPaymentShareIsWhatGoesToDebtOverIncome() {
        val projection = SavingsProjector.project(setup(income = 2000.0), tenMonthPlan(), monthlyDebtPayment = 500.0)

        assertEquals(0.25, projection.debtPaymentShare, 1e-12)
    }

    // ---------- limits ----------

    @Test
    fun validatedAcceptsTheEdgesAndRejectsAnythingOutOfRange() {
        assertNotNull(SavingsLimits.validated(0.0, 1.0, 0.0, 0.0))
        assertNotNull(SavingsLimits.validated(SavingsLimits.MAX_BALANCE, SavingsLimits.MAX_INCOME, 100.0, 25.0))

        assertNull(SavingsLimits.validated(null, 1.0, 10.0, 4.0))
        assertNull(SavingsLimits.validated(0.0, null, 10.0, 4.0))
        assertNull(SavingsLimits.validated(0.0, 1.0, null, 4.0))
        assertNull(SavingsLimits.validated(0.0, 1.0, 10.0, null))
        assertNull(SavingsLimits.validated(-1.0, 1000.0, 10.0, 4.0))
        assertNull(SavingsLimits.validated(0.0, 0.0, 10.0, 4.0)) // income has to be more than 0
        assertNull(SavingsLimits.validated(0.0, 1000.0, 100.01, 4.0))
        assertNull(SavingsLimits.validated(0.0, 1000.0, 10.0, 25.01))
        assertNull(SavingsLimits.validated(Double.NaN, 1000.0, 10.0, 4.0))
        assertNull(SavingsLimits.validated(0.0, Double.POSITIVE_INFINITY, 10.0, 4.0))
    }
}
