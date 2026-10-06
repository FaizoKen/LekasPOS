package com.lekaspos.core.report

import com.lekaspos.core.time.Days

/**
 * The daily sales report in the shop's Google Drive (D-065): one file per month, each upload
 * replacing it with the month's finished days. Days are local epoch days.
 */
object MonthFiles {

    /**
     * In its first [REFRESH_DAYS] days a month's upload also rewrites the month before: sales of
     * other tills that arrive late (sync) then still reach the last days of that month.
     */
    const val REFRESH_DAYS = 3

    /** At most this many months are written in one go (a till offline for a year and more). */
    const val MAX_MONTHS = 13

    /**
     * The months (their first day, oldest first) to write when [yesterday] is the last finished day
     * and [last] the last one written before (null: never, or written again on request — then
     * yesterday's month and the one before). Empty when [yesterday] is written already. A [last]
     * after [yesterday] (the phone's clock was set back) writes yesterday's month again.
     */
    fun due(last: Long?, yesterday: Long): List<Long> {
        val end = Days.monthStart(yesterday)
        val start = when {
            last == null -> Days.monthStart(end - 1)
            last == yesterday -> return emptyList()
            last > yesterday -> end
            else -> Days.monthStart(last + 1)
        }
        val refresh = if (yesterday - end < REFRESH_DAYS) Days.monthStart(end - 1) else end
        val months = ArrayList<Long>()
        var m = minOf(start, refresh)
        while (m <= end) {
            months.add(m)
            m = Days.nextMonthStart(m)
        }
        return months.takeLast(MAX_MONTHS)
    }

    /** The days [month]'s file holds: its first day up to [yesterday], or the whole month once it is over. */
    fun period(month: Long, yesterday: Long): Period = Period(month, minOf(Days.nextMonthStart(month), yesterday + 1))

    /** "2026-10 daily-sales.csv" (sorts by date in Drive). */
    fun name(month: Long): String {
        val ymd = Days.toYmd(month)
        val mm = ymd / 100 % 100
        return "${ymd / 10_000}-${if (mm < 10) "0" else ""}$mm daily-sales.csv"
    }
}
