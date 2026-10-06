package com.lekaspos.core.report

import com.lekaspos.core.time.Days
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class MonthFilesTest {

    private fun d(ymd: Int) = Days.fromYmd(ymd)

    @Test
    fun eachDayWritesTheMonthOfTheDayBefore() {
        assertEquals(listOf(d(20261001)), MonthFiles.due(d(20261004), d(20261005)))
        // Written already today: nothing.
        assertTrue(MonthFiles.due(d(20261005), d(20261005)).isEmpty())
        // The month's last day is written on the 1st of the next month, into its own month.
        assertEquals(listOf(d(20261001)), MonthFiles.due(d(20261030), d(20261031)))
    }

    @Test
    fun theFirstDaysOfAMonthAlsoRewriteTheMonthBefore() {
        assertEquals(listOf(d(20261001), d(20261101)), MonthFiles.due(d(20261031), d(20261101)))
        assertEquals(listOf(d(20261001), d(20261101)), MonthFiles.due(d(20261102), d(20261103)))
        assertEquals(listOf(d(20261101)), MonthFiles.due(d(20261103), d(20261104)))
        // Across the new year.
        assertEquals(listOf(d(20261201), d(20270101)), MonthFiles.due(d(20261231), d(20270101)))
    }

    @Test
    fun theFirstUploadAndUploadNowWriteThisMonthAndTheOneBefore() {
        assertEquals(listOf(d(20260901), d(20261001)), MonthFiles.due(null, d(20261005)))
        assertEquals(listOf(d(20261201), d(20270101)), MonthFiles.due(null, d(20270115)))
    }

    @Test
    fun aTillOfflineForMonthsCatchesUpEveryMonthItMissed() {
        assertEquals(listOf(d(20260801), d(20260901), d(20261001)), MonthFiles.due(d(20260810), d(20261005)))
        // At most 13 months, the latest.
        val many = MonthFiles.due(d(20240115), d(20261015))
        assertEquals(MonthFiles.MAX_MONTHS, many.size)
        assertEquals(d(20261001), many.last())
        assertEquals(d(20251001), many.first())
    }

    @Test
    fun aClockSetBackWritesYesterdaysMonthAgain() {
        assertEquals(listOf(d(20261001)), MonthFiles.due(d(20270301), d(20261010)))
        assertEquals(listOf(d(20260901), d(20261001)), MonthFiles.due(d(20270301), d(20261002)))
    }

    @Test
    fun aMonthsFileHoldsItsFinishedDays() {
        assertEquals(Period(d(20261001), d(20261006)), MonthFiles.period(d(20261001), d(20261005)))
        assertEquals(Period(d(20261001), d(20261101)), MonthFiles.period(d(20261001), d(20261101)))
        assertEquals(Period(d(20261001), d(20261101)), MonthFiles.period(d(20261001), d(20261031)))
        assertEquals(Period(d(20261101), d(20261102)), MonthFiles.period(d(20261101), d(20261101)))
    }

    @Test
    fun filesAreNamedByMonth() {
        assertEquals("2026-10 daily-sales.csv", MonthFiles.name(d(20261001)))
        assertEquals("2027-01 daily-sales.csv", MonthFiles.name(d(20270101)))
    }
}
