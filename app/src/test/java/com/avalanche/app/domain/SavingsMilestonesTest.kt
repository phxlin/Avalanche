package com.avalanche.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsMilestonesTest {
    private fun setup(balance: Double = 0.0, income: Double = 2000.0, percent: Double = 10.0, apy: Double = 0.0) =
        SavingsSetup(balance, income, percent, apy)

    @Test
    fun theMilestonesAreOneThreeSixAndTwelveMonthsOfIncome() {
        val milestones = SavingsProjector.milestones(setup(income = 3000.0))

        assertEquals(listOf(1, 3, 6, 12), milestones.map { it.months })
        assertEquals(listOf(3000.0, 9000.0, 18000.0, 36000.0), milestones.map { it.target })
    }

    @Test
    fun progressIsTheShareOfEachTargetSavedAndIsCappedAtFull() {
        val milestones = SavingsProjector.milestones(setup(balance = 6000.0, income = 2000.0))

        assertEquals(1f, milestones[0].progress, 0f) // 6,000 of 2,000: capped
        assertEquals(1f, milestones[1].progress, 0f) // exactly the 6,000 target
        assertEquals(0.5f, milestones[2].progress, 1e-6f) // 6,000 of 12,000
        assertEquals(0.25f, milestones[3].progress, 1e-6f) // 6,000 of 24,000
    }

    @Test
    fun aTargetAlreadyMetIsMarkedReachedIncludingWhenTheBalanceIsExactlyOnIt() {
        val milestones = SavingsProjector.milestones(setup(balance = 6000.0, income = 2000.0))

        assertTrue(milestones[0].reached)
        assertTrue(milestones[1].reached) // 6,000 is exactly three months
        assertFalse(milestones[2].reached)
        assertEquals(0, milestones[1].monthsUntilReached)
    }

    @Test
    fun monthsUntilCountsTheMonthsOfSavingNeededWithoutInterest() {
        // 200 a month (10% of 2,000): 12,000 needs 30 more months after the first 6,000, and 24,000 needs 90.
        val milestones = SavingsProjector.milestones(setup(balance = 6000.0, income = 2000.0, percent = 10.0, apy = 0.0))

        assertEquals(30, milestones[2].monthsUntilReached)
        assertEquals(90, milestones[3].monthsUntilReached)
    }

    @Test
    fun theMonthMatchesTheFirstPointOfTheSavingsCurveAtOrOverTheTarget() {
        val account = setup(balance = 1500.0, income = 2500.0, percent = 12.0, apy = 4.35)
        val curve = SavingsProjector.curve(account, 200)

        for (milestone in SavingsProjector.milestones(account)) {
            val expected = curve.indexOfFirst { it >= milestone.target - 0.005 }
            assertEquals("${milestone.months} months", expected, milestone.monthsUntilReached)
        }
    }

    @Test
    fun interestBringsAMilestoneForward() {
        val flat = SavingsProjector.milestones(setup(balance = 3000.0, apy = 0.0))[3].monthsUntilReached!!
        val earning = SavingsProjector.milestones(setup(balance = 3000.0, apy = 5.0))[3].monthsUntilReached!!

        assertTrue("$earning should be before $flat", earning < flat)
    }

    @Test
    fun anAccountThatNeverGrowsNeverReachesAnUnmetMilestone() {
        val milestones = SavingsProjector.milestones(setup(balance = 500.0, percent = 0.0, apy = 0.0))

        assertTrue(milestones.all { it.monthsUntilReached == null })
        assertFalse(milestones.any { it.reached })
        assertEquals(0.25f, milestones[0].progress, 1e-6f) // 500 of 2,000
    }

    @Test
    fun aMilestoneMoreThanFiftyYearsAwayIsNotReported() {
        // 1 a month (0.05% of 2,000) against a 24,000 target is far beyond the 600-month horizon.
        val far = SavingsProjector.milestones(setup(percent = 0.05, apy = 0.0))[3]

        assertNull(far.monthsUntilReached)
    }

    @Test
    fun customTiersAreSupported() {
        val milestones = SavingsProjector.milestones(setup(income = 1000.0), tiers = listOf(24))

        assertEquals(24_000.0, milestones.single().target, 0.0)
    }
}
