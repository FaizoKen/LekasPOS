package com.lekaspos.core.id

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdsTest {

    @Test
    fun composesAndDecomposes() {
        val id = Ids.make(123_456, 789L)
        assertEquals(123_456, Ids.deviceOf(id))
        assertEquals(789L, Ids.seqOf(id))
        val max = Ids.make(Ids.MAX_DEVICE_NO, Ids.MAX_SEQ)
        assertTrue(max > 0, "IDs must stay positive")
        assertEquals(Ids.MAX_DEVICE_NO, Ids.deviceOf(max))
    }

    @Test
    fun seedIdsAreSmallAndDeviceZero() {
        assertEquals(1L, Ids.make(0, 1))
        assertEquals(0, Ids.deviceOf(4L))
    }

    @Test
    fun devicesNeverOverlap() {
        val a = Ids.rangeOf(7)
        val b = Ids.rangeOf(8)
        assertTrue(a.last < b.first)
    }

    @Test
    fun rejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { Ids.make(-1, 1) }
        assertFailsWith<IllegalArgumentException> { Ids.make(Ids.MAX_DEVICE_NO + 1, 1) }
        assertFailsWith<IllegalArgumentException> { Ids.make(1, 0) }
    }

    @Test
    fun randomDeviceNumbersAreInRangeAndNeverZero() {
        val r = Random(3)
        repeat(10_000) {
            val d = Ids.randomDeviceNo(r)
            assertTrue(d in 1..Ids.MAX_DEVICE_NO)
        }
    }

    private class FakeStore(var reserved: Long = 0L) : IdAllocator.ReservationStore {
        var saves = 0
        override fun loadReserved() = reserved
        override fun saveReserved(value: Long) {
            reserved = value
            saves++
        }
    }

    @Test
    fun allocatorReservesBeforeHandingOutIds() {
        val store = FakeStore()
        val alloc = IdAllocator(42, store, blockSize = 10)
        assertFailsWith<IllegalStateException> { alloc.nextId() }
        alloc.ensure(3)
        assertTrue(store.reserved >= 3)
        val ids = List(3) { alloc.nextId() }
        assertEquals(listOf(1L, 2L, 3L), ids.map { Ids.seqOf(it) })
        assertTrue(ids.all { Ids.deviceOf(it) == 42 })
    }

    @Test
    fun allocatorOnlyWritesWhenTheBlockRunsLow() {
        val store = FakeStore()
        val alloc = IdAllocator(1, store, blockSize = 100)
        alloc.ensure(1)
        repeat(50) { alloc.ensure(1); alloc.nextId() }
        assertEquals(1, store.saves)
    }

    @Test
    fun restartAfterCrashNeverReusesAnId() {
        val store = FakeStore()
        val first = IdAllocator(9, store, blockSize = 10)
        first.ensure(5)
        val used = List(5) { first.nextId() }
        // "crash": a new allocator starts from the persisted reservation, skipping unused IDs
        val second = IdAllocator(9, store, blockSize = 10)
        second.ensure(5)
        val after = List(5) { second.nextId() }
        assertTrue(after.min() > used.max())
    }
}
