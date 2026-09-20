package com.avalanche.app.domain

import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.KIND_ADJUSTMENT
import com.avalanche.app.data.PaymentEntity
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaidThisMonthTest {
    private val march = YearMonth.of(2026, 3)

    private fun debt(id: Long = 1, balance: Double = 1000.0, min: Double = 100.0, paidMonth: Int? = null) = DebtEntity(
        id = id, name = "Debt $id", type = "CREDIT_CARD", originalBalance = 1000.0, currentBalance = balance,
        apr = 10.0, minPayment = min, createdDay = 0, lastAccrualDay = 0, paidMonth = paidMonth,
    )

    private fun payment(debtId: Long, day: String, amount: Double, kind: String = "PAYMENT") = PaymentEntity(
        sessionId = 1, debtId = debtId, day = java.time.LocalDate.parse(day).toEpochDay(), amount = amount,
        interestPortion = 0.0, principalPortion = amount, balanceBefore = 0.0, balanceAfter = 0.0,
        prevAccrualDay = 0, prevOriginalBalance = 0.0, kind = kind,
    )

    @Test
    fun monthCodesAreConsecutiveAcrossYearEnds() {
        assertEquals(monthCode(YearMonth.of(2026, 12)) + 1, monthCode(YearMonth.of(2027, 1)))
        assertEquals(monthCode(YearMonth.of(2026, 3)) + 1, monthCode(YearMonth.of(2026, 4)))
    }

    @Test
    fun theCheckboxCountsOnlyForTheMonthItWasSetIn() {
        val marked = debt(paidMonth = monthCode(march))

        assertTrue(isPaidThisMonth(marked, emptyList(), march))
        assertFalse(isPaidThisMonth(marked, emptyList(), march.plusMonths(1)))
        assertFalse(isPaidThisMonth(marked, emptyList(), march.minusMonths(1)))
    }

    @Test
    fun paymentsLoggedThisMonthThatCoverTheMinimumCount() {
        val d = debt(min = 100.0)

        assertTrue(paidByLoggedPayments(d, listOf(payment(1, "2026-03-05", 100.0)), march))
        assertTrue(paidByLoggedPayments(d, listOf(payment(1, "2026-03-05", 60.0), payment(1, "2026-03-20", 40.0)), march))
        assertFalse(paidByLoggedPayments(d, listOf(payment(1, "2026-03-05", 99.0)), march))
    }

    @Test
    fun paymentsFromOtherMonthsOrOtherDebtsOrAdjustmentsDoNotCount() {
        val d = debt(min = 100.0)
        val notThisMonth = listOf(
            payment(1, "2026-02-28", 500.0),
            payment(1, "2026-04-01", 500.0),
            payment(2, "2026-03-10", 500.0),
            payment(1, "2026-03-10", 500.0, kind = KIND_ADJUSTMENT),
        )

        assertFalse(paidByLoggedPayments(d, notThisMonth, march))
    }

    @Test
    fun theFirstAndLastDaysOfTheMonthCount() {
        val d = debt(min = 100.0)

        assertTrue(paidByLoggedPayments(d, listOf(payment(1, "2026-03-01", 100.0)), march))
        assertTrue(paidByLoggedPayments(d, listOf(payment(1, "2026-03-31", 100.0)), march))
    }

    @Test
    fun aDebtWithNoMinimumIsNeverPaidByPaymentsAndAPaidOffDebtIsNeverPaid() {
        assertFalse(paidByLoggedPayments(debt(min = 0.0), listOf(payment(1, "2026-03-05", 50.0)), march))
        assertFalse(isPaidThisMonth(debt(balance = 0.0, paidMonth = monthCode(march)), emptyList(), march))
    }

    @Test
    fun amountsAreTheLargerOfWhatWasLoggedAndTheTickedMinimumAndIncludePartPayments() {
        val ticked = debt(id = 1, min = 100.0, paidMonth = monthCode(march))
        val tickedAndOverpaid = debt(id = 2, min = 100.0, paidMonth = monthCode(march))
        val loggedOnly = debt(id = 3, min = 50.0)
        val partPaid = debt(id = 4, min = 100.0)
        val nothingPaid = debt(id = 5, min = 100.0)
        val payments = listOf(
            payment(2, "2026-03-04", 250.0),
            payment(3, "2026-03-06", 30.0),
            payment(3, "2026-03-20", 45.0),
            payment(4, "2026-03-07", 20.0), // some payment, but not the minimum
        )

        val amounts = paidThisMonthAmounts(listOf(ticked, tickedAndOverpaid, loggedOnly, partPaid, nothingPaid), payments, march)

        assertEquals(setOf(1L, 2L, 3L, 4L), amounts.keys) // the part payment counts (that money is spent); an untouched debt does not
        assertEquals(20.0, amounts.getValue(4L), 0.001)
        assertEquals(100.0, amounts.getValue(1L), 0.001)
        assertEquals(250.0, amounts.getValue(2L), 0.001)
        assertEquals(75.0, amounts.getValue(3L), 0.001)
    }

    @Test
    fun aPartPaymentIsNotAPaidMonthEvenThoughItsAmountCounts() {
        val d = debt(id = 1, min = 100.0)
        val payments = listOf(payment(1, "2026-03-05", 40.0))

        assertEquals(40.0, paidThisMonthAmounts(listOf(d), payments, march).getValue(1L), 0.001)
        assertEquals(emptySet<Long>(), paidThisMonthIds(listOf(d), payments, march))
        assertFalse(isPaidThisMonth(d, payments, march))
    }

    @Test
    fun aDebtClearedThisMonthStillCountsWhatWasPaidOnIt() {
        // Paid off this month with 500: the balance is 0, but that money left the budget all the same.
        val cleared = debt(id = 1, balance = 0.0, min = 100.0)
        val payments = listOf(payment(1, "2026-03-10", 500.0), payment(1, "2026-02-10", 300.0))

        assertEquals(500.0, paidThisMonthAmounts(listOf(cleared), payments, march).getValue(1L), 0.001) // February's payment doesn't count
        assertEquals(emptySet<Long>(), paidThisMonthIds(listOf(cleared), payments, march)) // but it is not shown as "paid this month"
    }
    @Test
    fun idsCombineTheCheckboxAndLoggedPayments() {
        val marked = debt(id = 1, paidMonth = monthCode(march))
        val logged = debt(id = 2, min = 50.0)
        val neither = debt(id = 3)
        val payments = listOf(payment(2, "2026-03-09", 50.0))

        assertEquals(setOf(1L, 2L), paidThisMonthIds(listOf(marked, logged, neither), payments, march))
    }
}