package com.lekaspos.core.refund

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
    fun aPartAddsUpLikeTheLine() {
        // 2 units at 2.99, promotion 1.03 off: returning one gave gross 2.99, discount 0.52, net 2.48.
        val promo = RefundSource(1L, qty = 2000L, baseQty = 2000L, gross = 598L, discount = 103L, billDiscount = 0L, net = 495L, tax = 0L, cost = 300L)
        val p1 = Refunds.part(promo, 1000L)
        assertEquals(p1.net, p1.gross - p1.discount - p1.billDiscount)
        assertEquals(299L, p1.gross)
        assertEquals(248L, p1.net)
        val p2 = Refunds.part(promo.copy(refunded = p1), 1000L)
        assertEquals(p2.net, p2.gross - p2.discount - p2.billDiscount)
        assertEquals(598L, p1.gross + p2.gross)
        assertEquals(103L, p1.discount + p2.discount)
        assertEquals(495L, p1.net + p2.net)
        // With a bill discount as well, over three parts of odd sizes.
        val line = RefundSource(2L, qty = 7000L, baseQty = 7000L, gross = 1001L, discount = 97L, billDiscount = 53L, net = 851L, tax = 48L, cost = 700L)
        var done: RefundPart? = null
        for (q in listOf(3000L, 2000L, 2000L)) {
            val p = Refunds.part(line.copy(refunded = done), q)
            assertEquals(p.net, p.gross - p.discount - p.billDiscount)
            assertTrue(p.discount >= 0L && p.net >= 0L)
            done = Refunds.plus(done, p)
        }
        assertEquals(RefundPart(2L, 7000L, 7000L, 1001L, 97L, 53L, 851L, 48L, 700L), done)
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
