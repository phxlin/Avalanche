package com.avalanche.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avalanche.app.data.Allocation
import com.avalanche.app.data.AppDatabase
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.DebtType
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.ui.debts.EditDebtViewModel
import com.avalanche.app.ui.payments.LogPaymentViewModel
import com.avalanche.app.ui.plan.PlanViewModel
import com.avalanche.app.ui.settings.SettingsViewModel
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * View-model behaviour on top of the real repository and an in-memory Room database. The view models
 * launch on the main dispatcher, so results are awaited rather than asserted immediately.
 */
@RunWith(AndroidJUnit4::class)
class ViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: DebtRepository
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("view_model_test_settings", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        settings = SettingsStore(prefs)
        repo = DebtRepository(db, settings, {}, { today })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) fail("Timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** Makes writes to one table fail, standing in for a disk or database error. (Room reopens a closed in-memory database, so close() wouldn't.) */
    private fun breakTable(name: String) = db.openHelper.writableDatabase.execSQL("DROP TABLE $name")

    private fun loadedEditor(debtId: Long?): EditDebtViewModel =
        EditDebtViewModel(repo, debtId).also { vm -> waitUntil(what = "the edit form to load") { !vm.loading } }

    // ---------- EditDebtViewModel ----------

    @Test
    fun theAprFieldIsPrefilledWithoutRoundingBelowItsPrecision() = runBlocking<Unit> {
        // Regression: two-decimal prefilling turned 19.875 into 19.88, and saving then changed the rate.
        val id = repo.addDebt("Loan", DebtType.PERSONAL_LOAN, 1000.0, 19.875, 50.0)

        val vm = loadedEditor(id)

        assertEquals("19.875", vm.apr)
    }

    @Test
    fun savingAnUntouchedFormOnADebtWithHistoryDoesNotRederiveTheOriginal() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)
        repo.logPayments(today, listOf(Allocation(id, 100.0))) // 900 of 1,000: the form shows 10%
        val vm = loadedEditor(id)
        assertEquals("10", vm.percentPaid)

        // New charges: only the balance changes. The original must grow to 1,500, not become 1,500 / 0.9.
        vm.balance = "1500"
        var done = false
        vm.save { done = true }
        waitUntil(what = "the save to finish") { done }

        assertEquals(1500.0, db.debtDao().get(id)!!.originalBalance, 0.001)
    }

    @Test
    fun aFailedSaveShowsAnErrorInsteadOfClosingTheForm() {
        val vm = loadedEditor(null)
        vm.name = "Visa"
        vm.balance = "500"
        vm.apr = "19.99"
        vm.minPayment = "25"
        breakTable("debts") // saving now hits a database error

        var done = false
        vm.save { done = true }
        waitUntil(what = "the save to fail") { !vm.saving && vm.saveError != null }

        assertNotNull(vm.saveError)
        assertFalse(done)
    }

    @Test
    fun addingADebtWithTheAlreadyPaidBoxTickedStoresTheMarker() {
        val vm = loadedEditor(null)
        vm.name = "Visa"
        vm.balance = "500"
        vm.apr = "19.99"
        vm.minPayment = "25"
        vm.paidThisMonth = true

        var done = false
        vm.save { done = true }
        waitUntil(what = "the save to finish") { done }

        val saved = runBlocking { db.debtDao().getAll() }.single()
        assertEquals(com.avalanche.app.domain.monthCode(java.time.YearMonth.from(today)), saved.paidMonth)
    }

    @Test
    fun theBoxShowsTickedAndLockedWhenLoggedPaymentsAlreadyCoverTheMinimum() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)
        repo.logPayments(today, listOf(Allocation(id, 50.0)))

        val vm = loadedEditor(id)

        assertTrue(vm.coveredByLoggedPayments)
        assertTrue(vm.paidThisMonth)
    }

    @Test
    fun savingAFormCoveredByLoggedPaymentsDoesNotStoreAMarker() = runBlocking<Unit> {
        // Otherwise undoing that payment later would leave the debt wrongly marked as paid.
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0)
        repo.logPayments(today, listOf(Allocation(id, 50.0)))
        val vm = loadedEditor(id)
        vm.name = "Card renamed"

        var done = false
        vm.save { done = true }
        waitUntil(what = "the save to finish") { done }

        assertNull(db.debtDao().get(id)!!.paidMonth)
    }

    @Test
    fun theBoxShowsTheStoredMarkerAndCanBeCleared() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 1000.0, 0.0, 50.0, paidThisMonth = true)
        val vm = loadedEditor(id)
        assertTrue(vm.paidThisMonth)
        assertFalse(vm.coveredByLoggedPayments)

        vm.paidThisMonth = false
        var done = false
        vm.save { done = true }
        waitUntil(what = "the save to finish") { done }

        assertNull(db.debtDao().get(id)!!.paidMonth)
    }

    // ---------- LogPaymentViewModel ----------

    @Test
    fun aFailedPaymentSaveShowsAnErrorAndLetsYouRetry() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 500.0, 0.0, 25.0)
        val active = repo.debts.first()
        val vm = LogPaymentViewModel(repo, null, emptyMap())
        vm.setAmount(id, "25")
        breakTable("payments")

        var result: Any? = null
        vm.save(active) { result = it }
        waitUntil(what = "the save to fail") { !vm.saving && vm.formError != null }

        assertNull(result)
        assertEquals("Couldn't save the payment. Please try again.", vm.formError)
        assertFalse(vm.saving)
    }

    @Test
    fun aDateBeforeADebtsLastEntryIsFlaggedOnThatDebtAndBlocksSaving() = runBlocking<Unit> {
        val id = repo.addDebt("Card", DebtType.CREDIT_CARD, 500.0, 0.0, 25.0) // its accrual point is today
        val active = repo.debts.first()
        val vm = LogPaymentViewModel(repo, null, emptyMap())
        vm.setAmount(id, "25")
        vm.date = today.minusDays(3)

        assertNotNull(vm.rowError(active.single()))
        var result: Any? = null
        vm.save(active) { result = it }

        assertEquals("Fix the amounts marked in red first.", vm.formError)
        assertNull(result)
        assertEquals(500.0, db.debtDao().get(id)!!.currentBalance, 0.001)

        vm.date = today
        assertNull(vm.rowError(active.single()))
    }

    // ---------- SettingsViewModel ----------

    @Test
    fun theSettingsScreenKnowsWhetherThereIsAnythingToReplace() = runBlocking<Unit> {
        val vm = SettingsViewModel(repo, settings, ApplicationProvider.getApplicationContext())
        try {
            assertEquals(false, withTimeout(5_000) { vm.hasData.first { it != null } }) // no debts yet

            repo.addDebt("Card", DebtType.CREDIT_CARD, 500.0, 0.0, 25.0)

            assertEquals(true, withTimeout(5_000) { vm.hasData.first { it == true } })
        } finally {
            // hasData keeps its query alive for a few seconds after the last collector; stop it before tearDown
            // closes the database, or the pending re-query fails with "connection pool has been closed".
            vm.viewModelScope.cancel()
        }
    }

    // ---------- PlanViewModel ----------

    @Test
    fun theExtraFieldFollowsChangesMadeElsewhere() {
        val vm = PlanViewModel(repo, settings)
        assertEquals("", vm.extraText)

        settings.update { it.copy(extraMonthly = 50.0) } // for example a backup import

        waitUntil(what = "the field to catch up") { vm.extraText == "50" }
    }

    @Test
    fun typingIsNotRewrittenWhileTheFieldAlreadyMeansTheSameAmount() {
        val vm = PlanViewModel(repo, settings)

        vm.onExtraChanged("12.")
        Thread.sleep(300) // long enough for the settings collector to see the new amount

        assertEquals(12.0, settings.settings.value.extraMonthly, 0.0)
        assertEquals("12.", vm.extraText)
        assertTrue(vm.extraText.endsWith("."))
    }
}
