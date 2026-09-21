package com.avalanche.app.data

import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.domain.Strategy
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun extraAmountKeepsExactCentsAcrossRestarts() {
        val prefs = FakeSharedPreferences()
        SettingsStore(prefs).update { it.copy(extraMonthly = 200.10) }
        // Regression: this used to go through a Float and read back as 200.10000610351562.
        assertEquals(200.10, SettingsStore(prefs).settings.value.extraMonthly, 0.0)
        SettingsStore(prefs).update { it.copy(extraMonthly = 1234567.89) }
        assertEquals(1234567.89, SettingsStore(prefs).settings.value.extraMonthly, 0.0)
    }

    @Test
    fun aFloatSavedByVersionOnePointZeroIsReadBackCleanlyAndReplaced() {
        val prefs = FakeSharedPreferences().apply { values["extra_monthly"] = 200.1f }
        val store = SettingsStore(prefs)
        assertEquals(200.1, store.settings.value.extraMonthly, 0.0)

        store.update { it.copy(strategy = Strategy.SNOWBALL) }
        assertFalse("legacy key should be removed once rewritten", prefs.contains("extra_monthly"))
        assertEquals(200.1, SettingsStore(prefs).settings.value.extraMonthly, 0.0)
    }

    @Test
    fun corruptOrNegativeExtraFallsBackToZero() {
        assertEquals(0.0, SettingsStore(FakeSharedPreferences().apply { values["extra_monthly_text"] = "abc" }).settings.value.extraMonthly, 0.0)
        assertEquals(0.0, SettingsStore(FakeSharedPreferences().apply { values["extra_monthly_text"] = "-50" }).settings.value.extraMonthly, 0.0)
        assertEquals(0.0, SettingsStore(FakeSharedPreferences()).settings.value.extraMonthly, 0.0)
    }

    @Test
    fun otherSettingsRoundTripAndAreClamped() {
        val prefs = FakeSharedPreferences()
        SettingsStore(prefs).update {
            it.copy(currency = "EUR", strategy = Strategy.SNOWBALL, remindersEnabled = true, reminderDay = 99, celebrationsEnabled = false)
        }
        val s = SettingsStore(prefs).settings.value
        assertEquals("EUR", s.currency)
        assertEquals(Strategy.SNOWBALL, s.strategy)
        assertEquals(true, s.remindersEnabled)
        assertEquals(28, s.reminderDay)
        assertEquals(false, s.celebrationsEnabled)
    }

    @Test
    fun reminderBookkeepingRoundTrips() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)
        assertNull(store.lastReminderMonth)
        assertNull(store.reminderScheduledAt)

        store.lastReminderMonth = YearMonth.of(2026, 3)
        store.reminderScheduledAt = LocalDateTime.of(2026, 3, 16, 10, 30)
        assertEquals(YearMonth.of(2026, 3), SettingsStore(prefs).lastReminderMonth)
        assertEquals(LocalDateTime.of(2026, 3, 16, 10, 30), SettingsStore(prefs).reminderScheduledAt)
    }

    @Test
    fun savingsRoundTripWithExactCents() {
        val prefs = FakeSharedPreferences()
        val setup = SavingsSetup(balance = 12345.67, monthlyIncome = 4210.55, percentSaved = 12.25, apy = 4.35)

        SettingsStore(prefs).update { it.copy(savings = setup) }

        assertEquals(setup, SettingsStore(prefs).settings.value.savings)
    }

    @Test
    fun savingsAreNotSetUpByDefaultAndRemovingThemForgetsEverything() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)
        assertNull(store.settings.value.savings)

        store.update { it.copy(savings = SavingsSetup(100.0, 3000.0, 10.0, 4.0)) }
        assertNotNull(SettingsStore(prefs).settings.value.savings)

        store.update { it.copy(savings = null) }
        assertNull(SettingsStore(prefs).settings.value.savings)
        assertFalse(prefs.contains("savings_income_text"))
    }

    @Test
    fun corruptOrOutOfRangeSavingsReadBackAsNotSetUp() {
        fun withValue(key: String, value: String) = FakeSharedPreferences().apply {
            values["savings_balance_text"] = "100"
            values["savings_income_text"] = "3000"
            values["savings_percent_text"] = "10"
            values["savings_apy_text"] = "4"
            values[key] = value
        }

        assertNotNull(SettingsStore(withValue("savings_apy_text", "4")).settings.value.savings)
        assertNull(SettingsStore(withValue("savings_apy_text", "abc")).settings.value.savings)
        assertNull(SettingsStore(withValue("savings_percent_text", "500")).settings.value.savings)
        assertNull(SettingsStore(withValue("savings_income_text", "-1")).settings.value.savings)
        assertNull(SettingsStore(FakeSharedPreferences().apply { values["savings_balance_text"] = "100" }).settings.value.savings)
    }
}
