package com.lekaspos.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlcTest {

    private var wall = 1_790_000_000_000L
    private val hlc = Hlc({ wall })

    @Test
    fun strictlyIncreasesWhenTheClockStandsStill() {
        val a = hlc.now()
        val b = hlc.now()
        val c = hlc.now()
        assertTrue(a < b && b < c)
        assertEquals(wall, Hlc.physicalOf(c))
        assertEquals(2, Hlc.counterOf(c))
    }

    @Test
    fun followsTheWallClockAndResetsTheCounter() {
        hlc.now()
        hlc.now()
        wall += 10
        val t = hlc.now()
        assertEquals(wall, Hlc.physicalOf(t))
        assertEquals(0, Hlc.counterOf(t))
    }

    @Test
    fun neverGoesBackwardsWhenTheClockDoes() {
        val before = hlc.now()
        wall -= 60_000 // user changed the time
        assertTrue(hlc.now() > before)
    }

    @Test
    fun orderingSurvivesARemoteClockAhead() {
        val remote = Hlc.pack(wall + 5_000, 3) // other device is 5 s ahead
        assertTrue(hlc.observe(remote))
        assertTrue(hlc.now() > remote, "an edit made after seeing the remote one must order after it")
    }

    @Test
    fun rejectsRemoteClocksFarInTheFuture() {
        val bogus = Hlc.pack(wall + Hlc.MAX_FUTURE_MS + 1, 0)
        assertFalse(hlc.observe(bogus))
        assertTrue(hlc.now() < bogus)
    }

    /** 2026-10 review: a till whose clock was set back stopped following the other tills. */
    @Test
    fun aClockSetBackStillFollowsTheOthersFromItsOwnLastTime() {
        val day = 24L * 60L * 60L * 1000L
        hlc.now()
        wall -= 3 * day // the date was set three days back
        val other = Hlc.pack(wall + 3 * day + 60_000L, 0) // another till, a minute after our last
        assertTrue(hlc.observe(other))
        assertTrue(hlc.now() > other)
        assertFalse(hlc.observe(Hlc.pack(wall + 3 * day + 2 * day, 0))) // still not a till days ahead of us
    }

    @Test
    fun resumesFromPersistedValue() {
        val persisted = Hlc.pack(wall + 1_000, 5)
        val restarted = Hlc({ wall }, persisted)
        assertTrue(restarted.now() > persisted)
    }

    /** 2026-10 review: a crafted value at the very top wrapped to the bottom; one far ahead was followed in 24 h steps. */
    @Test
    fun theLargestValueNeverWrapsAndTimesPast2100AreIgnored() {
        val top = Hlc({ wall }, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, top.now())
        assertEquals(Long.MAX_VALUE, com.lekaspos.core.sync.Lww.stampAbove(wall, Long.MAX_VALUE))
        val walked = Hlc({ wall }, Hlc.pack(Hlc.MAX_PHYSICAL_MS, 0))
        assertFalse(walked.observe(Hlc.pack(Hlc.MAX_PHYSICAL_MS + 1_000L, 0)))
    }

    @Test
    fun counterOverflowCarriesIntoTheMillis() {
        val start = Hlc({ wall }, Hlc.pack(wall, 0xFFFF))
        val next = start.now()
        assertEquals(wall + 1, Hlc.physicalOf(next))
        assertEquals(0, Hlc.counterOf(next))
    }
}
