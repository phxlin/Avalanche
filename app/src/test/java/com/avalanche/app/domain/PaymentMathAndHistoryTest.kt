package com.avalanche.app.domain

import com.avalanche.app.data.Backup
import com.avalanche.app.data.BackupException
import com.avalanche.app.data.AppSettings
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.PaymentEntity
import com.avalanche.app.notifications.ReminderRule
import com.avalanche.app.util.formatPercent
import com.avalanche.app.util.formatPercentPoints
import org.junit.Assert.assertNull
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PaymentMathAndHistoryTest {
    @Test
    fun paymentCoversAccruedInterestFirst() {
        // 1000 @ 18.25% for 30 days -> 15.00 interest.
        val a = PaymentMath.apply(1000.0, 18.25, lastAccrualDay = 100, payDay = 130, requested = 100.0)
        assertEquals(15.0, a.interest, 0.001)
        assertEquals(85.0, a.principal, 0.001)
        assertEquals(915.0, a.balanceAfter, 0.001)
        assertEquals(130L, a.newAccrualDay)
    }

    @Test
    fun sameDayPaymentAccruesNoInterest() {
        val a = PaymentMath.apply(1000.0, 25.0, 100, 100, 200.0)
        assertEquals(0.0, a.interest, 0.0)
        assertEquals(800.0, a.balanceAfter, 0.001)
    }

    @Test
    fun paymentBelowAccruedInterestCapitalisesTheShortfall() {
        val a = PaymentMath.apply(1000.0, 36.5, 0, 30, 10.0) // interest = 30.00
        assertEquals(10.0, a.interest, 0.001)
        assertEquals(0.0, a.principal, 0.001)
        assertEquals(1020.0, a.balanceAfter, 0.001)
    }

    @Test
    fun overpaymentIsCappedAtThePayoffAmount() {
        val a = PaymentMath.apply(100.0, 0.0, 0, 10, 500.0)
        assertEquals(100.0, a.amount, 0.001)
        assertEquals(0.0, a.balanceAfter, 0.0)
    }

    @Test
    fun backdatedPaymentDoesNotMoveAccrualBackwards() {
        val a = PaymentMath.apply(1000.0, 20.0, lastAccrualDay = 200, payDay = 150, requested = 50.0)
        assertEquals(0.0, a.interest, 0.0)
        assertEquals(200L, a.newAccrualDay)
    }

    private fun debt(id: Long, created: Long, current: Double) = DebtEntity(
        id = id, name = "D$id", type = "CREDIT_CARD", originalBalance = 1000.0, currentBalance = current,
        apr = 20.0, minPayment = 25.0, createdDay = created, lastAccrualDay = created,
    )

    private fun pay(id: Long, debtId: Long, day: Long, before: Double, after: Double) = PaymentEntity(
        id = id, sessionId = id, debtId = debtId, day = day, amount = before - after, interestPortion = 2.0,
        principalPortion = before - after - 2.0, balanceBefore = before, balanceAfter = after,
        prevAccrualDay = day, prevOriginalBalance = 1000.0,
    )

    @Test
    fun historyStartsAtOriginalBalanceAndEndsToday() {
        val d = debt(1, created = 100, current = 800.0)
        val h = balanceHistory(listOf(d), listOf(pay(1, 1, 110, 1000.0, 900.0), pay(2, 1, 120, 900.0, 800.0)), today = 130)
        assertEquals(listOf(100L, 110L, 120L, 130L), h.map { it.day })
        assertEquals(listOf(1000.0, 900.0, 800.0, 800.0), h.map { it.balance })
    }

    @Test
    fun historySumsAcrossDebtsAndStartsALateDebtWhenAdded() {
        val a = debt(1, created = 100, current = 900.0)
        val b = debt(2, created = 110, current = 500.0).copy(originalBalance = 500.0)
        val h = balanceHistory(listOf(a, b), listOf(pay(1, 1, 105, 1000.0, 900.0)), today = 110)
        assertEquals(listOf(1000.0, 900.0, 1400.0), h.map { it.balance })
    }

    @Test
    fun percentPaidDerivesTheOriginalBalance() {
        assertEquals(6000.0, originalFromPercentPaid(4200.0, 30.0), 0.001)
        assertEquals(4200.0, originalFromPercentPaid(4200.0, 0.0), 0.0)
        assertEquals(0.0, originalFromPercentPaid(0.0, 40.0), 0.0)
        assertEquals(30.0, percentPaidOf(6000.0, 4200.0), 0.001)
        assertEquals(0.0, percentPaidOf(0.0, 0.0), 0.0)
    }

    @Test
    fun enteringThirtyTwoPercentDisplaysThirtyTwoNotThirtyOne() {
        // Regression: the original was rounded to cents and the display truncated, so 32% showed as 31%.
        for (current in listOf(4200.0, 1234.56, 987.65, 15000.0, 333.33)) {
            val original = originalFromPercentPaid(current, 32.0)
            val progress = (1.0 - current / original).toFloat()
            assertEquals("current=$current", "32%", formatPercent(progress))
        }
    }

    @Test
    fun percentagesKeepTwoDecimalsAndDropTrailingZeros() {
        fun fmt(p: Double) = formatPercentPoints(p).replace(',', '.')
        assertEquals("32%", fmt(32.0))
        assertEquals("32.5%", fmt(32.5))
        assertEquals("32.25%", fmt(32.25))
        assertEquals("32.26%", fmt(32.256))
        assertEquals("0%", fmt(0.0))
        assertEquals("100%", fmt(100.0))
        // A two-decimal entry survives the round trip through the original balance.
        val original = originalFromPercentPaid(4200.0, 32.25)
        assertEquals(32.25, percentPaidOf(original, 4200.0), 1e-9)
    }

    @Test
    fun interestEstimateReplaysTheLoanUntilItReachesTheCurrentBalance() {
        // 10,000 @ 6% with 200/month, now at 5,000.
        val e = estimateInterestPaid(10000.0, 5000.0, 6.0, 200.0)!!
        assertEquals(e.months * 200.0 - 5000.0, e.interest, 0.01) // paid = principal + interest
        // Closed form: 1.005^n = 35000 / 30000, so n = ln(1.1667) / ln(1.005) = 30.9 months and interest = 30.9 * 200 - 5000.
        assertEquals(30.9, e.months, 0.1)
        assertEquals(1180.0, e.interest, 25.0)
    }

    @Test
    fun interestEstimateIsZeroAtZeroAprAndForUnstartedDebts() {
        val flat = estimateInterestPaid(5000.0, 3000.0, 0.0, 200.0)!!
        assertEquals(0.0, flat.interest, 0.0)
        assertEquals(10.0, flat.months, 1e-9)
        assertEquals(0.0, estimateInterestPaid(1000.0, 1000.0, 20.0, 50.0)!!.interest, 0.0)
    }

    @Test
    fun interestEstimateIsNullWhenTheMinimumNeverCoveredTheInterest() {
        assertNull(estimateInterestPaid(6000.0, 4200.0, 22.9, 110.0)) // 114.50 of interest a month at the start
        assertNull(estimateInterestPaid(6000.0, 4200.0, 10.0, 0.0))
    }

    @Test
    fun estimatedPriorInterestCountsTowardsTheTotals() {
        val d = debt(1, 0, 4200.0).copy(priorInterestPaid = 500.0)
        val t = computeTotals(listOf(d), listOf(pay(1, 1, 5, 4300.0, 4200.0)))
        assertEquals(502.0, t.interestPaid, 1e-9) // 2.00 logged + 500 estimated
        assertEquals(500.0, t.estimatedInterest, 1e-9)
    }

    @Test
    fun startingPercentPaidShowsUpInTheTotals() {
        val d = debt(1, 0, 4200.0).copy(originalBalance = originalFromPercentPaid(4200.0, 30.0))
        val t = computeTotals(listOf(d), emptyList())
        assertEquals(6000.0, t.original, 0.001)
        assertEquals(0.3f, t.percentPaid, 0.0001f)
    }

    @Test
    fun noDebtsMeansNoHistory() {
        assertTrue(balanceHistory(emptyList(), emptyList(), 10).isEmpty())
    }

    @Test
    fun totalsCountInterestAndPercentPaid() {
        val t = computeTotals(listOf(debt(1, 0, 750.0)), listOf(pay(1, 1, 5, 1000.0, 750.0)))
        assertEquals(750.0, t.remaining, 0.0)
        assertEquals(0.25f, t.percentPaid, 0.0001f)
        assertEquals(2.0, t.interestPaid, 0.0)
    }

    @Test
    fun backupRoundTripsAndRejectsGarbage() {
        val d = debt(7, 100, 800.0)
        val p = pay(3, 7, 110, 1000.0, 800.0)
        val json = Backup.toJson(listOf(d), listOf(p), AppSettings(currency = "EUR", extraMonthly = 75.0))
        val parsed = Backup.parse(json)
        assertEquals(listOf(d), parsed.debts)
        assertEquals(listOf(p), parsed.payments)
        assertEquals("EUR", parsed.settings?.currency)

        for (bad in listOf("not json", "{}", "{\"app\":\"Avalanche\",\"version\":99,\"debts\":[]}")) {
            try {
                Backup.parse(bad)
                fail("should reject: $bad")
            } catch (_: BackupException) {
            }
        }
    }

    @Test
    fun backupRejectsPaymentsForUnknownDebts() {
        val json = Backup.toJson(listOf(debt(1, 0, 10.0)), listOf(pay(1, 1, 1, 20.0, 10.0)), AppSettings("USD"))
            .replace("\"debtId\": 1", "\"debtId\": 42")
        try {
            Backup.parse(json)
            fail("expected BackupException")
        } catch (_: BackupException) {
        }
    }

    @Test
    fun reminderFiresOncePerMonthAfterNineOnOrAfterTheChosenDay() {
        val day5 = 5
        assertFalse(ReminderRule.isDue(LocalDateTime.of(2026, 3, 4, 12, 0), day5, null))
        assertFalse(ReminderRule.isDue(LocalDateTime.of(2026, 3, 5, 8, 59), day5, null))
        assertTrue(ReminderRule.isDue(LocalDateTime.of(2026, 3, 5, 9, 0), day5, null))
        assertTrue(ReminderRule.isDue(LocalDateTime.of(2026, 3, 9, 15, 0), day5, YearMonth.of(2026, 2)))
        assertFalse(ReminderRule.isDue(LocalDateTime.of(2026, 3, 9, 15, 0), day5, YearMonth.of(2026, 3)))
    }
}
