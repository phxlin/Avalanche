package com.avalanche.app.domain

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestVerdictTest {
    private val start = YearMonth.of(2026, 9)

    // A big high-rate card and a small low-rate loan: avalanche aims extra at the card, snowball at the loan.
    private val debts = listOf(
        PlanDebt(1, "Big card", true, 10_000.0, 25.0, 250.0),
        PlanDebt(2, "Small loan", false, 1_000.0, 5.0, 50.0),
    )

    private fun plans(debts: List<PlanDebt> = this.debts, extra: Double = 300.0) = Pair(
        PayoffCalculator.simulate(debts, extra, Strategy.AVALANCHE, start = start),
        PayoffCalculator.simulate(debts, extra, Strategy.SNOWBALL, start = start),
    )

    @Test
    fun avalancheInUseSavesTheDifferenceAgainstSnowball() {
        val (avalanche, snowball) = plans()

        val verdict = interestVerdict(Strategy.AVALANCHE, avalanche, snowball)!!

        assertEquals(Strategy.AVALANCHE, verdict.chosen)
        assertEquals(Strategy.SNOWBALL, verdict.other)
        assertEquals(snowball.totalInterest - avalanche.totalInterest, verdict.saved, 1e-9)
        assertTrue(verdict.saved > 0.5)
        assertFalse(verdict.isAboutTheSame)
    }

    @Test
    fun switchingToSnowballFlipsTheVerdictToWhatItCostsMore() {
        val (avalanche, snowball) = plans()

        val onAvalanche = interestVerdict(Strategy.AVALANCHE, avalanche, snowball)!!
        val onSnowball = interestVerdict(Strategy.SNOWBALL, avalanche, snowball)!!

        // The same gap, seen from the other side: this is what makes the line change when the strategy does.
        assertEquals(Strategy.SNOWBALL, onSnowball.chosen)
        assertEquals(Strategy.AVALANCHE, onSnowball.other)
        assertEquals(-onAvalanche.saved, onSnowball.saved, 1e-9)
        assertTrue(onSnowball.saved < -0.5)
    }

    @Test
    fun twoStrategiesThatCostTheSameAreAboutTheSameFromEitherSide() {
        val (avalanche, snowball) = plans(debts = listOf(debts[0]), extra = 100.0) // one debt: same order either way

        assertTrue(interestVerdict(Strategy.AVALANCHE, avalanche, snowball)!!.isAboutTheSame)
        assertTrue(interestVerdict(Strategy.SNOWBALL, avalanche, snowball)!!.isAboutTheSame)
    }

    @Test
    fun thereIsNoVerdictWhenEitherPlanNeverFinishes() {
        val (avalanche, snowball) = plans(debts = listOf(PlanDebt(1, "Stuck", true, 10_000.0, 30.0, 10.0)), extra = 0.0)
        assertFalse(avalanche.converges)

        assertNull(interestVerdict(Strategy.AVALANCHE, avalanche, snowball))
        assertNull(interestVerdict(Strategy.SNOWBALL, avalanche, snowball))
    }
}
