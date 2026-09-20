package com.avalanche.app.domain

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayoffCalculatorTest {
    private val start = YearMonth.of(2026, 1)

    private fun debt(id: Long, balance: Double, apr: Double, min: Double, revolving: Boolean = false, paid: Boolean = false) =
        PlanDebt(id, "Debt $id", revolving, balance, apr, min, alreadyPaidThisMonth = if (paid) min else null)

    @Test
    fun singleDebtMatchesAmortizationFormula() {
        // 1000 @ 12% APR, 100/month: n = -ln(1 - rB/P) / ln(1 + r) = 10.59 -> 11 payments.
        val plan = PayoffCalculator.simulate(listOf(debt(1, 1000.0, 12.0, 100.0)), 0.0, Strategy.AVALANCHE, start = start)
        assertEquals(11, plan.monthsToDebtFree)
        assertEquals(YearMonth.of(2026, 11), plan.debtFreeMonth)
        assertTrue("interest ${plan.totalInterest}", plan.totalInterest in 55.0..65.0)
        assertEquals(plan.startBalance + plan.totalInterest, plan.totalPaid, 0.05)
        assertEquals(0.0, plan.months.last().endBalance, 0.0)
    }

    @Test
    fun avalancheAimsExtraAtHighestApr() {
        val debts = listOf(debt(1, 500.0, 5.0, 25.0), debt(2, 5000.0, 24.0, 100.0))
        val plan = PayoffCalculator.simulate(debts, 200.0, Strategy.AVALANCHE, start = start)
        assertEquals(listOf(2L, 1L), plan.priorityOrder)
        val first = plan.months.first().debts.associateBy { it.debtId }
        assertEquals(200.0, first.getValue(2).extraPaid, 0.001)
        assertEquals(0.0, first.getValue(1).extraPaid, 0.001)
    }

    @Test
    fun payoffDateOrderListsWhoFinishesFirstNotWhoGetsTheExtra() {
        // The big 24% card gets the extra money first, but the small loan's own minimum clears it much sooner.
        val debts = listOf(debt(1, 5000.0, 24.0, 100.0, revolving = true), debt(2, 300.0, 5.0, 100.0))
        val plan = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, start = start)

        assertEquals(listOf(1L, 2L), plan.priorityOrder)
        assertEquals(listOf(2L, 1L), plan.payoffDateOrder())
        assertTrue(plan.payoffMonthIndex.getValue(2L) < plan.payoffMonthIndex.getValue(1L))
    }

    @Test
    fun payoffDateOrderKeepsPriorityOrderForDebtsClearedInTheSameMonth() {
        val debts = listOf(debt(1, 100.0, 0.0, 100.0), debt(2, 100.0, 0.0, 100.0), debt(3, 100.0, 0.0, 100.0))
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.SNOWBALL, start = start)

        assertEquals(1, plan.payoffMonthIndex.getValue(1L))
        assertEquals(plan.priorityOrder, plan.payoffDateOrder())
    }

    @Test
    fun payoffDateOrderPutsDebtsThePlanNeverClearsLast() {
        // Debt 1 has the higher APR (so it comes first in priority) but its minimum can't cover the interest.
        val debts = listOf(debt(1, 10000.0, 30.0, 50.0), debt(2, 100.0, 0.0, 50.0))
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(listOf(1L, 2L), plan.priorityOrder)
        assertEquals(listOf(2L, 1L), plan.payoffDateOrder())
    }

    @Test
    fun balanceGapBetweenStrategiesIsSmallComparedWithTheBalance() {
        val debts = listOf(
            debt(1, 800.0, 6.0, 30.0),
            debt(2, 4200.0, 22.9, 110.0, revolving = true),
            debt(3, 12000.0, 7.5, 240.0),
            debt(4, 2500.0, 26.0, 75.0, revolving = true),
        )
        val a = PayoffCalculator.simulate(debts, 150.0, Strategy.AVALANCHE, start = start)
        val s = PayoffCalculator.simulate(debts, 150.0, Strategy.SNOWBALL, start = start)

        val gap = a.maxBalanceGap(s)

        assertTrue("gap $gap", gap > 0.0)
        assertTrue("gap $gap of ${a.startBalance}", gap < 0.05 * a.startBalance)
        assertEquals(gap, s.maxBalanceGap(a), 0.0001)
    }

    @Test
    fun balanceGapOfAPlanWithItselfIsZeroAndAFinishedPlanCountsAsOwingNothing() {
        val quick = PayoffCalculator.simulate(listOf(debt(1, 100.0, 0.0, 100.0)), 0.0, Strategy.AVALANCHE, start = start)
        val slow = PayoffCalculator.simulate(listOf(debt(1, 400.0, 0.0, 100.0)), 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(0.0, quick.maxBalanceGap(quick), 0.0)
        // Balances by month: quick 100, 0 and slow 400, 300, 200, 100, 0. Once "quick" is done it owes nothing, so the
        // gap is 300 at the start, 300 after month 1, then shrinks.
        assertEquals(300.0, quick.maxBalanceGap(slow), 0.001)
    }

    // ---------- this month's payment already made ----------

    @Test
    fun aDebtAlreadyPaidThisMonthTakesNoMinimumInMonthOneAndPaysNormallyAfter() {
        // 1,000 at 0% with 100/month: ten payments normally. Month 1 is already paid, so the schedule is one month longer.
        val unpaid = PayoffCalculator.simulate(listOf(debt(1, 1000.0, 0.0, 100.0)), 0.0, Strategy.AVALANCHE, start = start)
        val paid = PayoffCalculator.simulate(listOf(debt(1, 1000.0, 0.0, 100.0, paid = true)), 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(10, unpaid.monthsToDebtFree)
        assertEquals(11, paid.monthsToDebtFree)
        assertEquals(0.0, paid.months.first().totalPaid, 0.0)
        assertEquals(100.0, paid.months[1].totalPaid, 0.001)
        assertEquals(setOf(1L), paid.alreadyPaidIds)
        assertTrue(unpaid.alreadyPaidIds.isEmpty())
    }

    @Test
    fun interestStillAccruesInTheAlreadyPaidMonth() {
        val plan = PayoffCalculator.simulate(listOf(debt(1, 1000.0, 12.0, 100.0, paid = true)), 0.0, Strategy.AVALANCHE, start = start)

        val first = plan.months.first().debts.single()
        assertEquals(10.0, first.interest, 0.001) // 1,000 * 12% / 12
        assertEquals(1010.0, first.endBalance, 0.001)
    }

    @Test
    fun extraStillGoesToTheTopPriorityDebtEvenWhenItsMinimumIsAlreadyPaid() {
        // Debt 1 has the highest APR and its minimum is already paid. The extra hasn't been spent, so it still goes to
        // debt 1 (not down the list to debt 2), while debt 1 itself takes no second minimum.
        val debts = listOf(debt(1, 5000.0, 24.0, 100.0, paid = true), debt(2, 2000.0, 10.0, 60.0))
        val plan = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, start = start)

        val first = plan.months.first().debts.associateBy { it.debtId }
        assertEquals(0.0, first.getValue(1L).minPaid, 0.0)
        assertEquals(50.0, first.getValue(1L).extraPaid, 0.001)
        assertEquals(60.0, first.getValue(2L).minPaid, 0.001)
        assertEquals(0.0, first.getValue(2L).extraPaid, 0.001)
        // From month 2 the debt pays its minimum again as well.
        val second = plan.months[1].debts.associateBy { it.debtId }
        assertEquals(100.0, second.getValue(1L).minPaid, 0.001)
        assertEquals(50.0, second.getValue(1L).extraPaid, 0.001)
    }

    @Test
    fun tickingTheTopTwoDebtsStillAimsTheExtraAtTheFirstOfThem() {
        // The reported case: the first two debts have their payment made, the third is unpaid.
        val debts = listOf(
            debt(1, 4500.0, 25.74, 150.0, revolving = true, paid = true),
            debt(2, 4600.0, 19.49, 150.0, revolving = true, paid = true),
            debt(3, 4700.0, 16.74, 150.0, revolving = true),
        )
        val plan = PayoffCalculator.simulate(debts, 730.0, Strategy.AVALANCHE, start = start)

        val first = plan.months.first().debts.associateBy { it.debtId }
        assertEquals(730.0, first.getValue(1L).extraPaid, 0.001)
        assertEquals(0.0, first.getValue(3L).extraPaid, 0.001)
        assertEquals(150.0, first.getValue(3L).minPaid, 0.001)
    }

    @Test
    fun whatWasAlreadyPaidAboveTheMinimumComesOutOfThatMonthsExtra() {
        // Budget is 100 + 60 + 50 extra = 210. Debt 1 already got 130 (30 over its minimum), so only 20 of the extra is left.
        val debts = listOf(
            PlanDebt(1, "Debt 1", true, 5000.0, 24.0, 100.0, alreadyPaidThisMonth = 130.0),
            PlanDebt(2, "Debt 2", false, 2000.0, 10.0, 60.0),
        )
        val plan = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, start = start)

        val first = plan.months.first().debts.associateBy { it.debtId }
        assertEquals(20.0, first.getValue(1L).extraPaid, 0.001)
        assertEquals(60.0, first.getValue(2L).minPaid, 0.001)
        assertEquals(80.0, plan.months.first().totalPaid, 0.001)
    }

    @Test
    fun anAmountAlreadyPaidBiggerThanTheWholeBudgetLeavesNothingMoreToPayInMonthOne() {
        val debts = listOf(
            PlanDebt(1, "Debt 1", true, 5000.0, 24.0, 100.0, alreadyPaidThisMonth = 900.0),
            PlanDebt(2, "Debt 2", false, 2000.0, 10.0, 60.0),
        )
        val plan = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, start = start)

        val first = plan.months.first().debts.associateBy { it.debtId }
        assertEquals(0.0, first.getValue(1L).totalPaid, 0.0)
        assertEquals(60.0, first.getValue(2L).minPaid, 0.001) // the unpaid debt still takes its own minimum
        assertEquals(0.0, first.getValue(2L).extraPaid, 0.0)
    }

    @Test
    fun aPaidDebtsMinimumIsNotHandedToOtherDebtsInMonthOne() {
        // Budget is 100 + 60 = 160 with no extra. Debt 1's 100 is already spent, so debt 2 gets only its own 60.
        val debts = listOf(debt(1, 5000.0, 24.0, 100.0, paid = true), debt(2, 2000.0, 10.0, 60.0))
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(60.0, plan.months.first().totalPaid, 0.001)
    }

    @Test
    fun whenEveryDebtIsAlreadyPaidOnlyTheExtraIsPaidInMonthOne() {
        val debts = listOf(debt(1, 1000.0, 12.0, 100.0, paid = true), debt(2, 500.0, 6.0, 50.0, paid = true))
        val plan = PayoffCalculator.simulate(debts, 25.0, Strategy.SNOWBALL, start = start)

        val first = plan.months.first()
        assertEquals(25.0, first.totalPaid, 0.001) // no minimums again, just the extra
        assertEquals(0.0, first.debts.sumOf { it.minPaid }, 0.0)
        assertEquals(setOf(1L, 2L), plan.alreadyPaidIds)
        assertTrue(plan.converges)
    }

    @Test
    fun theSameDebtsWithoutTheFlagAreUnchanged() {
        val debts = listOf(debt(1, 800.0, 6.0, 30.0), debt(2, 4200.0, 22.9, 110.0, revolving = true))
        val plain = PayoffCalculator.simulate(debts, 100.0, Strategy.AVALANCHE, start = start)
        val flaggedFalse = PayoffCalculator.simulate(debts.map { it.copy(alreadyPaidThisMonth = null) }, 100.0, Strategy.AVALANCHE, start = start)

        assertEquals(plain, flaggedFalse)
    }
    // ---------- the budget after a payoff, part payments and lump sums ----------

    @Test
    fun aClearedDebtsMinimumKeepsFlowingToTheRemainingDebts() {
        // Two 100 minimums make a 200 budget. Debt 1 is already paid off, but its 100 is still part of what you put in
        // each month, so debt 2 keeps getting 200 instead of dropping to 100.
        val cleared = debt(1, 0.0, 0.0, 100.0)
        val open = debt(2, 1000.0, 0.0, 100.0)

        val withPayoff = PayoffCalculator.simulate(listOf(cleared, open), 0.0, Strategy.AVALANCHE, start = start)
        val alone = PayoffCalculator.simulate(listOf(open), 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(200.0, withPayoff.months.first().totalPaid, 0.001)
        assertEquals(5, withPayoff.monthsToDebtFree)
        assertEquals(10, alone.monthsToDebtFree) // the same debt without the freed minimum
    }

    @Test
    fun aPartPaymentOnlyReducesWhatIsStillScheduledThisMonth() {
        // 40 of a 100 minimum is paid (the balance already shows it): month 1 schedules the other 60, not another 100.
        val debts = listOf(PlanDebt(1, "Debt 1", false, 960.0, 0.0, 100.0, alreadyPaidThisMonth = 40.0))
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.AVALANCHE, start = start)

        val first = plan.months.first()
        assertEquals(60.0, first.totalPaid, 0.001)
        assertEquals(60.0, first.debts.single().minPaid, 0.001)
        assertEquals(100.0, plan.months[1].totalPaid, 0.001)
        assertTrue("a part payment isn't a fully paid month", plan.alreadyPaidIds.isEmpty())
    }

    @Test
    fun aFullyPaidMinimumIsListedAsAlreadyPaidButAPartPaymentIsNot() {
        val full = PlanDebt(1, "Full", false, 900.0, 0.0, 100.0, alreadyPaidThisMonth = 100.0)
        val part = PlanDebt(2, "Part", false, 900.0, 0.0, 100.0, alreadyPaidThisMonth = 99.0)

        val plan = PayoffCalculator.simulate(listOf(full, part), 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(setOf(1L), plan.alreadyPaidIds)
    }

    @Test
    fun aLumpSumThatClearsAnAlreadyPaidDebtDoesNotSpendItsMoneyTwice() {
        // Two 100 minimums, 100 already paid on debt 1, and a separate lump sum that clears debt 1. The 100 already
        // spent is still gone from the budget, so debt 2 gets 100 this month, not 200.
        val debts = listOf(
            PlanDebt(1, "Debt 1", false, 100.0, 0.0, 100.0, alreadyPaidThisMonth = 100.0),
            PlanDebt(2, "Debt 2", false, 1000.0, 0.0, 100.0),
        )
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.AVALANCHE, lump = LumpSum(100.0, targetDebtId = 1), start = start)

        val first = plan.months.first().debts.associateBy { it.debtId }
        assertEquals(100.0, first.getValue(1L).lumpPaid, 0.001)
        assertEquals(100.0, first.getValue(2L).minPaid, 0.001)
        assertEquals(0.0, first.getValue(2L).extraPaid, 0.001)
        assertEquals(1, plan.payoffMonthIndex.getValue(1L))
    }

    @Test
    fun whatWasSpentOnADebtClearedEarlierThisMonthComesOutOfThisMonthsBudget() {
        val open = debt(2, 1000.0, 0.0, 100.0)
        val nothingSpent = PayoffCalculator.simulate(listOf(debt(1, 0.0, 0.0, 100.0), open), 0.0, Strategy.AVALANCHE, start = start)
        // 30 went into clearing debt 1 this month, out of the 200 budget: 170 is left for debt 2 (its 100 plus 70 extra).
        val clearedWith30 = PlanDebt(1, "Debt 1", false, 0.0, 0.0, 100.0, alreadyPaidThisMonth = 30.0)
        val partlySpent = PayoffCalculator.simulate(listOf(clearedWith30, open), 0.0, Strategy.AVALANCHE, start = start)
        // 500 went into it: more than the whole budget, so debt 2 only gets its own minimum.
        val clearedWith500 = clearedWith30.copy(alreadyPaidThisMonth = 500.0)
        val overspent = PayoffCalculator.simulate(listOf(clearedWith500, open), 0.0, Strategy.AVALANCHE, start = start)

        assertEquals(200.0, nothingSpent.months.first().totalPaid, 0.001)
        assertEquals(170.0, partlySpent.months.first().totalPaid, 0.001)
        assertEquals(100.0, overspent.months.first().totalPaid, 0.001)
    }
    @Test
    fun snowballAimsExtraAtSmallestBalance() {
        val debts = listOf(debt(1, 500.0, 5.0, 25.0), debt(2, 5000.0, 24.0, 100.0))
        val plan = PayoffCalculator.simulate(debts, 200.0, Strategy.SNOWBALL, start = start)
        assertEquals(listOf(1L, 2L), plan.priorityOrder)
        assertEquals(200.0, plan.months.first().debts.first { it.debtId == 1L }.extraPaid, 0.001)
    }

    @Test
    fun avalancheNeverCostsMoreInterestThanSnowball() {
        val debts = listOf(
            debt(1, 800.0, 6.0, 30.0),
            debt(2, 4200.0, 22.9, 110.0, revolving = true),
            debt(3, 12000.0, 7.5, 240.0),
            debt(4, 2500.0, 26.0, 75.0, revolving = true),
        )
        val a = PayoffCalculator.simulate(debts, 150.0, Strategy.AVALANCHE, start = start)
        val s = PayoffCalculator.simulate(debts, 150.0, Strategy.SNOWBALL, start = start)
        assertTrue(a.converges && s.converges)
        assertTrue("avalanche ${a.totalInterest} vs snowball ${s.totalInterest}", a.totalInterest <= s.totalInterest)
    }

    @Test
    fun freedMinimumsRollIntoTheNextDebt() {
        // Budget is 60 + 40 = 100 every month. Once debt 1 is gone, debt 2 should still be paid 100/month.
        val debts = listOf(debt(1, 100.0, 0.0, 20.0), debt(2, 1000.0, 0.0, 40.0))
        val plan = PayoffCalculator.simulate(debts, 40.0, Strategy.SNOWBALL, start = start)
        val afterFirst = plan.months.first { it.index > plan.payoffMonthIndex.getValue(1L) }
        assertEquals(100.0, afterFirst.totalPaid, 0.001)
        assertEquals(11, plan.monthsToDebtFree) // 1,100 owed at 100/month and 0% APR
    }

    @Test
    fun minimumsThatDontCoverInterestNeverConverge() {
        val plan = PayoffCalculator.simulate(listOf(debt(1, 10000.0, 30.0, 50.0)), 0.0, Strategy.AVALANCHE, start = start)
        assertNull(plan.monthsToDebtFree)
        assertFalse(plan.converges)
        assertEquals(setOf(1L), plan.unpaidDebtIds)
        assertTrue(plan.balanceCurve().isEmpty())
    }

    @Test
    fun extraPaymentShortensThePlan() {
        val debts = listOf(debt(1, 6000.0, 19.99, 150.0, revolving = true))
        val base = PayoffCalculator.simulate(debts, 0.0, Strategy.AVALANCHE, start = start)
        val more = PayoffCalculator.simulate(debts, 100.0, Strategy.AVALANCHE, start = start)
        assertTrue(more.monthsToDebtFree!! < base.monthsToDebtFree!!)
        assertTrue(more.totalInterest < base.totalInterest)
    }

    @Test
    fun lumpSumAutoTargetsHighestApr() {
        val debts = listOf(debt(1, 3000.0, 8.0, 80.0), debt(2, 3000.0, 21.0, 80.0))
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.SNOWBALL, LumpSum(1000.0), start)
        assertEquals(mapOf(2L to 1000.0), plan.lumpAllocation())
    }

    @Test
    fun lumpSumCanTargetASpecificDebtAndOverflowsToAvalancheOrder() {
        val debts = listOf(debt(1, 300.0, 8.0, 30.0), debt(2, 3000.0, 21.0, 80.0))
        val plan = PayoffCalculator.simulate(debts, 0.0, Strategy.AVALANCHE, LumpSum(1000.0, targetDebtId = 1L), start)
        val alloc = plan.lumpAllocation()
        assertEquals(300.0, alloc.getValue(1L), 0.001)
        assertEquals(700.0, alloc.getValue(2L), 0.001)
        assertEquals(1, plan.payoffMonthIndex[1L])
    }

    @Test
    fun lumpSumBringsTheDebtFreeDateForward() {
        val debts = listOf(debt(1, 5000.0, 18.0, 120.0), debt(2, 2000.0, 9.0, 60.0))
        val base = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, start = start)
        val withLump = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, LumpSum(1500.0), start)
        assertTrue(withLump.monthsToDebtFree!! < base.monthsToDebtFree!!)
        assertTrue(withLump.totalInterest < base.totalInterest)
    }

    @Test
    fun paidOffDebtsAreIgnoredAndEmptyInputIsDebtFree() {
        val plan = PayoffCalculator.simulate(listOf(debt(1, 0.0, 20.0, 50.0)), 100.0, Strategy.AVALANCHE, start = start)
        assertTrue(plan.isDebtFree)
        assertTrue(plan.months.isEmpty())
        assertEquals(0, plan.monthsToDebtFree)
    }

    @Test
    fun avalancheTieOnAprPrefersRevolving() {
        val debts = listOf(debt(1, 1000.0, 15.0, 30.0, revolving = false), debt(2, 1000.0, 15.0, 30.0, revolving = true))
        val plan = PayoffCalculator.simulate(debts, 50.0, Strategy.AVALANCHE, start = start)
        assertEquals(listOf(2L, 1L), plan.priorityOrder)
    }
}
