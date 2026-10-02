package com.lekaspos.core.pricing

import kotlin.test.assertEquals
import org.junit.Test

class QuickCashTest {

    private fun rm(vararg v: Long) = v.map { it * 100L }

    @Test
    fun theNotesCustomersHandOver() {
        assertEquals(rm(25, 30, 50), QuickCash.amounts(2_345L, 100L)) // not 24, 25, 30 any more
        assertEquals(rm(65, 70, 100), QuickCash.amounts(6_170L, 100L))
        assertEquals(rm(10, 20, 50), QuickCash.amounts(890L, 100L))
        assertEquals(rm(5, 10, 20), QuickCash.amounts(430L, 100L))
        assertEquals(rm(10, 20, 50), QuickCash.amounts(500L, 100L)) // RM5 exactly: "Exact" covers 5
        assertEquals(rm(100), QuickCash.amounts(9_900L, 100L))
        assertEquals(rm(105, 110, 150), QuickCash.amounts(10_005L, 100L))
        assertEquals(emptyList(), QuickCash.amounts(10_000L, 100L)) // RM100 exactly: "Exact" is the note
    }

    @Test
    fun nothingToOfferForNothingDue() {
        assertEquals(emptyList(), QuickCash.amounts(0L, 100L))
        assertEquals(rm(5), QuickCash.amounts(430L, 100L, max = 1))
    }
}
