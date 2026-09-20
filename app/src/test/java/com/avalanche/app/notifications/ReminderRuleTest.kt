package com.avalanche.app.notifications

import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRuleTest {
    private fun at(month: Int, day: Int, hour: Int = 12, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute)

    @Test
    fun enablingAfterThisMonthsDayHasPassedDoesNotFireAtOnce() {
        // Enabled on the 15th for day 10: the 10th is already behind us, so wait for next month.
        val scheduled = at(3, 15, 9, 30)
        assertFalse(ReminderRule.isDue(at(3, 15, 10), 10, null, scheduled))
        assertFalse(ReminderRule.isDue(at(3, 30), 10, null, scheduled))
        assertTrue(ReminderRule.isDue(at(4, 10, 9), 10, null, scheduled))
    }

    @Test
    fun movingTheDayLaterInTheSameMonthStillFiresOnTheNewDay() {
        // Enabled on the 15th (day 10, skipped), then moved to the 20th on the 16th.
        val rescheduled = at(3, 16, 8)
        assertFalse(ReminderRule.isDue(at(3, 19, 15), 20, null, rescheduled))
        assertTrue(ReminderRule.isDue(at(3, 20, 9), 20, null, rescheduled))
    }

    @Test
    fun aReminderThatAlreadyFiredThisMonthNeverFiresTwice() {
        // Fired on the 1st, then the user moves the day to the 20th: no second reminder in March.
        val rescheduled = at(3, 5, 8)
        assertFalse(ReminderRule.isDue(at(3, 20, 12), 20, YearMonth.of(2026, 3), rescheduled))
        // ...but April is a new month.
        assertTrue(ReminderRule.isDue(at(4, 20, 12), 20, YearMonth.of(2026, 3), rescheduled))
    }

    @Test
    fun togglingRemindersOffAndOnKeepsTheFiredMarker() {
        assertFalse(ReminderRule.isDue(at(3, 28, 12), 1, YearMonth.of(2026, 3), at(3, 27)))
    }

    @Test
    fun firesFromNineOnTheChosenDayAndCatchesUpLater() {
        assertFalse(ReminderRule.isDue(at(3, 4, 12), 5, null))
        assertFalse(ReminderRule.isDue(at(3, 5, 8, 59), 5, null))
        assertTrue(ReminderRule.isDue(at(3, 5, 9, 0), 5, null))
        // The periodic check may run days late; it still fires once.
        assertTrue(ReminderRule.isDue(at(3, 9, 15), 5, YearMonth.of(2026, 2)))
    }

    @Test
    fun aMissedReminderIsCaughtUpFromNineNeverInTheSmallHours() {
        // The phone was off on the 5th. The check at 03:00 on the 6th must wait; from 09:00 it fires.
        assertFalse(ReminderRule.isDue(at(3, 6, 3), 5, null))
        assertFalse(ReminderRule.isDue(at(3, 6, 8, 59), 5, null))
        assertTrue(ReminderRule.isDue(at(3, 6, 9), 5, null))
        assertTrue(ReminderRule.isDue(at(3, 6, 23, 30), 5, null))
    }

    @Test
    fun aDayBeyondTheMonthsLengthIsClampedToItsLastDay() {
        // February 2026 has 28 days; a stored day of 31 must not throw.
        assertTrue(ReminderRule.isDue(at(2, 28, 10), 31, null))
        assertFalse(ReminderRule.isDue(at(2, 27, 10), 31, null))
    }
}
