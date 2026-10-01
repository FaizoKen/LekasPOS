package com.lekaspos.core.report

import com.lekaspos.core.time.Days
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PeriodsTest {

    private fun d(ymd: Int) = Days.fromYmd(ymd)

    @Test
    fun presetsFollowTheCalendar() {
        val today = d(20260930) // a Wednesday
        assertEquals(Period(today, today + 1), Preset.TODAY.period(today))
        assertEquals(Period(today - 1, today), Preset.YESTERDAY.period(today))
        assertEquals(Period(d(20260928), today + 1), Preset.THIS_WEEK.period(today))
        assertEquals(Period(d(20260921), d(20260928)), Preset.LAST_WEEK.period(today))
        assertEquals(Period(d(20260901), today + 1), Preset.THIS_MONTH.period(today))
        assertEquals(Period(d(20260801), d(20260901)), Preset.LAST_MONTH.period(today))
        assertEquals(Period(d(20260101), today + 1), Preset.THIS_YEAR.period(today))
        assertEquals(Period(d(20250101), d(20260101)), Preset.LAST_YEAR.period(today))
        // January: last month is December of the year before.
        assertEquals(Period(d(20251201), d(20260101)), Preset.LAST_MONTH.period(d(20260115)))
        assertEquals(Period(d(20260801), d(20260901)).previous(), Period(d(20260701), d(20260801)))
        assertEquals(202609, Months.key(today))
    }

    @Test
    fun rangesSplitIntoWholeMonthsAndLooseDays() {
        // 15 Jan – 10 Apr (exclusive): Jan 15–31 loose, Feb + Mar whole, Apr 1–9 loose.
        val s = MonthSplit.of(Period(d(20260115), d(20260410)))
        assertEquals(MonthSplit(d(20260115), d(20260201), 202602, 202604, d(20260401), d(20260410)), s)
        assertTrue(s.hasMonths)
        // A whole year is twelve months and no loose days.
        val y = MonthSplit.of(Period(d(20250101), d(20260101)))
        assertEquals(MonthSplit(d(20250101), d(20250101), 202501, 202601, d(20260101), d(20260101)), y)
        // Inside one month: days only.
        val m = MonthSplit.of(Period(d(20260903), d(20260920)))
        assertFalse(m.hasMonths)
        assertEquals(d(20260903), m.headFrom)
        assertEquals(d(20260920), m.headTo)
        assertEquals(m.tailFrom, m.tailTo)
        // Across a year end.
        val x = MonthSplit.of(Period(d(20251120), d(20260205)))
        assertEquals(202512, x.fromMonth)
        assertEquals(202602, x.toMonth)
        // Every day of a range is covered exactly once.
        for (p in listOf(Period(d(20260115), d(20260410)), Period(d(20251231), d(20260102)), Period(d(20260201), d(20260301)))) {
            val sp = MonthSplit.of(p)
            var covered = (sp.headTo - sp.headFrom) + (sp.tailTo - sp.tailFrom)
            var mDay = if (sp.hasMonths) sp.headTo else sp.tailFrom
            while (sp.hasMonths && mDay < sp.tailFrom) {
                covered += Days.nextMonthStart(mDay) - mDay
                mDay = Days.nextMonthStart(mDay)
            }
            assertEquals(p.days, covered, "coverage of $p")
        }
    }

    @Test
    fun bucketsIncludeEmptyOnesAndClipToThePeriod() {
        val p = Period(d(20260924), d(20261006)) // Thursday 24 Sep – Monday 5 Oct
        val days = listOf(
            DayTotals(d(20260924), sales = 3, total = 3_000, netEx = 2_800, cost = 1_900),
            DayTotals(d(20260928), sales = 1, total = 500, netEx = 470, cost = 300),
            DayTotals(d(20261005), sales = 2, total = 1_000, netEx = 940, cost = 600),
            DayTotals(d(20261010), sales = 9, total = 9_999), // outside: ignored
        )
        val byDay = Buckets.of(days, p, Granularity.DAY)
        assertEquals(12, byDay.size)
        assertEquals(3L, byDay[0].sales)
        assertEquals(0L, byDay[1].sales)
        val byWeek = Buckets.of(days, p, Granularity.WEEK)
        assertEquals(listOf(d(20260924), d(20260928), d(20261005)), byWeek.map { it.start })
        assertEquals(listOf(d(20260928), d(20261005), d(20261006)), byWeek.map { it.end })
        assertEquals(listOf(3L, 1L, 2L), byWeek.map { it.sales })
        val byMonth = Buckets.of(days, p, Granularity.MONTH)
        assertEquals(listOf(d(20260924), d(20261001)), byMonth.map { it.start })
        assertEquals(listOf(2_800L + 470L, 940L), byMonth.map { it.netEx })
        assertEquals(listOf(3_270L - 2_200L, 940L - 600L), byMonth.map { it.grossProfit })
        assertEquals(Granularity.DAY, Granularity.forPeriod(p))
        assertEquals(Granularity.WEEK, Granularity.forPeriod(Period(0, 90)))
        assertEquals(Granularity.MONTH, Granularity.forPeriod(Period(0, 365)))
    }

    @Test
    fun marginsAveragesAndStockValues() {
        assertEquals(3_000, ReportMath.marginBp(10_000L, 7_000L))
        assertEquals(3_333, ReportMath.marginBp(3_000L, 2_000L))
        assertEquals(-500, ReportMath.marginBp(10_000L, 10_500L))
        assertNull(ReportMath.marginBp(0L, 100L))
        // 0.01 of net sales against RM3,000 of cost: −2,999,990,000 bp is held at the Int limit
        // instead of wrapping around to +1,294,977,296 (2026-10 review).
        assertEquals(Int.MIN_VALUE, ReportMath.marginBp(1L, 300_000L))
        assertEquals(2_500L, ReportMath.changeBp(12_500L, 10_000L))
        assertEquals(-5_000L, ReportMath.changeBp(5_000L, 10_000L))
        assertNull(ReportMath.changeBp(5_000L, 0L))
        assertEquals(333L, ReportMath.average(1_000L, 3L))
        assertEquals(0L, ReportMath.average(1_000L, 0L))
        assertEquals(1_134L, ReportMath.value(2_520L, 450L)) // 2.52 kg at 4.50
    }
}
