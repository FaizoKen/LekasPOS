package com.lekaspos.core.sync

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SyncNamesTest {

    private val store = "3f2b9c1e-7a44-4d1b-9a0e-5c7d0b8e2f11"

    private fun seg(n: Long) = SyncNames.TillFile(store, 7, n, n, false)

    private fun pack(from: Long, to: Long) = SyncNames.TillFile(store, 7, from, to, true)

    @Test
    fun packNamesRoundTripAndOlderTillsPassThemBy() {
        val name = SyncNames.pack(store, 1_234_567, 1, 2_000)
        assertEquals("seg-$store-1234567-00000001-00002000.pack.gz", name)
        assertEquals(SyncNames.TillFile(store, 1_234_567, 1, 2_000, true), SyncNames.parseFile(name))
        assertTrue(name.startsWith(SyncNames.segmentPrefix(store))) // one listing finds segments and packs
        assertNull(SyncNames.parseSegment(name)) // a till before D-085 ignores it
        assertEquals(SyncNames.TillFile(store, 7, 42, 42, false), SyncNames.parseFile(SyncNames.segment(store, 7, 42)))
        assertNull(SyncNames.parseFile("seg-$store-7-00000009-00000008.pack.gz")) // backwards
        assertNull(SyncNames.parseFile("seg-$store-7-00000000-00000008.pack.gz"))
        assertNull(SyncNames.parseFile("seg-7-00000001-00000002.pack.gz")) // no store
        assertNull(SyncNames.parseFile(SyncNames.device(store, 7)))
    }

    @Test
    fun aPlanReadsSegmentsAndPacksInOrderAndWaitsAtAGap() {
        // Segments only: as before.
        assertEquals(listOf(seg(4), seg(5), seg(6)), Cursors.plan(3, listOf(seg(1), seg(4), seg(5), seg(6), seg(8))))
        // The segments 1..2000 are gone: their pack is read, then the segments after it.
        val p = pack(1, 2_000)
        assertEquals(listOf(p, seg(2_001)), Cursors.plan(0, listOf(p, seg(2_001))))
        // Read half of it already: the pack is still the way on (its first half is skipped while reading).
        assertEquals(listOf(p, seg(2_001)), Cursors.plan(1_000, listOf(p, seg(2_001))))
        // Read past it: the pack is not read again.
        assertEquals(listOf(seg(2_001)), Cursors.plan(2_000, listOf(p, seg(2_001))))
        // The segments are still there too: many to read → the pack; a few → the segments.
        val both = (1L..2_001L).map { seg(it) } + p
        assertEquals(listOf(p, seg(2_001)), Cursors.plan(0, both))
        assertEquals(listOf(seg(1_995), seg(1_996), seg(1_997), seg(1_998), seg(1_999), seg(2_000), seg(2_001)), Cursors.plan(1_994, both))
        // Packs follow on from one another; a gap after them waits.
        assertEquals(listOf(pack(1, 10), pack(11, 20)), Cursors.plan(0, listOf(pack(11, 20), pack(1, 10), seg(22))))
        assertEquals(20L, Cursors.reach(0, listOf(pack(11, 20), pack(1, 10), seg(22))))
        assertEquals(emptyList(), Cursors.plan(0, listOf(pack(2, 10))))
        assertEquals(5L, Cursors.reach(5, listOf(pack(2, 5))))
    }

    @Test
    fun aPackIsMadeOnlyWhenFullAndNeverOfTheNewestSegments() {
        fun rows(from: Long, to: Long, size: Long = 1_000L) = (from..to).map { Packs.Sealed(it, size) }
        // 2,000 + 100 uploaded: the first 2,000 make a pack.
        assertEquals(1L..2_000L, Packs.next(0, rows(1, 2_000), uploadedTo = 2_100))
        // The newest 100 stay as they are: not full yet.
        assertNull(Packs.next(0, rows(1, 2_000), uploadedTo = 2_099))
        // Not enough yet.
        assertNull(Packs.next(0, rows(1, 500), uploadedTo = 5_000))
        // After the last pack.
        assertEquals(2_001L..4_000L, Packs.next(2_000, rows(2_001, 4_000), uploadedTo = 9_000))
        // Large segments (pictures): full at 4 MB.
        assertEquals(1L..4L, Packs.next(0, rows(1, 10, size = 1_048_576L), uploadedTo = 1_000))
        // A missing number is never packed over.
        assertNull(Packs.next(0, rows(1, 10) + rows(12, 3_000), uploadedTo = 9_000))
        // Tests make it small.
        assertEquals(1L..2L, Packs.next(0, rows(1, 5), uploadedTo = 5, keepNewest = 1, maxSegments = 2))
    }

    @Test
    fun segmentsLeaveTheFolderOnlyOnceEveryTillThatCannotReadPacksHasReadThem() {
        val now = 100L * 24L * 60L * 60L * 1000L
        val today = now - 60_000L
        assertEquals(2_000L, Packs.removableTo(2_000, emptyList(), now))
        assertEquals(2_000L, Packs.removableTo(2_000, listOf(Packs.Reader(true, today, 0)), now)) // reads packs
        assertEquals(1_500L, Packs.removableTo(2_000, listOf(Packs.Reader(false, today, 1_500)), now)) // an older till
        assertEquals(2_000L, Packs.removableTo(2_000, listOf(Packs.Reader(false, today, 2_500)), now))
        // An older till not seen for a month is not waited for.
        assertEquals(2_000L, Packs.removableTo(2_000, listOf(Packs.Reader(false, now - Packs.READER_GONE_MS - 1L, 0)), now))
        assertEquals(0L, Packs.removableTo(2_000, listOf(Packs.Reader(false, today, 0), Packs.Reader(true, today, 0)), now))
    }

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
}
