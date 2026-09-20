package com.avalanche.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.avalanche.app.AvalancheApplication
import com.avalanche.app.MainActivity
import com.avalanche.app.R
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** When the monthly reminder should fire. Kept pure so it can be unit tested. */
object ReminderRule {
    const val HOUR = 9

    /**
     * This month's reminder is due once its moment (the chosen day at [HOUR]:00) has passed, provided that:
     *  - it hasn't already fired this month ([lastFired]),
     *  - the moment came after the schedule was last set ([scheduledAt], updated when reminders are enabled
     *    or the day is changed). That stops enabling reminders on the 15th from firing at once for the 10th,
     *    without suppressing a reminder the user then moves to the 20th, and
     *  - it's at least [HOUR]:00 now. A reminder the phone missed on its day is caught up later, but never in
     *    the small hours of a following day.
     */
    fun isDue(now: LocalDateTime, reminderDay: Int, lastFired: YearMonth?, scheduledAt: LocalDateTime? = null): Boolean {
        val month = YearMonth.from(now)
        if (lastFired == month) return false
        val due = month.atDay(reminderDay.coerceIn(1, month.lengthOfMonth())).atTime(HOUR, 0)
        return !now.isBefore(due) && now.hour >= HOUR && (scheduledAt == null || due.isAfter(scheduledAt))
    }
}

object Notifications {
    private const val CHANNEL_REMINDER = "monthly_reminder"
    private const val CHANNEL_ACHIEVEMENT = "debt_paid_off"
    private const val WORK_NAME = "reminder_check"
    private const val ID_REMINDER = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Monthly reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A monthly nudge to log the payments you made."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACHIEVEMENT, "Debt paid off", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A celebration when a debt reaches zero."
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Returns whether the notification was actually posted (false when notifications are blocked). */
    fun showReminder(context: Context): Boolean {
        if (!canPost(context)) return false
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time to log your payments")
            .setContentText("Record what you paid this month to keep your payoff plan accurate.")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // canPost() checked above
        NotificationManagerCompat.from(context).notify(ID_REMINDER, n)
        return true
    }

    fun showDebtPaidOff(context: Context, debtName: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ACHIEVEMENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Paid off: $debtName 🎉")
            .setContentText("One debt down. Your freed-up payment now goes to the next one.")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // canPost() checked above
        NotificationManagerCompat.from(context).notify(debtName.hashCode(), n)
    }

    /** Schedules a periodic check; the worker decides whether the reminder is due today. */
    fun scheduleReminderChecks(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelReminderChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AvalancheApplication).container
        val settings = container.settings
        val s = settings.settings.value
        if (!s.remindersEnabled) return Result.success()
        val now = LocalDateTime.now()
        if (ReminderRule.isDue(now, s.reminderDay, settings.lastReminderMonth, settings.reminderScheduledAt)) {
            // The month only counts as reminded once the notification went out (or there was nothing to remind
            // about), so a temporarily blocked notification is retried by the next check instead of lost.
            val reminded = !container.repository.hasActiveDebts() || Notifications.showReminder(applicationContext)
            if (reminded) settings.lastReminderMonth = YearMonth.from(now)
        }
        return Result.success()
    }
}
