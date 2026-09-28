package com.lekaspos.core.refund

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class RefundsTest {

    private val line = RefundSource(
        lineId = 7L, qty = 3000L, baseQty = 3000L, gross = 1000L, discount = 0L, billDiscount = 0L,
        net = 1000L, tax = 57L, cost = 600L,
    )

    @Test
    fun partialRefundsAddUpExactlyToTheLine() {
        val p1 = Refunds.part(line, 1000L)
        assertEquals(333L, p1.net)
        assertEquals(19L, p1.tax)
        val p2 = Refunds.part(line.copy(refunded = p1), 1000L)
        assertEquals(333L, p2.net)
        val done = Refunds.plus(p1, p2)
        val p3 = Refunds.part(line.copy(refunded = done), 1000L)
        assertEquals(334L, p3.net) // the last return takes what is left
        assertEquals(1000L, p1.net + p2.net + p3.net)
        assertEquals(57L, p1.tax + p2.tax + p3.tax)
        assertEquals(600L, p1.cost + p2.cost + p3.cost)
        assertEquals(3000L, p1.baseQty + p2.baseQty + p3.baseQty)
    }

    @Test
    fun returningEverythingRefundsWhatWasPaid() {
        val p = Refunds.part(line, 3000L)
        assertEquals(RefundPart(7L, 3000L, 3000L, 1000L, 0L, 0L, 1000L, 57L, 600L), p)
    }

    @Test
    fun allocatedDiscountsAreReturnedProRata() {
        val discounted = RefundSource(
            lineId = 1L, qty = 2000L, baseQty = 2000L, gross = 2000L, discount = 200L, billDiscount = 100L,
            net = 1700L, tax = 96L, cost = 900L,
        )
        val p = Refunds.part(discounted, 1000L)
        assertEquals(1000L, p.gross)
        assertEquals(100L, p.discount)
        assertEquals(50L, p.billDiscount)
        assertEquals(850L, p.net)
        assertEquals(48L, p.tax)
    }

    @Test
    fun weighedLinesRefundByWeight() {
        val weighed = RefundSource(1L, qty = 1253L, baseQty = 1253L, gross = 1616L, discount = 0L, billDiscount = 0L, net = 1616L, tax = 0L, cost = 1002L)
        val p = Refunds.part(weighed, 500L)
        assertEquals(645L, p.net) // 1616 × 500 / 1253 = 644.9
    }

    @Test
    fun cannotReturnMoreThanIsLeft() {
        assertFailsWith<IllegalArgumentException> { Refunds.part(line, 3001L) }
        val p = Refunds.part(line, 2000L)
        assertFailsWith<IllegalArgumentException> { Refunds.part(line.copy(refunded = p), 2000L) }
        assertFailsWith<IllegalArgumentException> { Refunds.part(line, 0L) }
    }

    @Test
    fun documentTotals() {
        val parts = listOf(Refunds.part(line, 1000L), Refunds.part(line.copy(lineId = 8L), 3000L))
        val incl = Refunds.totals(parts, pricesIncludeTax = true)
        assertEquals(1333L, incl.net)
        assertEquals(1333L, incl.due)
        assertEquals(76L, incl.tax)
        val excl = Refunds.totals(parts, pricesIncludeTax = false)
        assertEquals(1409L, excl.due)
    }
}
