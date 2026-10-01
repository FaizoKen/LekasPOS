package com.lekaspos.core.time

import com.lekaspos.core.money.Checked
import java.util.TimeZone

/**
 * Business-day arithmetic without java.time (not available on API 21, D-013).
 * An "epoch day" is the number of days since 1970-01-01 in the store's time zone.
 */
object Days {
    const val DAY_MS = 86_400_000L

    /** How far from midnight [startOfDay] looks for the offsets of a clock change. */
    private const val NEAR_MS = 6L * 3_600_000L

    fun epochDay(epochMs: Long, tz: TimeZone): Long =
        Checked.floorDiv(epochMs + tz.getOffset(epochMs), DAY_MS)

    /** Epoch millis of the first instant of [epochDay] in [tz] (local midnight, if it exists). */
    fun startOfDay(epochDay: Long, tz: TimeZone): Long {
        val localMidnight = epochDay * DAY_MS
        var utc = localMidnight - tz.getOffset(localMidnight)
        utc = localMidnight - tz.getOffset(utc) // second pass settles DST transitions
        // A clock change at midnight (2026-10 review): midnight may not exist (00:00 → 01:00, the
        // day starts at the jump; this gave 23:00 of the day before) or exist twice (01:00 → 00:00,
        // the first one counts). Midnight under each offset in force around it is a candidate;
        // the day starts at the earliest candidate that belongs to it.
        var first = Long.MAX_VALUE
        for (near in longArrayOf(utc - NEAR_MS, utc, utc + NEAR_MS)) {
            val candidate = localMidnight - tz.getOffset(near)
            if (candidate < first && Days.epochDay(candidate, tz) == epochDay) first = candidate
        }
        return if (first != Long.MAX_VALUE) first else utc // a day the zone skipped (Samoa 2011)
    }

    /** Local calendar date of [epochDay] as yyyymmdd (e.g. 20260928). */
    fun toYmd(epochDay: Long): Int {
        // Howard Hinnant's civil_from_days.
        val z = epochDay + 719_468L
        val era = Checked.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val year = if (m <= 2L) y + 1L else y
        return (year * 10_000L + m * 100L + d).toInt()
    }

    /** Epoch day of the calendar date [ymd] (yyyymmdd). */
    fun fromYmd(ymd: Int): Long {
        val year = ymd / 10_000
        val month = (ymd / 100) % 100
        val day = ymd % 100
        require(month in 1..12 && day in 1..31) { "bad date $ymd" }
        // Howard Hinnant's days_from_civil.
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Checked.floorDiv(y, 400L)
        val yoe = y - era * 400L
        val mp = ((month + 9) % 12).toLong()
        val doy = (153L * mp + 2L) / 5L + day - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146_097L + doe - 719_468L
    }

    /** ISO day of week: 1 = Monday … 7 = Sunday. 1970-01-01 was a Thursday. */
    fun dayOfWeek(epochDay: Long): Int = (Checked.floorMod(epochDay + 3L, 7L) + 1L).toInt()

    /** Epoch day of the Monday starting the week that contains [epochDay]. */
    fun weekStart(epochDay: Long): Long = epochDay - (dayOfWeek(epochDay) - 1)

    /** Epoch day of the first day of the month that contains [epochDay]. */
    fun monthStart(epochDay: Long): Long {
        val ymd = toYmd(epochDay)
        return fromYmd(ymd / 100 * 100 + 1)
    }

    /** Epoch day of the first day of the next month after the one containing [epochDay]. */
    fun nextMonthStart(epochDay: Long): Long {
        val ymd = toYmd(epochDay)
        var year = ymd / 10_000
        var month = (ymd / 100) % 100 + 1
        if (month == 13) {
            month = 1
            year++
        }
        return fromYmd(year * 10_000 + month * 100 + 1)
    }
}
