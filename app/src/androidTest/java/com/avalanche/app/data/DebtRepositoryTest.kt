package com.avalanche.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.domain.estimateInterestPaid
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real repository against a real Room database held in memory. A fake clock makes the
 * date-dependent behaviour (interest accrual, rate changes) deterministic.
 */
@RunWith(AndroidJUnit4::class)
class DebtRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: DebtRepository
    private val start: LocalDate = LocalDate.of(2026, 3, 1)
    private var today: LocalDate = start
    private val paidOffNotifications = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        // A separate prefs file so the tests never touch the app's real settings.
        val prefs = context.getSharedPreferences("repository_test_settings", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        settings = SettingsStore(prefs)
        repo = DebtRepository(db, settings, { paidOffNotifications += it }, { today })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun debt(id: Long) = db.debtDao().get(id)!!
    private suspend fun rows() = db.paymentDao().getAll()
    private fun advanceDays(days: Long) {
        today = today.plusDays(days)
    }

    private suspend fun addCard(balance: Double = 1000.0, apr: Double = 36.5, min: Double = 50.0) =
        repo.addDebt("Card", DebtType.CREDIT_CARD, balance, apr, min)

    // ---------- payments ----------

    @Test
    fun paymentSplitsInterestFromPrincipalAndReducesTheBalance() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 18.25, 50.0)
        advanceDays(30) // 1000 * 18.25% * 30/365 = 15.00

        val result = repo.logPayments(today, listOf(Allocation(id, 100.0)))

        assertEquals(1, result.paymentCount)
        val row = rows().single()
        assertEquals(15.0, row.interestPortion, 0.001)
        assertEquals(85.0, row.principalPortion, 0.001)
        assertEquals(915.0, debt(id).currentBalance, 0.001)
        assertEquals(today.toEpochDay(), debt(id).lastAccrualDay)
    }

    @Test
    fun aPaymentSmallerThanTheAccruedInterestCapitalisesTheShortfall() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5)
        advanceDays(30) // 30.00 of interest

        repo.logPayments(today, listOf(Allocation(id, 10.0)))

        val row = rows().single()
        assertEquals(10.0, row.interestPortion, 0.001)
        assertEquals(0.0, row.principalPortion, 0.001)
        assertEquals(1020.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun overpayingIsCappedAtThePayoffAmountAndMarksTheDebtPaidOff() = runBlocking<Unit> {
        val id = repo.addDebt("Loan", DebtType.PERSONAL_LOAN, 100.0, 0.0, 20.0)

        val result = repo.logPayments(today, listOf(Allocation(id, 500.0)))

        assertEquals(100.0, result.totalPaid, 0.001)
        assertEquals(listOf("Loan"), result.paidOffNames)
        assertEquals(listOf("Loan"), paidOffNotifications)
        assertEquals(0.0, debt(id).currentBalance, 0.0)
        assertEquals(today.toEpochDay(), debt(id).paidOffDay)
        assertFalse(repo.hasActiveDebts())
    }

    @Test
    fun aPaymentSessionCanBeSplitAcrossSeveralDebts() = runBlocking<Unit> {
        val a = addCard(1000.0, 0.0)
        val b = repo.addDebt("Car", DebtType.AUTO_LOAN, 5000.0, 0.0, 200.0)

        val result = repo.logPayments(today, listOf(Allocation(a, 100.0), Allocation(b, 250.0), Allocation(999, 5.0)))

        assertEquals(2, result.paymentCount)
        assertEquals(350.0, result.totalPaid, 0.001)
        assertEquals(900.0, debt(a).currentBalance, 0.001)
        assertEquals(4750.0, debt(b).currentBalance, 0.001)
        assertEquals(1, rows().map { it.sessionId }.distinct().size)
    }

    @Test
    fun celebrationsCanBeTurnedOff() = runBlocking<Unit> {
        settings.update { it.copy(celebrationsEnabled = false) }
        val id = repo.addDebt("Loan", DebtType.PERSONAL_LOAN, 50.0, 0.0, 50.0)

        repo.logPayments(today, listOf(Allocation(id, 50.0)))

        assertTrue(paidOffNotifications.isEmpty())
    }

    // ---------- undo ----------

    @Test
    fun undoRestoresBalanceAccrualPointAndPaidOffState() = runBlocking<Unit> {
        val id = repo.addDebt("Loan", DebtType.PERSONAL_LOAN, 100.0, 0.0, 20.0)
        val created = debt(id).lastAccrualDay
        advanceDays(10)
        repo.logPayments(today, listOf(Allocation(id, 500.0)))

        assertTrue(repo.undoLatest(id))

        val d = debt(id)
        assertEquals(100.0, d.currentBalance, 0.001)
        assertNull(d.paidOffDay)
        assertEquals(created, d.lastAccrualDay)
        assertTrue(rows().isEmpty())
        assertFalse(repo.undoLatest(id))
    }

    @Test
    fun undoingAPaymentKeepsAnOriginalBalanceCorrectedAfterwardsByPercentPaid() = runBlocking<Unit> {
        // Regression: undo used to restore the original balance from before the correction.
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 4200.0, 0.0, 100.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0))) // balance 4,100
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 4100.0, 0.0, 100.0, percentPaid = 50.0)
        assertEquals(8200.0, debt(id).originalBalance, 0.01)

        repo.undoLatest(id)

        assertEquals(4200.0, debt(id).currentBalance, 0.001)
        assertEquals(8200.0, debt(id).originalBalance, 0.01)
    }

    @Test
    fun undoingAnAdjustmentRollsBackTheOriginalItRaised() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1500.0, 0.0, 50.0) // new charges: original rises to 1,500
        assertEquals(1500.0, debt(id).originalBalance, 0.001)

        repo.undoLatest(id)

        assertEquals(900.0, debt(id).currentBalance, 0.001)
        assertEquals(1000.0, debt(id).originalBalance, 0.001)
    }

    @Test
    fun undoingAnAdjustmentKeepsALaterPercentPaidCorrection() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1500.0, 0.0, 50.0)
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1500.0, 0.0, 50.0, percentPaid = 40.0) // original 2,500
        assertEquals(2500.0, debt(id).originalBalance, 0.01)

        repo.undoLatest(id) // the adjustment is still the latest row

        assertEquals(900.0, debt(id).currentBalance, 0.001)
        assertEquals(2500.0, debt(id).originalBalance, 0.01)
    }

    // ---------- editing ----------

    @Test
    fun correctingTheBalanceOfAnUntouchedDebtFixesTheOriginalEntry() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0)

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1200.0, 0.0, 50.0)

        assertEquals(1200.0, debt(id).currentBalance, 0.001)
        assertEquals(1200.0, debt(id).originalBalance, 0.001)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun changingTheBalanceAfterPaymentsIsRecordedAsAnAdjustment() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 400.0, 0.0, 50.0)

        val adjustment = rows().last()
        assertTrue(adjustment.isAdjustment)
        assertEquals(900.0, adjustment.balanceBefore, 0.001)
        assertEquals(400.0, adjustment.balanceAfter, 0.001)
        assertEquals(0.0, adjustment.amount, 0.0)
        assertEquals(1000.0, debt(id).originalBalance, 0.001) // progress isn't rewritten
    }

    @Test
    fun changingTheAprSettlesInterestAccruedAtTheOldRate() = runBlocking<Unit> {
        // Regression: the new rate used to be applied to the whole unpaid stretch at the next payment.
        val id = addCard(1000.0, 36.5)
        advanceDays(30) // 30.00 accrued at 36.5%

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0) // promo: 0% APR

        val adjustment = rows().single()
        assertTrue(adjustment.isAdjustment)
        assertEquals(1000.0, adjustment.balanceBefore, 0.001)
        assertEquals(1030.0, adjustment.balanceAfter, 0.001)
        assertEquals(1030.0, debt(id).currentBalance, 0.001)
        assertEquals(today.toEpochDay(), debt(id).lastAccrualDay)
        assertEquals(0.0, debt(id).apr, 0.0)

        repo.logPayments(today, listOf(Allocation(id, 100.0)))
        assertEquals(0.0, rows().last().interestPortion, 0.0)
        assertEquals(930.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun aRateChangeCanBeUndoneBackToTheOldBalanceAndAccrualPoint() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5)
        val created = debt(id).lastAccrualDay
        advanceDays(30)
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)

        repo.undoLatest(id)

        assertEquals(1000.0, debt(id).currentBalance, 0.001)
        assertEquals(created, debt(id).lastAccrualDay)
    }

    @Test
    fun undoingARateChangePutsTheOldAprBack() = runBlocking<Unit> {
        // Regression: undo restored the balance but left the new rate, so the settled interest was repriced.
        val id = addCard(1000.0, 36.5)
        advanceDays(30)
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)
        assertEquals(0.0, debt(id).apr, 0.0)

        repo.undoLatest(id)

        assertEquals(36.5, debt(id).apr, 0.0)
        assertEquals(1000.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun undoStepsBackThroughARateChangeAndThePaymentBeforeIt() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5)
        advanceDays(30)
        repo.logPayments(today, listOf(Allocation(id, 100.0))) // 30.00 interest, balance 930
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 930.0, 20.0, 50.0) // same day: nothing accrued, still recorded

        repo.undoLatest(id) // the rate change
        assertEquals(36.5, debt(id).apr, 0.0)
        assertEquals(930.0, debt(id).currentBalance, 0.001)

        repo.undoLatest(id) // the payment
        assertEquals(36.5, debt(id).apr, 0.0)
        assertEquals(1000.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun endingAZeroPercentPeriodDoesNotChargeTheNewRateBackwards() = runBlocking<Unit> {
        // Regression: with nothing accrued at 0%, the accrual point stayed put and the new rate was applied to the whole stretch.
        val id = addCard(1000.0, 0.0)
        advanceDays(30)

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 36.5, 50.0) // promo over
        advanceDays(1)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))

        val payment = rows().last()
        assertEquals(1.0, payment.interestPortion, 0.001) // one day at 36.5% (not 31.00 for thirty-one)
        assertEquals(901.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun undoingAPromoEndingRestoresTheZeroRateAndTheAccrualPoint() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0)
        val created = debt(id).lastAccrualDay
        advanceDays(30)
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 36.5, 50.0)

        repo.undoLatest(id)

        assertEquals(0.0, debt(id).apr, 0.0)
        assertEquals(created, debt(id).lastAccrualDay)
        assertTrue(rows().isEmpty())
    }

    // ---------- backdated payments ----------

    @Test
    fun aPaymentDatedBeforeTheLastEntryIsRejectedAndChangesNothing() = runBlocking<Unit> {
        // Regression: logging day 10 after day 20 left the balance at 800 while the dated history ended at 900.
        val id = addCard(1000.0, 0.0)
        advanceDays(20)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))

        try {
            repo.logPayments(today.minusDays(10), listOf(Allocation(id, 100.0)))
            fail("should have rejected the earlier date")
        } catch (expected: BackdatedPaymentException) {
            assertEquals("Card", expected.debtName)
            assertEquals(today, expected.lastEntry)
        }

        assertEquals(900.0, debt(id).currentBalance, 0.001)
        assertEquals(1, rows().size)
    }

    @Test
    fun aRejectedBackdatedPaymentSavesNothingForTheOtherDebtsInTheSession() = runBlocking<Unit> {
        val early = addCard(1000.0, 0.0) // added ten days ago: yesterday is fine for it
        advanceDays(10)
        val late = addCard(500.0, 0.0) // added today: yesterday is before its last entry
        val yesterday = today.minusDays(1)

        try {
            // "early" is valid and comes first, so this only passes if the whole session is rolled back.
            repo.logPayments(yesterday, listOf(Allocation(early, 20.0), Allocation(late, 20.0)))
            fail("should have rejected the earlier date")
        } catch (expected: BackdatedPaymentException) {
            assertEquals("Card", expected.debtName)
        }

        assertEquals(1000.0, debt(early).currentBalance, 0.001)
        assertEquals(500.0, debt(late).currentBalance, 0.001)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun aPaymentDatedOnTheLastEntryDayIsAccepted() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))

        repo.logPayments(today, listOf(Allocation(id, 100.0)))

        assertEquals(800.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun anAprWithinTheFormsPrecisionIsNotARateChange() = runBlocking<Unit> {
        // An imported 19.9999% shows as "20" in the form; saving that must neither settle interest nor rewrite the rate.
        val id = addCard(1000.0, 19.9999)
        advanceDays(30)

        repo.updateDebt(id, "Card renamed", DebtType.CREDIT_CARD, 1000.0, 20.0, 50.0)

        assertTrue(rows().isEmpty())
        assertEquals(19.9999, debt(id).apr, 0.0)
        assertEquals("Card renamed", debt(id).name)
    }

    @Test
    fun aRateChangeWithNothingAccruedIsStillRecordedAsAnEntry() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5) // same day: no interest has accrued yet

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 20.0, 50.0)

        val entry = rows().single()
        assertTrue(entry.isAdjustment)
        assertEquals(36.5, entry.prevApr!!, 0.0)
        assertEquals(entry.balanceBefore, entry.balanceAfter, 0.0)
        assertEquals(20.0, debt(id).apr, 0.0)
        assertEquals(1000.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun renamingADebtWithoutChangingTheRateLeavesNoEntry() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5)
        advanceDays(30)

        repo.updateDebt(id, "Renamed", DebtType.CREDIT_CARD, 1000.0, 36.5, 75.0)

        assertTrue(rows().isEmpty())
        assertEquals(created(id), debt(id).lastAccrualDay)
    }

    private suspend fun created(id: Long) = debt(id).createdDay

    @Test
    fun aTypedBalanceOnAnUntouchedDebtRestartsInterestFromToday() = runBlocking<Unit> {
        // A statement figure already includes the interest accrued so far; it must not be charged a second time.
        val id = addCard(1000.0, 36.5)
        advanceDays(30)

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1024.5, 36.5, 50.0)

        assertEquals(1024.5, debt(id).currentBalance, 0.001)
        assertEquals(today.toEpochDay(), debt(id).lastAccrualDay)
        assertTrue(rows().isEmpty()) // no history yet, so this is a correction rather than an adjustment

        repo.logPayments(today, listOf(Allocation(id, 100.0)))
        assertEquals(0.0, rows().single().interestPortion, 0.0)
        assertEquals(924.5, debt(id).currentBalance, 0.001)
    }

    @Test
    fun whenTheBalanceAndRateChangeTogetherTheTypedBalanceWins() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5)
        repo.logPayments(today, listOf(Allocation(id, 100.0))) // gives the debt a history: 900
        advanceDays(30)

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 924.5, 0.0, 50.0) // statement figure and a promo rate

        val adjustment = rows().last()
        assertTrue(adjustment.isAdjustment)
        assertEquals(900.0, adjustment.balanceBefore, 0.001)
        assertEquals(924.5, adjustment.balanceAfter, 0.001) // not 900 plus separately settled interest
        assertEquals(924.5, debt(id).currentBalance, 0.001)
        assertEquals(2, rows().size)
    }

    // ---------- "already paid off %" ----------

    @Test
    fun addingADebtWithAPercentagePaidDerivesTheOriginalAndEstimatesInterest() = runBlocking<Unit> {
        val id = repo.addDebt("Car", DebtType.AUTO_LOAN, 9000.0, 6.5, 250.0, percentPaid = 32.25)

        val d = debt(id)
        assertEquals(13284.13, d.originalBalance, 0.01)
        assertEquals(0.3225f, d.progress, 0.0001f)
        assertEquals(1385.59, d.priorInterestPaid, 0.5)
    }

    @Test
    fun withoutAPercentageThereIsNoPriorInterest() = runBlocking<Unit> {
        val id = repo.addDebt("Car", DebtType.AUTO_LOAN, 9000.0, 6.5, 250.0)

        assertEquals(9000.0, debt(id).originalBalance, 0.0)
        assertEquals(0.0, debt(id).priorInterestPaid, 0.0)
    }

    @Test
    fun whenTheMinimumNeverCoveredTheInterestNoEstimateIsMade() = runBlocking<Unit> {
        val id = repo.addDebt("Visa", DebtType.CREDIT_CARD, 4200.0, 22.9, 110.0, percentPaid = 30.0)

        assertEquals(6000.0, debt(id).originalBalance, 0.01)
        assertEquals(0.0, debt(id).priorInterestPaid, 0.0)
    }

    @Test
    fun editingThePercentageOfADebtWithHistoryEstimatesFromTheTrackedStartingBalance() = runBlocking<Unit> {
        val id = repo.addDebt("Car", DebtType.AUTO_LOAN, 4200.0, 6.5, 250.0)
        repo.logPayments(today, listOf(Allocation(id, 250.0))) // first tracked row starts from 4,200

        repo.updateDebt(id, "Car", DebtType.AUTO_LOAN, 3950.0, 6.5, 250.0, percentPaid = 50.0)

        val d = debt(id)
        assertEquals(7900.0, d.originalBalance, 0.01)
        // Only the time before tracking began (7,900 -> 4,200) is estimated; payments logged since are already counted.
        val expected = estimateInterestPaid(7900.0, 4200.0, 6.5, 250.0)!!.interest
        assertEquals(expected, d.priorInterestPaid, 0.01)
    }

    @Test
    fun resavingTheShownPercentageOnADebtWithHistoryLeavesTheAdjustmentRulesInCharge() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0))) // 900 of 1,000: shown as 10%

        // New charges take the balance to 1,500 while the form still says 10%: the original grows to 1,500,
        // rather than being re-derived from the percentage (which would give 1,666.67).
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1500.0, 0.0, 50.0, percentPaid = 10.0)

        assertEquals(1500.0, debt(id).originalBalance, 0.001)
    }

    @Test
    fun aPercentageEqualToTheRoundedShownOneIsNotAnEdit() = runBlocking<Unit> {
        // 10,000 original, 6,875.51 left: 31.2449%, shown (and re-submitted) as 31.24.
        val id = repo.addDebt("Loan", DebtType.PERSONAL_LOAN, 10000.0, 0.0, 300.0)
        repo.logPayments(today, listOf(Allocation(id, 3124.49)))
        assertEquals(6875.51, debt(id).currentBalance, 0.001)

        repo.updateDebt(id, "Loan", DebtType.PERSONAL_LOAN, 6875.51, 0.0, 300.0, percentPaid = 31.24)

        assertEquals(10000.0, debt(id).originalBalance, 0.001)
    }

    @Test
    fun aDifferentPercentageOnADebtWithHistoryReplacesTheOriginal() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0)))

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 900.0, 0.0, 50.0, percentPaid = 40.0)

        assertEquals(1500.0, debt(id).originalBalance, 0.01)
    }

    @Test
    fun clearingThePercentageOfAnUntouchedDebtResetsOriginalAndPriorInterest() = runBlocking<Unit> {
        val id = repo.addDebt("Car", DebtType.AUTO_LOAN, 9000.0, 6.5, 250.0, percentPaid = 32.25)

        repo.updateDebt(id, "Car", DebtType.AUTO_LOAN, 9000.0, 6.5, 250.0, percentPaid = 0.0)

        assertEquals(9000.0, debt(id).originalBalance, 0.001)
        assertEquals(0.0, debt(id).priorInterestPaid, 0.0)
    }

    // ---------- this month's payment already made ----------

    private fun thisMonth() = com.avalanche.app.domain.monthCode(java.time.YearMonth.from(today))

    @Test
    fun addingADebtCanMarkThisMonthsPaymentAsAlreadyMade() = runBlocking<Unit> {
        val marked = repo.addDebt("Visa", DebtType.CREDIT_CARD, 500.0, 20.0, 25.0, paidThisMonth = true)
        val plain = repo.addDebt("Car", DebtType.AUTO_LOAN, 500.0, 5.0, 25.0)

        assertEquals(thisMonth(), debt(marked).paidMonth)
        assertNull(debt(plain).paidMonth)
    }

    @Test
    fun editingCanSetClearOrLeaveTheMarker() = runBlocking<Unit> {
        val id = addCard(500.0, 0.0)

        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 500.0, 0.0, 50.0, paidThisMonth = true)
        assertEquals(thisMonth(), debt(id).paidMonth)

        repo.updateDebt(id, "Renamed", DebtType.CREDIT_CARD, 500.0, 0.0, 50.0) // not given: left as it is
        assertEquals(thisMonth(), debt(id).paidMonth)

        repo.updateDebt(id, "Renamed", DebtType.CREDIT_CARD, 500.0, 0.0, 50.0, paidThisMonth = false)
        assertNull(debt(id).paidMonth)
    }

    @Test
    fun theMarkerStopsMatchingWhenTheMonthChanges() = runBlocking<Unit> {
        val id = repo.addDebt("Visa", DebtType.CREDIT_CARD, 500.0, 0.0, 25.0, paidThisMonth = true)
        val d = debt(id)
        val nextMonth = java.time.YearMonth.from(today).plusMonths(1)

        assertTrue(com.avalanche.app.domain.isPaidThisMonth(d, emptyList(), java.time.YearMonth.from(today)))
        assertFalse(com.avalanche.app.domain.isPaidThisMonth(d, emptyList(), nextMonth))
    }

    @Test
    fun loggedPaymentsMarkTheDebtPaidWithoutStoringAnything() = runBlocking<Unit> {
        val id = addCard(1000.0, 0.0, 50.0)
        repo.logPayments(today, listOf(Allocation(id, 50.0)))

        val ids = com.avalanche.app.domain.paidThisMonthIds(db.debtDao().getAll(), rows(), java.time.YearMonth.from(today))

        assertEquals(setOf(id), ids)
        assertNull(debt(id).paidMonth)

        repo.undoLatest(id) // undoing the payment un-marks the debt again, since nothing was stored
        assertTrue(com.avalanche.app.domain.paidThisMonthIds(db.debtDao().getAll(), rows(), java.time.YearMonth.from(today)).isEmpty())
    }

    @Test
    fun theMarkerSurvivesABackupRoundTrip() = runBlocking<Unit> {
        val id = repo.addDebt("Visa", DebtType.CREDIT_CARD, 500.0, 0.0, 25.0, paidThisMonth = true)

        repo.importJson(repo.exportJson())

        assertEquals(thisMonth(), debt(id).paidMonth)
    }
    // ---------- deleting and backups ----------

    @Test
    fun deletingADebtRemovesItsPayments() = runBlocking<Unit> {
        val keep = addCard(500.0, 0.0)
        val drop = addCard(500.0, 0.0)
        repo.logPayments(today, listOf(Allocation(keep, 10.0), Allocation(drop, 10.0)))

        repo.deleteDebt(drop)

        assertEquals(listOf(keep), rows().map { it.debtId })
        assertNull(db.debtDao().get(drop))
    }

    @Test
    fun exportThenImportReproducesEverythingIncludingSettings() = runBlocking<Unit> {
        settings.update { it.copy(currency = "EUR", extraMonthly = 75.5) }
        val a = repo.addDebt("Car", DebtType.AUTO_LOAN, 9000.0, 6.5, 250.0, percentPaid = 32.25)
        val b = addCard(800.0, 20.0)
        advanceDays(15)
        repo.logPayments(today, listOf(Allocation(a, 300.0), Allocation(b, 40.0)))
        repo.updateDebt(b, "Card", DebtType.CREDIT_CARD, 700.0, 20.0, 50.0)
        val debtsBefore = db.debtDao().getAll()
        val paymentsBefore = rows()
        val json = repo.exportJson()

        repo.deleteAll()
        settings.update { it.copy(currency = "USD", extraMonthly = 0.0) }
        assertTrue(db.debtDao().getAll().isEmpty())
        repo.importJson(json)

        assertEquals(debtsBefore, db.debtDao().getAll())
        assertEquals(paymentsBefore, rows())
        assertEquals("EUR", settings.settings.value.currency)
        assertEquals(75.5, settings.settings.value.extraMonthly, 0.0)
    }

    @Test
    fun anInvalidBackupIsRejectedWithoutTouchingExistingData() = runBlocking<Unit> {
        val id = addCard(500.0, 0.0)

        for (bad in listOf("not json", "{}", "{\"app\":\"Avalanche\",\"version\":99,\"debts\":[]}")) {
            try {
                repo.importJson(bad)
                fail("should have rejected: $bad")
            } catch (expected: BackupException) {
                assertNotNull(expected.message)
            }
        }
        assertEquals(500.0, debt(id).currentBalance, 0.001)
    }

    @Test
    fun aRestoredBackupCanStillUndoARateChange() = runBlocking<Unit> {
        val id = addCard(1000.0, 36.5)
        advanceDays(30)
        repo.updateDebt(id, "Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)

        repo.importJson(repo.exportJson())
        repo.undoLatest(id)

        assertEquals(36.5, debt(id).apr, 0.0)
    }

    @Test
    fun aBackupWithoutThePriorInterestFieldImportsAsZero() = runBlocking<Unit> {
        val id = repo.addDebt("Car", DebtType.AUTO_LOAN, 9000.0, 6.5, 250.0, percentPaid = 32.25)
        // Simulate a file exported before the field existed.
        val old = repo.exportJson().lines().filterNot { it.contains("priorInterestPaid") }.joinToString("\n")
            .replace(Regex(",(\\s*)\\}"), "$1}")

        repo.importJson(old)

        assertEquals(0.0, debt(id).priorInterestPaid, 0.0)
        assertEquals(9000.0, debt(id).currentBalance, 0.0)
    }

    // ---------- savings ----------

    private val savings = SavingsSetup(balance = 2500.5, monthlyIncome = 4200.0, percentSaved = 12.5, apy = 4.35)

    @Test
    fun deleteAllAlsoForgetsTheSavingsDetailsButKeepsOtherSettings() = runBlocking<Unit> {
        settings.update { it.copy(currency = "EUR", savings = savings) }
        addCard()

        repo.deleteAll()

        assertTrue(db.debtDao().getAll().isEmpty())
        assertNull(settings.settings.value.savings)
        assertEquals("EUR", settings.settings.value.currency)
    }

    @Test
    fun theSavingsDetailsSurviveABackupRoundTrip() = runBlocking<Unit> {
        settings.update { it.copy(savings = savings) }
        addCard()
        val json = repo.exportJson()

        settings.update { it.copy(savings = null) }
        repo.importJson(json)

        assertEquals(savings, settings.settings.value.savings)
    }
}
