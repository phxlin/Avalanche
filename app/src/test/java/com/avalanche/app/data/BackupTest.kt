package com.avalanche.app.data

import com.avalanche.app.domain.SavingsSetup
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private fun debt() = DebtEntity(
        id = 1, name = "Card", type = "CREDIT_CARD", originalBalance = 1000.0, currentBalance = 900.0,
        apr = 0.0, minPayment = 25.0, createdDay = 20000, lastAccrualDay = 20030,
    )

    private fun adjustment(prevApr: Double?) = PaymentEntity(
        id = 1, sessionId = 1, debtId = 1, day = 20030, amount = 0.0, interestPortion = 0.0, principalPortion = 0.0,
        balanceBefore = 900.0, balanceAfter = 930.0, prevAccrualDay = 20000, prevOriginalBalance = 1000.0,
        kind = KIND_ADJUSTMENT, prevApr = prevApr,
    )

    @Test
    fun theAprAnAdjustmentReplacedSurvivesABackupRoundTrip() {
        val json = Backup.toJson(listOf(debt()), listOf(adjustment(36.5)), AppSettings("USD"))

        assertEquals(36.5, Backup.parse(json).payments.single().prevApr!!, 0.0)
    }

    @Test
    fun entriesWithoutAPreviousAprImportAsNull() {
        val json = Backup.toJson(listOf(debt()), listOf(adjustment(null)), AppSettings("USD"))

        assertNull(Backup.parse(json).payments.single().prevApr)
    }

    @Test
    fun aNegativePreviousAprIsRejected() {
        val json = Backup.toJson(listOf(debt()), listOf(adjustment(-1.0)), AppSettings("USD"))

        try {
            Backup.parse(json)
            fail("should have rejected a negative rate")
        } catch (expected: BackupException) {
            // expected
        }
    }

    @Test
    fun theAlreadyPaidMonthSurvivesABackupRoundTripAndOldFilesImportWithoutIt() {
        val marked = debt().copy(paidMonth = 24_315)
        assertEquals(24_315, Backup.parse(Backup.toJson(listOf(marked), emptyList(), AppSettings("USD"))).debts.single().paidMonth)

        assertNull(Backup.parse(Backup.toJson(listOf(debt()), emptyList(), AppSettings("USD"))).debts.single().paidMonth)
    }
    @Test
    fun aFileWithinTheLimitIsReadInFull() {
        val text = "x".repeat(50_000)

        assertEquals(text, Backup.readBounded(StringReader(text)))
    }

    @Test
    fun aFileOverTheLimitIsRejectedWithoutBeingReadToTheEnd() {
        val reader = StringReader("x".repeat(Backup.MAX_CHARS + 1))

        try {
            Backup.readBounded(reader)
            fail("should have rejected an oversized file")
        } catch (expected: BackupException) {
            assertEquals("This file is too large to be an Avalanche backup.", expected.message)
        }
    }

    @Test
    fun aFileExactlyAtTheLimitIsAccepted() {
        val text = "x".repeat(Backup.MAX_CHARS)

        assertEquals(Backup.MAX_CHARS, Backup.readBounded(StringReader(text)).length)
    }

    // ---------- savings ----------

    private val savings = SavingsSetup(balance = 2500.5, monthlyIncome = 4200.0, percentSaved = 12.5, apy = 4.35)

    private fun restored(json: String, current: AppSettings = AppSettings("USD")) =
        Backup.applyTo(current, Backup.parse(json).settings)

    @Test
    fun theSavingsDetailsSurviveABackupRoundTrip() {
        val json = Backup.toJson(listOf(debt()), emptyList(), AppSettings("USD", savings = savings))

        assertEquals(savings, restored(json).savings)
    }

    @Test
    fun aBackupMadeWithoutSavingsLeavesTheSavingsHereAlone() {
        // Backups from before savings existed have no such block; importing one shouldn't erase what was set up since.
        val json = Backup.toJson(listOf(debt()), emptyList(), AppSettings("USD"))

        assertEquals(savings, restored(json, AppSettings("USD", savings = savings)).savings)
        assertNull(restored(json).savings)
    }

    @Test
    fun anInvalidSavingsBlockIsIgnoredRatherThanRejectingTheBackup() {
        val good = Backup.toJson(listOf(debt()), emptyList(), AppSettings("USD", savings = savings))
        val tooMuch = good.replace("\"percentSaved\": 12.5", "\"percentSaved\": 250")
        val missing = good.lines().filterNot { it.contains("monthlyIncome") }.joinToString("\n")

        assertTrue(tooMuch != good && missing != good)
        assertNull(restored(tooMuch).savings)
        assertNull(restored(missing).savings)
        assertEquals(savings, restored(tooMuch, AppSettings("USD", savings = savings)).savings)
    }
}
