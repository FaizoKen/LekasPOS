package com.lekaspos.core.time

import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.Test

class DateTextTest {

    @Test
    fun formatsInTheStoreTimeZone() {
        val kl = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
        val localMidnight = Days.fromYmd(20260929) * Days.DAY_MS
        val at = localMidnight + (14 * 60 + 5) * 60_000L - 8 * 3_600_000L
        assertEquals("29/09/2026 14:05", DateText.dateTime(at, kl))
        assertEquals("14:05", DateText.time(at, kl))
        assertEquals("29/09/2026", DateText.date(Days.epochDay(at, kl)))
        assertEquals("01/01/1970 00:00", DateText.dateTime(0L, TimeZone.getTimeZone("UTC")))
        assertEquals("31/12/1969 23:59", DateText.dateTime(-60_000L, TimeZone.getTimeZone("UTC")))
    }
}
