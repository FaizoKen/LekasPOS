package com.lekaspos.sync.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drive's RFC 3339 times (the short listing's `createdTime > …`, D-053); no java.time on API 21. */
class DriveTimeTest {

    @Test
    fun parsesDriveTimesWithAndWithoutMilliseconds() {
        assertEquals(1_790_771_696_789L, DriveProvider.parseTime("2026-09-30T12:34:56.789Z"))
        assertEquals(1_790_771_696_000L, DriveProvider.parseTime("2026-09-30T12:34:56Z"))
        assertEquals(1_790_771_696_500L, DriveProvider.parseTime("2026-09-30T12:34:56.5Z"))
        assertNull(DriveProvider.parseTime("not a time"))
    }

    @Test
    fun formatsQueryTimesInUtc() {
        assertEquals("2026-09-30T12:34:56", DriveProvider.rfc3339(1_790_771_696_789L))
        val ms = 1_790_771_696_000L
        assertEquals(ms, DriveProvider.parseTime(DriveProvider.rfc3339(ms) + "Z"))
    }
}
