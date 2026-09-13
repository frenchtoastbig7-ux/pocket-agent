package dev.pocketagent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleSpecTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)

    // 2026-09-14 is a Monday.
    private val mondayMorning = at(14, 8)

    @Test
    fun dailyLaterToday() = assertEquals(at(14, 9, 30), ScheduleSpec.nextAfter("daily 09:30", mondayMorning))

    @Test
    fun dailyAlreadyPassedRollsToTomorrow() = assertEquals(at(15, 7), ScheduleSpec.nextAfter("daily 07:00", mondayMorning))

    @Test
    fun dailyAtExactlyNowIsTomorrow() = assertEquals(at(15, 8), ScheduleSpec.nextAfter("daily 08:00", mondayMorning))

    @Test
    fun weekdaysSkipWeekend() = assertEquals(at(21, 8), ScheduleSpec.nextAfter("weekdays 08:00", at(18, 18)))

    @Test
    fun weeklyFindsNamedDay() = assertEquals(at(20, 10), ScheduleSpec.nextAfter("weekly sunday 10:00", mondayMorning))

    @Test
    fun everyInterval() = assertEquals(at(14, 10), ScheduleSpec.nextAfter("every 2h", mondayMorning))

    @Test
    fun everyBelowMinimumIsRejected() {
        assertNull(ScheduleSpec.nextAfter("every 10m", mondayMorning))
        assertFalse(ScheduleSpec.isValid("every 10m"))
        assertTrue(ScheduleSpec.isValid("every 15 minutes"))
    }

    @Test
    fun onceInFutureAndPast() {
        assertEquals(at(20, 9), ScheduleSpec.nextAfter("once 2026-09-20T09:00", mondayMorning))
        assertNull(ScheduleSpec.nextAfter("once 2026-09-01T09:00", mondayMorning))
    }

    @Test
    fun invalidTimesAndFormats() {
        assertFalse(ScheduleSpec.isValid("daily 25:00"))
        assertFalse(ScheduleSpec.isValid("0 7 * * *"))
        assertNull(ScheduleSpec.nextAfter("daily 25:00", mondayMorning))
    }
}
