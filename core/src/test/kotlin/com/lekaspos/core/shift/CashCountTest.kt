package com.lekaspos.core.shift

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class CashCountTest {

    @Test
    fun ringgitNotesAndCoinsLargestFirst() {
        assertEquals(listOf(10_000L, 5_000L, 2_000L, 1_000L, 500L, 100L, 50L, 20L, 10L, 5L), CashCount.denominations("MYR"))
        assertEquals(CashCount.denominations("MYR"), CashCount.denominations(" myr "))
        // Every ringgit amount the till takes in cash (5-sen steps) can be counted with them.
        assertTrue(CashCount.denominations("MYR").all { it % 5L == 0L })
        assertEquals(emptyList(), CashCount.denominations("XYZ"))
    }

    @Test
    fun totalAddsEveryDenomination() {
        // 2 × RM100 + 1 × RM50 + 7 × RM1 + 3 × 20 sen + 1 × 5 sen = RM257.65
        val pieces = mapOf(10_000L to 2L, 5_000L to 1L, 100L to 7L, 20L to 3L, 5L to 1L, 10L to 0L)
        assertEquals(25_765L, CashCount.total(pieces))
        assertEquals(0L, CashCount.total(emptyMap()))
        assertFailsWith<IllegalArgumentException> { CashCount.total(mapOf(100L to -1L)) }
        assertFailsWith<ArithmeticException> { CashCount.total(mapOf(10_000L to Long.MAX_VALUE / 2L)) }
    }

    @Test
    fun summaryListsWhatWasCountedLargestFirst() {
        val pieces = linkedMapOf(5L to 1L, 10_000L to 2L, 1_000L to 0L, 50L to 4L)
        val text = CashCount.summary(pieces) { v -> if (v >= 100L) "RM${v / 100L}" else "$v sen" }
        assertEquals("RM100 x 2, 50 sen x 4, 5 sen x 1", text)
        assertEquals("", CashCount.summary(emptyMap()) { it.toString() })
    }
}
