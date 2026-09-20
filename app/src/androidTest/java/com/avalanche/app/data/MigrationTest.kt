package com.avalanche.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the user's data across schema upgrades: rows survive and Room's own validation passes. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migratingFromVersion1KeepsDebtsAndPaymentsAndDefaultsPriorInterestToZero() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO debts (id, name, type, originalBalance, currentBalance, apr, minPayment, createdDay, lastAccrualDay, paidOffDay) " +
                    "VALUES (7, 'Visa', 'CREDIT_CARD', 8400.0, 4200.0, 22.9, 110.0, 20000, 20030, NULL)",
            )
            execSQL(
                "INSERT INTO payments (id, sessionId, debtId, day, amount, interestPortion, principalPortion, balanceBefore, balanceAfter, " +
                    "prevAccrualDay, prevOriginalBalance, prevPaidOffDay, kind) " +
                    "VALUES (3, 111, 7, 20030, 300.0, 40.0, 260.0, 4460.0, 4200.0, 20000, 8400.0, NULL, 'PAYMENT')",
            )
            close()
        }

        // Runs the first migration and validates the result against the exported v2 schema (including the column default).
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)
        migrated.query("SELECT name, currentBalance, priorInterestPaid FROM debts WHERE id = 7").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Visa", c.getString(0))
            assertEquals(4200.0, c.getDouble(1), 0.0)
            assertEquals(0.0, c.getDouble(2), 0.0)
        }
        migrated.query("SELECT COUNT(*) FROM payments WHERE debtId = 7").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        migrated.close()

        // And the app's real database class can open the migrated file and read the rows through the DAOs.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
        try {
            val debt = runBlocking { room.debtDao().get(7) }!!
            assertEquals("Visa", debt.name)
            assertEquals(0.0, debt.priorInterestPaid, 0.0)
            val payment = runBlocking { room.paymentDao().getAll() }.single()
            assertEquals(null, payment.prevApr)
        } finally {
            room.close()
        }
    }

    @Test
    fun migratingFromVersion2AddsAnEmptyPrevAprToExistingPayments() {
        helper.createDatabase(TEST_DB_V2, 2).apply {
            execSQL(
                "INSERT INTO debts (id, name, type, originalBalance, currentBalance, apr, minPayment, createdDay, lastAccrualDay, paidOffDay, priorInterestPaid) " +
                    "VALUES (4, 'Car', 'AUTO_LOAN', 9000.0, 9000.0, 6.5, 250.0, 20000, 20000, NULL, 120.5)",
            )
            execSQL(
                "INSERT INTO payments (id, sessionId, debtId, day, amount, interestPortion, principalPortion, balanceBefore, balanceAfter, " +
                    "prevAccrualDay, prevOriginalBalance, prevPaidOffDay, kind) " +
                    "VALUES (9, 5, 4, 20010, 0.0, 0.0, 0.0, 9000.0, 9030.0, 20000, 9000.0, NULL, 'ADJUSTMENT')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_V2, 3, true, AppDatabase.MIGRATION_2_3)
        migrated.query("SELECT balanceAfter, prevApr FROM payments WHERE id = 9").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(9030.0, c.getDouble(0), 0.0)
            assertEquals(true, c.isNull(1))
        }
        migrated.query("SELECT priorInterestPaid FROM debts WHERE id = 4").use { c ->
            c.moveToFirst()
            assertEquals(120.5, c.getDouble(0), 0.0)
        }
        migrated.close()
    }

    @Test
    fun migratingFromVersion3AddsAnEmptyPaidMonthToExistingDebts() {
        helper.createDatabase(TEST_DB_V3, 3).apply {
            execSQL(
                "INSERT INTO debts (id, name, type, originalBalance, currentBalance, apr, minPayment, createdDay, lastAccrualDay, paidOffDay, priorInterestPaid) " +
                    "VALUES (2, 'Loan', 'PERSONAL_LOAN', 3000.0, 2500.0, 8.5, 150.0, 20000, 20010, NULL, 40.0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_V3, 4, true, AppDatabase.MIGRATION_3_4)
        migrated.query("SELECT name, currentBalance, priorInterestPaid, paidMonth FROM debts WHERE id = 2").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Loan", c.getString(0))
            assertEquals(2500.0, c.getDouble(1), 0.0)
            assertEquals(40.0, c.getDouble(2), 0.0)
            assertEquals(true, c.isNull(3))
        }
        migrated.close()
    }
    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB_V2 = "migration-test-v2"
        const val TEST_DB_V3 = "migration-test-v3"
    }
}
