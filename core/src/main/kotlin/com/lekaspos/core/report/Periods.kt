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
}

/** Ready-made periods; weeks start on Monday. "This …" periods run up to and including today. */
enum class Preset {
    TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR;

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

/**
 * A day range split for reading summaries (D-043): whole months come from the per-month
 * table, the loose days before and after them from the per-day table. Empty parts have
 * `from == to`. Month keys: [fromMonth] inclusive, [toMonth] exclusive.
 */
data class MonthSplit(
    val headFrom: Long,
    val headTo: Long,
    val fromMonth: Int,
    val toMonth: Int,
    val tailFrom: Long,
    val tailTo: Long,
) {
    val hasMonths: Boolean get() = toMonth > fromMonth

    companion object {
        fun of(p: Period): MonthSplit {
            val firstFull = if (Days.monthStart(p.from) == p.from) p.from else Days.nextMonthStart(p.from)
            val fullEnd = Days.monthStart(p.to)
            if (firstFull >= fullEnd) return MonthSplit(p.from, p.to, 0, 0, p.to, p.to)
            return MonthSplit(p.from, firstFull, Months.key(firstFull), Months.key(fullEnd), fullEnd, p.to)
        }
    }
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
