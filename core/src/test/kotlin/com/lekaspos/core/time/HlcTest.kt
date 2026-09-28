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

    @Test
    fun resumesFromPersistedValue() {
        val persisted = Hlc.pack(wall + 1_000, 5)
        val restarted = Hlc({ wall }, persisted)
        assertTrue(restarted.now() > persisted)
    }

    @Test
    fun counterOverflowCarriesIntoTheMillis() {
        val start = Hlc({ wall }, Hlc.pack(wall, 0xFFFF))
        val next = start.now()
        assertEquals(wall + 1, Hlc.physicalOf(next))
        assertEquals(0, Hlc.counterOf(next))
    }
}
