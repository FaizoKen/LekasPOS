package com.lekaspos.core.report

import com.lekaspos.core.time.Days

/**
 * How a report reads a period from the summary tables (D-058): whole years from the per-year
 * table, whole months from the per-month one, loose days from the per-day one. A month or year
 * only partly in the period is still read whole when the days of it outside the period had no
 * sales — "this month" and "this year" end today, and the rest is the future. A month or year
 * that is nearly all in the period is read whole and the few days (months) outside it are taken
 * off again: a rolling 30 days is one month minus two days plus a few days, not 30 days.
 *
 * The pieces always add up to exactly the period: each summary row is the sum of the rows below
 * it (a year of its months, a month of its days), so `Σ sign × piece` = the period's days.
 * Which pieces are chosen only changes how many rows are read (the cost: a month of a big store
 * is ~30,000 product rows, a day ~2,300, a year ~45,000).
 */
object RangePlan {

    enum class Kind { DAYS, MONTH, YEAR }

    /**
     * One part of a period: the days [from, to) — read per day for [Kind.DAYS], or as the whole
     * month or year containing them ([key] = yyyymm or yyyy). [sign] −1: taken off.
     */
    data class Piece(val kind: Kind, val from: Long, val to: Long, val key: Int, val sign: Int) {
        val cost: Int get() = when (kind) {
            Kind.DAYS -> (to - from).toInt()
            Kind.MONTH -> MONTH_COST
            Kind.YEAR -> YEAR_COST
        }

        fun negated(): Piece = copy(sign = -sign)
    }

    /** Rows of a month (a year) in days' rows, roughly, for a large catalogue (the perf suite's FULL store). */
    const val MONTH_COST = 13
    const val YEAR_COST = 20

    /**
     * The pieces of [p]. [hasSales] (from, to): whether the per-day table has rows for any day in
     * [from, to) — asked only for non-empty ranges, a few times per period (an index probe each).
     */
    fun of(p: Period, hasSales: (Long, Long) -> Boolean): List<Piece> {
        val out = ArrayList<Piece>()
        var y = yearStart(p.from)
        while (y < p.to) {
            val ye = nextYearStart(y)
            val a = maxOf(p.from, y)
            val b = minOf(p.to, ye)
            out.addAll(year(y, ye, a, b, hasSales))
            y = ye
        }
        return out
    }

    /** The days [a, b) of the year [y, ye). */
    private fun year(y: Long, ye: Long, a: Long, b: Long, has: (Long, Long) -> Boolean): List<Piece> {
        if (b - a <= YEAR_COST) return months(a, b, has)
        val before = a > y && has(y, a)
        val after = b < ye && has(b, ye)
        if (!before && !after) return listOf(Piece(Kind.YEAR, y, ye, yearKey(y), 1))
        val inside = months(a, b, has)
        val insideCost = inside.sumOf { it.cost }
        if (insideCost <= YEAR_COST) return inside
        val rest = (if (a > y) months(y, a, has) else emptyList()) + (if (b < ye) months(b, ye, has) else emptyList())
        if (YEAR_COST + rest.sumOf { it.cost } < insideCost) {
            return listOf(Piece(Kind.YEAR, y, ye, yearKey(y), 1)) + rest.map { it.negated() }
        }
        return inside
    }

    /** The days [from, to), month by month. */
    private fun months(from: Long, to: Long, has: (Long, Long) -> Boolean): List<Piece> {
        val out = ArrayList<Piece>()
        var m = Days.monthStart(from)
        while (m < to) {
            val me = Days.nextMonthStart(m)
            val a = maxOf(from, m)
            val b = minOf(to, me)
            if (b - a <= MONTH_COST) {
                out.add(days(a, b))
            } else {
                val before = a > m && has(m, a)
                val after = b < me && has(b, me)
                val excluded = (if (before) a - m else 0L) + (if (after) me - b else 0L)
                if (MONTH_COST + excluded < b - a) {
                    out.add(Piece(Kind.MONTH, m, me, Months.key(m), 1))
                    if (before) out.add(days(m, a).negated())
                    if (after) out.add(days(b, me).negated())
                } else {
                    out.add(days(a, b))
                }
            }
            m = me
        }
        return out
    }

    private fun days(a: Long, b: Long) = Piece(Kind.DAYS, a, b, 0, 1)

    private fun yearKey(day: Long): Int = Days.toYmd(day) / 10_000

    private fun yearStart(day: Long): Long = Days.fromYmd(yearKey(day) * 10_000 + 101)

    private fun nextYearStart(day: Long): Long = Days.fromYmd((yearKey(day) + 1) * 10_000 + 101)
}
