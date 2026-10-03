package com.lekaspos.core.report

import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding
import com.lekaspos.core.time.Days

/** A report range of local epoch days: [from] inclusive, [to] exclusive. */
data class Period(val from: Long, val to: Long) {
    init {
        require(to > from) { "empty period" }
    }

    val days: Long get() = to - from

    /** The period of the same length just before this one (for comparisons). */
    fun previous(): Period = Period(from - days, from)

    /**
     * The same days of the month(s) or year before, for a period that starts on the 1st of a month
     * ("this month", 1–3 Oct → 1–3 Sep; "last month" → the whole month before; one that starts on
     * 1 January and is longer than a month → the same days a year before), else [previous]. "This
     * year" was compared with April to December, "last month" (September) with 2–31 August
     * (2026-10 review).
     */
    fun sameDaysBefore(): Period {
        val ymd = Days.toYmd(from)
        if (ymd % 100 != 1) return previous()
        val lastYmd = Days.toYmd(to - 1)
        val months = (lastYmd / 10_000 - ymd / 10_000) * 12 + (lastYmd / 100 % 100 - ymd / 100 % 100) + 1
        return monthsBefore(if (ymd / 100 % 100 == 1 && months > 1) (months + 11) / 12 * 12 else months)
    }

    /** The same days a year before ("this year" in January too: 1–15 Jan → 1–15 Jan last year). */
    fun sameDaysYearBefore(): Period = monthsBefore(12)

    /** This period [back] months earlier. */
    private fun monthsBefore(back: Int): Period {
        val ymd = Days.toYmd(from)
        val lastYmd = Days.toYmd(to - 1)
        // Ending with a whole month: the comparison ends with a whole month too (September → all of
        // August, not 1–30 August); else on the same day of the month, or that month's last day.
        val endYmd = Days.toYmd(to)
        val end = if (endYmd % 100 == 1) {
            Days.fromYmd(shiftMonths(endYmd, back))
        } else {
            Days.fromYmd(shiftMonths(lastYmd, back)) + 1
        }
        return Period(Days.fromYmd(shiftMonths(ymd, back)), end)
    }

    private companion object {
        /** [ymd] [months] earlier; a day the month does not have becomes its last day (31 Mar → 28 Feb). */
        fun shiftMonths(ymd: Int, months: Int): Int {
            val index = (ymd / 10_000) * 12 + (ymd / 100 % 100 - 1) - months
            val year = index / 12
            val month = index % 12 + 1
            val first = Days.fromYmd(year * 10_000 + month * 100 + 1)
            val length = (Days.nextMonthStart(first) - first).toInt()
            return year * 10_000 + month * 100 + minOf(ymd % 100, length)
        }
    }
}

/** Ready-made periods; weeks start on Monday. "This …" periods run up to and including today. */
enum class Preset {
    TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR;

    /** What a report of this preset is compared with: months and years the same days before, days and weeks the ones just before. */
    fun comparison(p: Period): Period = when (this) {
        THIS_MONTH, LAST_MONTH -> p.sameDaysBefore()
        THIS_YEAR, LAST_YEAR -> p.sameDaysYearBefore()
        else -> p.previous()
    }

    fun period(today: Long): Period = when (this) {
        TODAY -> Period(today, today + 1)
        YESTERDAY -> Period(today - 1, today)
        THIS_WEEK -> Period(Days.weekStart(today), today + 1)
        LAST_WEEK -> Days.weekStart(today).let { Period(it - 7, it) }
        THIS_MONTH -> Period(Days.monthStart(today), today + 1)
        LAST_MONTH -> Days.monthStart(today).let { Period(Days.monthStart(it - 1), it) }
        THIS_YEAR -> Period(yearStart(today), today + 1)
        LAST_YEAR -> yearStart(today).let { Period(yearStart(it - 1), it) }
    }

    private fun yearStart(day: Long): Long = Days.fromYmd(Days.toYmd(day) / 10_000 * 10_000 + 101)
}

object Months {
    /** Month key yyyymm of an epoch day (the key of `sum_month_product`). */
    fun key(day: Long): Int = Days.toYmd(day) / 100
}

enum class Granularity {
    DAY, WEEK, MONTH;

    companion object {
        /** Days for up to a month, weeks for up to about half a year, months beyond. */
        fun forPeriod(p: Period): Granularity = when {
            p.days <= 31L -> DAY
            p.days <= 190L -> WEEK
            else -> MONTH
        }
    }
}

/** One day of `sum_day` (amounts in minor units). */
data class DayTotals(
    val day: Long,
    val sales: Long = 0L,
    val refunds: Long = 0L,
    val total: Long = 0L,
    val netEx: Long = 0L,
    val tax: Long = 0L,
    val cost: Long = 0L,
    val discount: Long = 0L,
)

/** Totals of one bucket (day, week or month), clipped to the period: [start] inclusive, [end] exclusive. */
data class Bucket(
    val start: Long,
    val end: Long,
    val sales: Long,
    val refunds: Long,
    val total: Long,
    val netEx: Long,
    val tax: Long,
    val cost: Long,
    val discount: Long,
) {
    val grossProfit: Long get() = Checked.sub(netEx, cost)
}

object Buckets {
    /** Every bucket of [p] in order (empty ones included), filled from [days]. */
    fun of(days: List<DayTotals>, p: Period, g: Granularity): List<Bucket> {
        val starts = ArrayList<Long>()
        var s = p.from
        while (s < p.to) {
            starts.add(s)
            s = when (g) {
                Granularity.DAY -> s + 1
                Granularity.WEEK -> Days.weekStart(s) + 7
                Granularity.MONTH -> Days.nextMonthStart(s)
            }
        }
        val acc = Array(starts.size) { LongArray(7) }
        for (d in days) {
            if (d.day < p.from || d.day >= p.to) continue
            var i = starts.binarySearch(d.day)
            if (i < 0) i = -i - 2
            val a = acc[i]
            a[0] += d.sales
            a[1] += d.refunds
            a[2] = Checked.add(a[2], d.total)
            a[3] = Checked.add(a[3], d.netEx)
            a[4] = Checked.add(a[4], d.tax)
            a[5] = Checked.add(a[5], d.cost)
            a[6] = Checked.add(a[6], d.discount)
        }
        return starts.mapIndexed { i, start ->
            val a = acc[i]
            Bucket(start, if (i + 1 < starts.size) starts[i + 1] else p.to, a[0], a[1], a[2], a[3], a[4], a[5], a[6])
        }
    }
}

object ReportMath {
    /** Gross margin in basis points of net sales (ex. tax); null when there were no net sales. */
    fun marginBp(netEx: Long, cost: Long): Int? {
        if (netEx <= 0L) return null
        val bp = Rounding.mulDivHalfUp(Checked.sub(netEx, cost), 10_000L, netEx)
        // Held within Int (2026-10 review): 0.01 of net sales against RM3,000 of cost is
        // −29,999,900 % and wrapped around to a large positive margin.
        return bp.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** Change from [before] to [now] in basis points of [before]; null when [before] is not positive. */
    fun changeBp(now: Long, before: Long): Long? =
        if (before <= 0L) null else Rounding.mulDivHalfUp(Checked.sub(now, before), 10_000L, before)

    /** Average per sale, rounded half-up; 0 without sales. */
    fun average(total: Long, count: Long): Long = if (count <= 0L) 0L else Rounding.roundHalfUp(total, count)

    /** Value of [qtyMilli] at [unitCost] per unit, rounded half-up. */
    fun value(qtyMilli: Long, unitCost: Long): Long = Rounding.mulDivHalfUp(qtyMilli, unitCost, 1000L)
}
