package com.lekaspos.core.pricing

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PricingEngineTest {

    private fun line(qty: Long, price: Long, discount: Discount = Discount.None, rate: Long? = null, bp: Int = 0) =
        PriceLine(qty = qty, unitPrice = price, discount = discount, taxRateId = rate, taxBp = bp)

    @Test
    fun lineGrossRoundsHalfUp() {
        assertEquals(326, PricingEngine.lineGross(1290, 253))
        assertEquals(360, PricingEngine.lineGross(120, 3000))
        assertEquals(13, PricingEngine.lineGross(100, 125))
    }

    @Test
    fun percentDiscountRoundsHalfUp() {
        assertEquals(200, PricingEngine.discountAmount(1999, Discount.Percent(1000)))
    }

    @Test
    fun amountDiscountIsCappedAtBase() {
        assertEquals(500, PricingEngine.discountAmount(500, Discount.Amount(900)))
        val cart = PricingEngine.price(listOf(line(1000, 500, Discount.Amount(900))), pricesIncludeTax = true)
        assertEquals(0, cart.total)
        assertEquals(500, cart.discount)
    }

    @Test
    fun exclusiveTaxIsAddedOnTop() {
        val cart = PricingEngine.price(listOf(line(1000, 1000, rate = 7, bp = 800)), pricesIncludeTax = false)
        assertEquals(80, cart.tax)
        assertEquals(1080, cart.total)
        assertEquals(80, cart.lines[0].tax)
    }

    @Test
    fun inclusiveTaxIsExtractedFromThePrice() {
        val sixPercent = PricingEngine.price(listOf(line(1000, 1060, rate = 6, bp = 600)), pricesIncludeTax = true)
        assertEquals(60, sixPercent.tax)
        assertEquals(1060, sixPercent.total)
        val eightPercent = PricingEngine.price(listOf(line(1000, 1000, rate = 8, bp = 800)), pricesIncludeTax = true)
        assertEquals(74, eightPercent.tax) // 74.07
        assertEquals(1000, eightPercent.total)
    }

    @Test
    fun billDiscountIsSplitByLargestRemainder() {
        val cart = PricingEngine.price(
            listOf(line(1000, 1000), line(1000, 1000), line(1000, 1000)),
            billDiscount = Discount.Amount(100),
            pricesIncludeTax = true,
        )
        assertEquals(listOf(34L, 33L, 33L), cart.lines.map { it.billDiscount })
        assertEquals(listOf(966L, 967L, 967L), cart.lines.map { it.net })
        assertEquals(2900, cart.total)
    }

    @Test
    fun taxIsComputedPerRateGroupThenAllocated() {
        val cart = PricingEngine.price(
            listOf(line(1000, 333, rate = 1, bp = 1000), line(1000, 333, rate = 1, bp = 1000), line(1000, 500, rate = 2, bp = 600)),
            pricesIncludeTax = false,
        )
        // group 1: base 666 × 10% = 66.6 → 67 (per-line rounding would give 33 + 33 = 66)
        assertEquals(67, cart.taxGroups.first { it.taxRateId == 1L }.tax)
        assertEquals(30, cart.taxGroups.first { it.taxRateId == 2L }.tax)
        assertEquals(97, cart.tax)
        assertEquals(67, cart.lines[0].tax + cart.lines[1].tax)
        assertEquals(1166 + 97, cart.total)
    }

    @Test
    fun fixedGrossFromScaleLabelIsUsedAsIs() {
        val cart = PricingEngine.price(listOf(PriceLine(qty = 253, unitPrice = 1290, fixedGross = 330)), pricesIncludeTax = true)
        assertEquals(330, cart.lines[0].gross)
        assertEquals(330, cart.total)
    }

    @Test
    fun emptyCartIsZero() {
        val cart = PricingEngine.price(emptyList(), pricesIncludeTax = false)
        assertEquals(0, cart.total)
        assertEquals(false, cart.pricesIncludeTax)
    }

    @Test
    fun invalidLinesAreRejected() {
        assertFailsWith<IllegalArgumentException> { PriceLine(qty = 0, unitPrice = 100) }
        assertFailsWith<IllegalArgumentException> { PriceLine(qty = 1000, unitPrice = -1) }
        assertFailsWith<IllegalArgumentException> { PriceLine(qty = 1000, unitPrice = 1, taxBp = 600) }
        assertFailsWith<IllegalArgumentException> { Discount.Percent(10_001) }
        assertFailsWith<IllegalArgumentException> { Discount.Amount(-1) }
    }

    @Test
    fun mixedPercentagesUnderOneRateAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PricingEngine.price(listOf(line(1000, 100, rate = 1, bp = 600), line(1000, 100, rate = 1, bp = 800)), pricesIncludeTax = true)
        }
    }

    @Test
    fun invariantsHoldForRandomCarts() {
        val rnd = Random(42)
        repeat(3_000) {
            val n = 1 + rnd.nextInt(15)
            val lines = List(n) {
                val rate = when (rnd.nextInt(4)) {
                    0 -> 1L to 600
                    1 -> 2L to 1000
                    else -> null to 0
                }
                val disc = when (rnd.nextInt(6)) {
                    0 -> Discount.Percent(rnd.nextInt(5001))
                    1 -> Discount.Amount(rnd.nextInt(3000).toLong())
                    else -> Discount.None
                }
                line(qty = 1L + rnd.nextInt(5_000), price = rnd.nextInt(50_000).toLong(), discount = disc, rate = rate.first, bp = rate.second)
            }
            val bill = when (rnd.nextInt(4)) {
                0 -> Discount.Percent(rnd.nextInt(3000))
                1 -> Discount.Amount(rnd.nextInt(5000).toLong())
                else -> Discount.None
            }
            val incl = rnd.nextBoolean()
            val c = PricingEngine.price(lines, bill, incl)
            assertEquals(c.subtotal, c.lines.sumOf { it.gross })
            assertEquals(c.lineDiscounts, c.lines.sumOf { it.lineDiscount })
            assertEquals(c.billDiscount, c.lines.sumOf { it.billDiscount })
            assertEquals(c.net, c.lines.sumOf { it.net })
            assertEquals(c.subtotal - c.discount, c.net)
            assertEquals(c.tax, c.lines.sumOf { it.tax })
            assertEquals(c.tax, c.taxGroups.sumOf { it.tax })
            assertEquals(if (incl) c.net else c.net + c.tax, c.total)
            assertTrue(c.lines.all { it.net >= 0 && it.tax >= 0 })
        }
    }
}
