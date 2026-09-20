package com.avalanche.app.data

import android.content.Context
import android.content.SharedPreferences
import com.avalanche.app.domain.Strategy
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val currency: String,
    val extraMonthly: Double = 0.0,
    val strategy: Strategy = Strategy.AVALANCHE,
    val remindersEnabled: Boolean = false,
    val reminderDay: Int = 1,
    val celebrationsEnabled: Boolean = true,
)

/** Small key-value store for preferences. Plain SharedPreferences keeps this dependency-free and fully local. */
class SettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("avalanche_settings", Context.MODE_PRIVATE))

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        currency = prefs.getString(KEY_CURRENCY, null)?.takeIf { isValidCurrency(it) } ?: deviceCurrency(),
        extraMonthly = loadExtra(),
        strategy = runCatching { Strategy.valueOf(prefs.getString(KEY_STRATEGY, null) ?: "") }.getOrDefault(Strategy.AVALANCHE),
        remindersEnabled = prefs.getBoolean(KEY_REMINDERS, false),
        reminderDay = prefs.getInt(KEY_REMINDER_DAY, 1).coerceIn(1, 28),
        celebrationsEnabled = prefs.getBoolean(KEY_CELEBRATE, true),
    )

    /**
     * The extra monthly amount is stored as text: a Float can't hold cents exactly (200.10 reads back as
     * 200.10000610...). Version 1.0 stored a Float under [KEY_EXTRA_LEGACY], so read that as a fallback.
     */
    private fun loadExtra(): Double {
        prefs.getString(KEY_EXTRA, null)?.toDoubleOrNull()?.let { return it.coerceAtLeast(0.0) }
        val legacy = prefs.all[KEY_EXTRA_LEGACY] as? Float ?: return 0.0
        return legacy.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString(KEY_CURRENCY, next.currency)
            .putString(KEY_EXTRA, next.extraMonthly.toString())
            .remove(KEY_EXTRA_LEGACY)
            .putString(KEY_STRATEGY, next.strategy.name)
            .putBoolean(KEY_REMINDERS, next.remindersEnabled)
            .putInt(KEY_REMINDER_DAY, next.reminderDay.coerceIn(1, 28))
            .putBoolean(KEY_CELEBRATE, next.celebrationsEnabled)
            .apply()
    }

    /** Month for which the reminder notification has already fired. */
    var lastReminderMonth: YearMonth?
        get() = prefs.getString(KEY_LAST_REMINDER, null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_LAST_REMINDER, value?.toString()).apply()

    /** When reminders were last enabled or their day last changed; earlier due moments never fire. */
    var reminderScheduledAt: LocalDateTime?
        get() = prefs.getString(KEY_REMINDER_SCHEDULED_AT, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_REMINDER_SCHEDULED_AT, value?.toString()).apply()

    companion object {
        private const val KEY_CURRENCY = "currency"
        private const val KEY_EXTRA = "extra_monthly_text"
        private const val KEY_EXTRA_LEGACY = "extra_monthly"
        private const val KEY_STRATEGY = "strategy"
        private const val KEY_REMINDERS = "reminders_enabled"
        private const val KEY_REMINDER_DAY = "reminder_day"
        private const val KEY_CELEBRATE = "celebrations_enabled"
        private const val KEY_LAST_REMINDER = "last_reminder_month"
        private const val KEY_REMINDER_SCHEDULED_AT = "reminder_scheduled_at"

        fun isValidCurrency(code: String) = runCatching { Currency.getInstance(code) }.isSuccess

        /** The currency of the device's region, falling back to USD when the locale has none. */
        fun deviceCurrency(): String =
            runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"
    }
}
