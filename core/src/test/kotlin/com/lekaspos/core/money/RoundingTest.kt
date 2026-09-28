package com.lekaspos.core.money

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoundingTest {

    @Test
    fun roundHalfUpRoundsHalvesAwayFromZero() {
        assertEquals(3, Rounding.roundHalfUp(5, 2))
        assertEquals(-3, Rounding.roundHalfUp(-5, 2))
        assertEquals(4, Rounding.roundHalfUp(7, 2))
        assertEquals(-4, Rounding.roundHalfUp(-7, 2))
        assertEquals(3, Rounding.roundHalfUp(-5, -2))
        assertEquals(-3, Rounding.roundHalfUp(5, -2))
    }

    @Test
    fun roundHalfUpRoundsToNearest() {
        assertEquals(2, Rounding.roundHalfUp(4, 2))
        assertEquals(0, Rounding.roundHalfUp(1, 3))
        assertEquals(1, Rounding.roundHalfUp(2, 3))
        assertEquals(-1, Rounding.roundHalfUp(-2, 3))
        assertEquals(0, Rounding.roundHalfUp(0, 5))
        assertEquals(12, Rounding.roundHalfUp(12_375, 1000))
        assertEquals(13, Rounding.roundHalfUp(12_500, 1000))
        assertEquals(-13, Rounding.roundHalfUp(-12_500, 1000))
    }

    @Test
    fun roundHalfUpRejectsZeroDivisor() {
        assertFailsWith<IllegalArgumentException> { Rounding.roundHalfUp(1, 0) }
    }

    @Test
    fun mulDivMatchesWorkedExamples() {
        assertEquals(326, Rounding.mulDivHalfUp(1290, 253, 1000)) // 1290/kg × 0.253 kg = 326.37
        assertEquals(12, Rounding.mulDivHalfUp(99, 125, 1000)) // 12.375
        assertEquals(13, Rounding.mulDivHalfUp(100, 125, 1000)) // 12.5
        assertEquals(360, Rounding.mulDivHalfUp(120, 3000, 1000))
    }

    @Test
    fun mulDivThrowsOnOverflow() {
        assertFailsWith<ArithmeticException> { Rounding.mulDivHalfUp(Long.MAX_VALUE / 2 + 1, 2, 1) }
    }

    @Test
    fun cashRoundingToFiveSenMatchesMalaysianRule() {
        val expected = mapOf(
            1000L to 1000L, 1001L to 1000L, 1002L to 1000L, 1003L to 1005L, 1004L to 1005L,
            1005L to 1005L, 1006L to 1005L, 1007L to 1005L, 1008L to 1010L, 1009L to 1010L,
        )
        for ((input, out) in expected) assertEquals(out, Rounding.toStep(input, 5), "amount $input")
        assertEquals(-1005, Rounding.toStep(-1003, 5))
        assertEquals(-1000, Rounding.toStep(-1002, 5))
    }

    @Test
    fun stepZeroOrOneIsNoOp() {
        assertEquals(1003, Rounding.toStep(1003, 0))
        assertEquals(1003, Rounding.toStep(1003, 1))
    }

    @Test
    fun allocateSplitsWithLargestRemainderAndTiesToEarlierParts() {
        assertContentEquals(longArrayOf(34, 33, 33), Rounding.allocate(100, longArrayOf(1, 1, 1)))
        assertContentEquals(longArrayOf(0, 4, 3), Rounding.allocate(7, longArrayOf(0, 5, 5)))
        assertContentEquals(longArrayOf(25, 75), Rounding.allocate(100, longArrayOf(1, 3)))
    }

    @Test
    fun allocateMirrorsNegativeTotals() {
        assertContentEquals(longArrayOf(-34, -33, -33), Rounding.allocate(-100, longArrayOf(1, 1, 1)))
    }

    @Test
    fun allocateEdgeCases() {
        assertContentEquals(longArrayOf(0, 0), Rounding.allocate(0, longArrayOf(3, 4)))
        assertContentEquals(longArrayOf(9, 0), Rounding.allocate(9, longArrayOf(0, 0)))
        assertContentEquals(longArrayOf(), Rounding.allocate(0, longArrayOf()))
        assertFailsWith<IllegalArgumentException> { Rounding.allocate(5, longArrayOf()) }
        assertFailsWith<IllegalArgumentException> { Rounding.allocate(5, longArrayOf(1, -1)) }
    }

    @Test
    fun allocatePartsAlwaysSumToTotal() {
        val rnd = Random(1)
        repeat(2_000) {
            val n = 1 + rnd.nextInt(12)
            val weights = LongArray(n) { rnd.nextInt(100_000).toLong() }
            val total = rnd.nextInt(2_000_000).toLong() - 1_000_000L
            val parts = Rounding.allocate(total, weights)
            assertEquals(total, parts.sum())
            val sum = weights.sum()
            if (sum > 0) {
                for (i in 0 until n) {
                    val exact = total.toDouble() * weights[i] / sum
                    assert(Math.abs(parts[i] - exact) < 1.0) { "part $i off by >= 1: ${parts[i]} vs $exact" }
                }
            }
        }
    }

    @Test
    fun checkedArithmeticDetectsOverflow() {
        assertFailsWith<ArithmeticException> { Checked.add(Long.MAX_VALUE, 1) }
        assertFailsWith<ArithmeticException> { Checked.sub(Long.MIN_VALUE, 1) }
        assertFailsWith<ArithmeticException> { Checked.mul(Long.MAX_VALUE / 2 + 1, 2) }
        assertFailsWith<ArithmeticException> { Checked.mul(Long.MIN_VALUE, -1) }
        assertFailsWith<ArithmeticException> { Checked.neg(Long.MIN_VALUE) }
        assertEquals(6_000_000_000L, Checked.mul(3_000_000_000L, 2))
    }

    @Test
    fun floorDivAndModHandleNegatives() {
        assertEquals(-2, Checked.floorDiv(-7, 4))
        assertEquals(1, Checked.floorMod(-7, 4))
        assertEquals(1, Checked.floorDiv(7, 4))
        assertEquals(3, Checked.floorMod(7, 4))
        assertEquals(-1, Checked.floorDiv(-1, 86_400_000))
    }
}
