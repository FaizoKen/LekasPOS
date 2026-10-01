package com.lekaspos.core.sync

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LwwTest {

    @Test
    fun versionsOrderByHlcThenDevice() {
        assertTrue(Version(10, 1) < Version(11, 0))
        assertTrue(Version(10, 1) < Version(10, 2))
        assertTrue(Version.SEED < Version(1, 1))
    }

    @Test
    fun fieldVersionsRoundTripSorted() {
        val fv = FieldVersions.EMPTY.with("price", Version(200, 7)).with("name", Version(150, 3))
        assertEquals("name:150:3;price:200:7", fv.encode())
        assertEquals(fv, FieldVersions.decode(fv.encode()))
        assertNull(FieldVersions.EMPTY.encode())
        assertEquals(FieldVersions.EMPTY, FieldVersions.decode(null))
        assertEquals(FieldVersions.EMPTY, FieldVersions.decode(""))
    }

    @Test
    fun missingFieldsUseTheBaseVersion() {
        val base = Version(100, 1)
        val fv = FieldVersions.EMPTY.with("price", Version(300, 2))
        assertEquals(Version(300, 2), fv.of("price", base))
        assertEquals(base, fv.of("name", base))
    }

    @Test
    fun newerWinsPerFieldAndReapplyingIsANoOp() {
        val base = Version(100, 1)
        val current = FieldVersions.EMPTY.with("price", Version(300, 2))
        val incoming = Version(200, 5)
        // name (base 100) loses to 200; price (300) beats 200
        assertEquals(listOf("name"), Lww.winningFields(listOf("name", "price"), incoming, base, current))
        // same version again: nothing wins (idempotent)
        val after = current.with("name", incoming)
        assertEquals(emptyList(), Lww.winningFields(listOf("name", "price"), incoming, base, after))
    }

    @Test
    fun aLocalEditIsStampedAboveWhatTheFieldsHold() {
        assertEquals(500L, Lww.stampAbove(500L, 400L)) // the clock is ahead: unchanged
        assertEquals(901L, Lww.stampAbove(500L, 900L)) // a till with a clock far ahead wrote it
        assertEquals(501L, Lww.stampAbove(500L, 500L))
        // The stamped edit wins on every replica, whichever device wrote the held version.
        val held = Version(900L, 9)
        val mine = Version(Lww.stampAbove(500L, held.hlc), 1)
        assertEquals(listOf("price"), Lww.winningFields(listOf("price"), mine, held, FieldVersions.EMPTY))
    }

    /** Minimal LWW replica: field → (value, version). */
    private class Replica {
        val values = HashMap<String, Pair<String, Version>>()
        fun apply(field: String, value: String, v: Version) {
            val cur = values[field]
            if (cur == null || v > cur.second) values[field] = value to v
        }
    }

    @Test
    fun replicasConvergeWhateverTheDeliveryOrder() {
        val rnd = Random(11)
        repeat(200) {
            val updates = List(40) {
                Triple(listOf("name", "price", "sku", "deleted")[rnd.nextInt(4)], "v${rnd.nextInt(1000)}", Version(rnd.nextInt(50).toLong(), rnd.nextInt(4)))
            }.distinctBy { it.third to it.first } // one value per (field, version), as HLC+device guarantees
            val a = Replica()
            val b = Replica()
            for (u in updates) a.apply(u.first, u.second, u.third)
            val shuffled = updates.shuffled(rnd)
            for (u in shuffled + shuffled.take(10)) b.apply(u.first, u.second, u.third) // duplicates too
            assertEquals(a.values, b.values)
        }
    }
}
