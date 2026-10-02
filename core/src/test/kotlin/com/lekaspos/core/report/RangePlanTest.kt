package com.lekaspos.core.report

import com.lekaspos.core.report.RangePlan.Kind
import com.lekaspos.core.time.Days
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class RangePlanTest {

    private fun d(ymd: Int) = Days.fromYmd(ymd)

    /** A store with sales on every day from [first] to [last] (inclusive). */
    private fun salesFrom(first: Long, last: Long): (Long, Long) -> Boolean = { a, b ->
        assertTrue(a < b, "asked about an empty range")
        a <= last && b > first
    }

    private fun shape(pieces: List<RangePlan.Piece>) = pieces.joinToString(" ") { p ->
        (if (p.sign < 0) "-" else "+") + when (p.kind) {
            Kind.DAYS -> "d${p.to - p.from}"
            Kind.MONTH -> "m${p.key}"
            Kind.YEAR -> "y${p.key}"
        }
    }

    private val today = d(20261002)
    private val twoYears = salesFrom(d(20241001), today)

    @Test
    fun thisMonthAndThisYearAreReadWholeAsTheRestIsTheFuture() {
        assertEquals("+m202609", shape(RangePlan.of(Period(d(20260901), d(20260929)), salesFrom(d(20240101), d(20260928)))))
        assertEquals("+y2026", shape(RangePlan.of(Preset.THIS_YEAR.period(today), twoYears)))
        assertEquals("+y2025", shape(RangePlan.of(Preset.LAST_YEAR.period(today), twoYears)))
        assertEquals("+m202609", shape(RangePlan.of(Preset.LAST_MONTH.period(today), twoYears)))
        // Early in a month, a few days cost less than the whole month.
        assertEquals("+d2", shape(RangePlan.of(Preset.THIS_MONTH.period(today), twoYears)))
        assertEquals("+d1", shape(RangePlan.of(Preset.TODAY.period(today), twoYears)))
    }

    @Test
    fun aNearlyWholeMonthIsTheMonthLessTheDaysOutside() {
        // 3 Sep – 2 Oct: September less 1–2 Sep, then 1–2 Oct.
        assertEquals("+m202609 -d2 +d2", shape(RangePlan.of(Period(today - 29, today + 1), twoYears)))
        // A rolling year: October 2025 less two days, November, December, then 2026 (its rest is the future).
        assertEquals("+m202510 -d2 +m202511 +m202512 +y2026", shape(RangePlan.of(Period(today - 364, today + 1), twoYears)))
    }

    @Test
    fun aNearlyWholeYearIsTheYearLessTheMonthsOutside() {
        // 1 Feb 2025 – 31 Dec 2025: the year less January.
        assertEquals("+y2025 -m202501", shape(RangePlan.of(Period(d(20250201), d(20260101)), twoYears)))
        // Mid-month to mid-month across a year: half months cost less as days than as month less days.
        assertEquals(
            "+d16 +m202512 +m202601 +d14",
            shape(RangePlan.of(Period(d(20251115), d(20260215)), twoYears)),
        )
    }

    @Test
    fun aStoreWithoutOlderSalesReadsItsFirstMonthsWhole() {
        val opened = salesFrom(d(20260310), today) // opened on 10 March 2026
        // A year without sales costs nothing to read whole.
        assertEquals("+y2025 +y2026", shape(RangePlan.of(Period(d(20250101), today + 1), opened)))
        assertEquals("+m202603", shape(RangePlan.of(Period(d(20260305), d(20260401)), opened)))
    }

    /** The pieces add up to exactly the period's days, whatever the period and the days with sales. */
    @Test
    fun piecesAlwaysAddUpToThePeriod() {
        val rnd = Random(2026)
        val first = d(20230101)
        val span = 3 * 366
        repeat(400) {
            val value = LongArray(span) { if (rnd.nextInt(100) < 70) 0L else 1L + rnd.nextInt(1_000) }
            if (rnd.nextBoolean()) for (i in rnd.nextInt(span) until span) value[i] = 0L // nothing after a day
            val has: (Long, Long) -> Boolean = { a, b ->
                assertTrue(a < b)
                (maxOf(a, first)..minOf(b, first + span) - 1).any { value[(it - first).toInt()] != 0L }
            }
            fun sum(a: Long, b: Long): Long {
                var s = 0L
                for (day in maxOf(a, first) until minOf(b, first + span)) s += value[(day - first).toInt()]
                return s
            }
            val from = first + rnd.nextInt(span)
            val to = minOf(first + span.toLong(), from + 1 + rnd.nextInt(800))
            val p = Period(from, to)
            val pieces = RangePlan.of(p, has)
            val got = pieces.sumOf { it.sign * sum(it.from, it.to) }
            assertEquals(sum(p.from, p.to), got, "period $p: ${shape(pieces)}")
            for (x in pieces) {
                when (x.kind) {
                    Kind.MONTH -> {
                        assertEquals(Days.monthStart(x.from), x.from)
                        assertEquals(Days.nextMonthStart(x.from), x.to)
                        assertEquals(Months.key(x.from), x.key)
                    }
                    Kind.YEAR -> assertEquals(Days.toYmd(x.from) / 10_000, x.key)
                    Kind.DAYS -> assertTrue(x.from < x.to)
                }
            }
        }
    }
}
