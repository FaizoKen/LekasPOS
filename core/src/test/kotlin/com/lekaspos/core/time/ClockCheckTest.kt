package com.lekaspos.core.time

import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.Test

class ClockCheckTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 60L * 60L * 1000L

    @Test
    fun aClockResetToTheYear2000IsWrong() {
        assertEquals("2026-09-01", DateText.isoDate(Days.epochDay(ClockCheck.NOT_BEFORE, utc)))
        assertEquals(ClockCheck.Verdict.WRONG, ClockCheck.verdict(946_684_800_000L, null)) // 2000-01-01
        assertEquals(ClockCheck.Verdict.WRONG, ClockCheck.verdict(0L, 1_790_000_000_000L))
    }

    @Test
    fun aClockBehindTheTillsLastSaleIsAsked() {
        val last = 1_790_000_000_000L
        assertEquals(ClockCheck.Verdict.OK, ClockCheck.verdict(last + 5_000L, last))
        assertEquals(ClockCheck.Verdict.OK, ClockCheck.verdict(last - hour / 2, last)) // a small correction
        assertEquals(ClockCheck.Verdict.SUSPECT, ClockCheck.verdict(last - 2 * hour, last))
        assertEquals(ClockCheck.Verdict.OK, ClockCheck.verdict(last, null))
    }
}
