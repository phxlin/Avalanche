package com.avalanche.app

import android.app.Application
import android.content.Context
import com.avalanche.app.data.AppDatabase
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.notifications.Notifications

/** Hand-rolled dependency container; the app is small enough that a DI framework isn't worth its weight. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val repository = DebtRepository(
        db = AppDatabase.build(context),
        settings = settings,
        onDebtPaidOff = { debtName -> Notifications.showDebtPaidOff(context.applicationContext, debtName) },
    )
}

class AvalancheApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        if (container.settings.settings.value.remindersEnabled) Notifications.scheduleReminderChecks(this)
    }
}
