package com.lekaspos.core.time

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaysTest {

    private val kl = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

    private fun millis(tz: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int): Long {
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, m - 1, d, h, min)
        return c.timeInMillis
    }

    @Test
    fun epochDayUsesTheStoreTimeZone() {
        // 00:30 in Kuala Lumpur is still the previous day in UTC
        val t = millis(kl, 2026, 9, 28, 0, 30)
        assertEquals(20260928, Days.toYmd(Days.epochDay(t, kl)))
        assertEquals(20260927, Days.toYmd(Days.epochDay(t, TimeZone.getTimeZone("UTC"))))
    }

    @Test
    fun ymdRoundTripsAcrossLeapYearsAndEras() {
        assertEquals(0L, Days.fromYmd(19700101))
        assertEquals(19700101, Days.toYmd(0))
        assertEquals(19691231, Days.toYmd(-1))
        for (ymd in listOf(20000229, 20240229, 20251231, 20260101, 20260928, 21000301, 19000228)) {
            assertEquals(ymd, Days.toYmd(Days.fromYmd(ymd)))
        }
        var day = Days.fromYmd(19990101)
        var prev = Days.toYmd(day)
        repeat(12_000) {
            day++
            val ymd = Days.toYmd(day)
            assertTrue(ymd > prev)
            assertEquals(day, Days.fromYmd(ymd))
            prev = ymd
        }
    }

    @Test
    fun dayOfWeekIsIso() {
        assertEquals(4, Days.dayOfWeek(0)) // 1970-01-01 Thursday
        assertEquals(1, Days.dayOfWeek(Days.fromYmd(20260928))) // Monday
        assertEquals(7, Days.dayOfWeek(Days.fromYmd(20260927))) // Sunday
        assertEquals(Days.fromYmd(20260928), Days.weekStart(Days.fromYmd(20261004)))
    }

    @Test
    fun monthBoundaries() {
        val d = Days.fromYmd(20261215)
        assertEquals(Days.fromYmd(20261201), Days.monthStart(d))
        assertEquals(Days.fromYmd(20270101), Days.nextMonthStart(d))
        assertEquals(Days.fromYmd(20240301), Days.nextMonthStart(Days.fromYmd(20240229)))
    }

    @Test
    fun startOfDayBracketsEveryInstant() {
        val zones = listOf(kl, TimeZone.getTimeZone("UTC"), TimeZone.getTimeZone("America/New_York"), TimeZone.getTimeZone("Europe/London"))
        for (tz in zones) {
            var t = millis(tz, 2026, 1, 1, 0, 0)
            repeat(400) {
                t += 21 * 3_600_000L + 17 * 60_000L
                val day = Days.epochDay(t, tz)
                assertTrue(Days.startOfDay(day, tz) <= t, "${tz.id} $t")
                assertTrue(t < Days.startOfDay(day + 1, tz), "${tz.id} $t")
            }
        }
    }

    @Test
    fun aDayWithoutMidnightStartsAtTheClockChange() {
        // Havana, 8 March 2026: clocks jump from 00:00 to 01:00. The day starts at 01:00 local
        // (05:00 UTC); it used to start at 23:00 of the day before (2026-10 review).
        val havana = TimeZone.getTimeZone("America/Havana")
        val day = Days.fromYmd(20260308)
        assertEquals(millis(TimeZone.getTimeZone("UTC"), 2026, 3, 8, 5, 0), Days.startOfDay(day, havana))
        // Zones whose clocks change at midnight: every day starts at its first instant.
        val zones = listOf(
            "America/Havana", "America/Santiago", "America/Asuncion", "Asia/Beirut", "Asia/Amman", "Africa/Cairo",
        )
        for (id in zones) {
            val tz = TimeZone.getTimeZone(id)
            for (d in Days.fromYmd(20240101) until Days.fromYmd(20280101)) {
                val start = Days.startOfDay(d, tz)
                assertEquals(d, Days.epochDay(start, tz), "$id $d")
                assertEquals(d - 1, Days.epochDay(start - 1, tz), "$id $d")
            }
        }
    }
}
