package com.lekaspos.core.sync

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SyncNamesTest {

    private val store = "3f2b9c1e-7a44-4d1b-9a0e-5c7d0b8e2f11"

    @Test
    fun segmentNamesRoundTripAndSortByNumber() {
        val name = SyncNames.segment(store, 1_234_567, 42)
        assertEquals("seg-$store-1234567-00000042.ndjson.gz", name)
        assertEquals(SyncNames.Segment(store, 1_234_567, 42), SyncNames.parseSegment(name))
        assertTrue(name.startsWith(SyncNames.segmentPrefix(store)))
        // Zero-padded: string order is number order.
        assertTrue(SyncNames.segment(store, 7, 9) < SyncNames.segment(store, 7, 10))
        assertNull(SyncNames.parseSegment("seg-$store-7-x.ndjson.gz"))
        assertNull(SyncNames.parseSegment("seg-$store-7-00000000.ndjson.gz"))
        assertNull(SyncNames.parseSegment(SyncNames.device(store, 7)))
        assertNull(SyncNames.parseSegment("notes.txt"))
        assertEquals(store, SyncNames.parseStore(SyncNames.store(store)))
        assertNull(SyncNames.parseStore("store-.json"))
        assertEquals("dev-$store-7.json", SyncNames.device(store, 7))
    }

    @Test
    fun cursorsStopAtTheFirstGap() {
        assertEquals(listOf(4L, 5L, 6L), Cursors.next(3L, listOf(1L, 2L, 3L, 4L, 5L, 6L, 8L, 9L)))
        assertEquals(emptyList(), Cursors.next(3L, listOf(5L, 6L))) // 4 is missing: wait for it
        assertEquals(listOf(1L, 2L), Cursors.next(0L, listOf(2L, 1L)))
        assertEquals(emptyList(), Cursors.next(9L, listOf(1L, 2L)))
    }

    @Test
    fun ownSegmentsAreDeletedOnlyWhenEveryTillHasThem() {
        assertTrue(Cursors.deletable(5L, listOf(5L, 9L)))
        assertFalse(Cursors.deletable(5L, listOf(4L, 9L)))
        assertFalse(Cursors.deletable(1L, emptyList()))
    }
}
