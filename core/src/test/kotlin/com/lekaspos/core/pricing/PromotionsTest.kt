package com.lekaspos.core.pricing

import com.lekaspos.core.cart.Cart
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.PromoKind
import com.lekaspos.core.model.SellMode
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** references/money.md §11 — every worked example there is mirrored here. */
class PromotionsTest {

    private fun multi(id: Long, n: Int, price: Long, vararg products: Long) =
        Promotion(id, "promo $id", PromoKind.MULTI_PRICE, buyQty = n, groupPrice = price, productIds = products.toSet())

    private fun bogo(id: Long, buy: Int, free: Int, vararg products: Long) =
        Promotion(id, "promo $id", PromoKind.BUY_GET_FREE, buyQty = buy, freeQty = free, productIds = products.toSet())

    private fun line(product: Long, units: Int, price: Long, eligible: Boolean = true) =
        PromoLine(product, units * 1000L, price, eligible)

    private fun savings(lines: List<PromoLine>, vararg promos: Promotion) =
        Promotions.apply(lines, promos.toList()).map { it?.discount ?: 0L }

    @Test
    fun threeForTenRinggit() {
        val p = multi(1, 3, 1_000, 10)
        assertEquals(listOf(0L), savings(listOf(line(10, 2, 390)), p)) // not enough units
        assertEquals(listOf(170L), savings(listOf(line(10, 3, 390)), p)) // 1170 → 1000
        assertEquals(listOf(170L), savings(listOf(line(10, 4, 390)), p)) // the 4th at full price
        assertEquals(listOf(340L), savings(listOf(line(10, 6, 390)), p))
    }

    @Test
    fun mixAndMatchGroupsTheDearestUnitsAndSharesTheSavingByPrice() {
        val p = multi(1, 3, 1_000, 10, 11)
        // Units 390, 390, 350, 350: one group (390 + 390 + 350 = 1130), saving 130 shared 780 : 350.
        assertEquals(listOf(90L, 40L), savings(listOf(line(10, 2, 390), line(11, 2, 350)), p))
    }

    @Test
    fun buyOneGetOneFree() {
        val p = bogo(1, 1, 1, 10)
        assertEquals(listOf(0L), savings(listOf(line(10, 1, 500)), p))
        assertEquals(listOf(500L), savings(listOf(line(10, 2, 500)), p))
        assertEquals(listOf(500L), savings(listOf(line(10, 3, 500)), p))
        assertEquals(listOf(1_000L), savings(listOf(line(10, 4, 500)), p))
    }

    @Test
    fun buyTwoGetOneTheCheapestIsFree() {
        val p = bogo(1, 2, 1, 10, 11, 12)
        // The 400 is free; the saving is shared by price (400 × 600/1500, 500/1500, 400/1500).
        assertEquals(listOf(160L, 133L, 107L), savings(listOf(line(10, 1, 600), line(11, 1, 500), line(12, 1, 400)), p))
        // Six units: sets (600, 600, 500) and (500, 400, 400) → one 500 and one 400 free, 900 in all:
        // 500 over 1200 + 500 → 353 + 147; 400 over 500 + 800 → 154 + 246.
        assertEquals(
            listOf(353L, 301L, 246L),
            savings(listOf(line(10, 2, 600), line(11, 2, 500), line(12, 2, 400)), p),
        )
    }

    /**
     * 2026-10 review: with the whole saving on the free line, returning the paid items refunded
     * their full price and the customer kept the free one. Shared by price, each line's refund
     * carries its part of the deal.
     */
    @Test
    fun aFreeItemsSavingIsSharedSoReturningThePaidItemsIsFair() {
        val p = bogo(1, 2, 1, 10, 11, 12)
        val lines = listOf(line(10, 1, 450), line(11, 1, 420), line(12, 1, 400))
        val s = savings(lines, p)
        assertEquals(400L, s.sum())
        assertEquals(listOf(142L, 132L, 126L), s)
        // Returning the two paid items gives back 870 − 274 = 596, not 870.
        assertEquals(596L, (450L - s[0]) + (420L - s[1]))
    }

    /** A special price with dates ("now RM3.99"): a deal of one unit. */
    @Test
    fun aSpecialPriceIsADealOfOne() {
        val p = multi(1, 1, 399, 10)
        assertEquals(listOf(102L), savings(listOf(line(10, 2, 450)), p)) // 2 × (4.50 − 3.99)
        assertEquals(listOf(0L), savings(listOf(line(10, 1, 350)), p)) // the shelf price is lower already
    }

    @Test
    fun aDealDearerThanTheShelfPriceSavesNothing() {
        assertEquals(listOf(0L), savings(listOf(line(10, 3, 300)), multi(1, 3, 1_000, 10)))
        assertNull(Promotions.apply(listOf(line(10, 3, 300)), listOf(multi(1, 3, 1_000, 10)))[0])
    }

    @Test
    fun onlyEligibleWholeUnitsTakePart() {
        val p = multi(1, 2, 500, 10)
        assertEquals(listOf(0L), savings(listOf(line(10, 2, 300, eligible = false)), p))
        assertEquals(listOf(0L), savings(listOf(PromoLine(10, 2_500L, 300, true)), p)) // 2.5 units: not whole
        assertEquals(listOf(0L), savings(listOf(PromoLine(null, 2_000L, 300, true)), p)) // other item
        assertEquals(listOf(0L, 0L), savings(listOf(line(10, 1, 300), line(99, 1, 300)), p)) // 99 not in the deal
    }

    @Test
    fun aProductInTwoPromotionsTakesTheOlderOne() {
        val older = multi(1, 2, 500, 10)
        val newer = bogo(2, 1, 1, 10)
        val r = Promotions.apply(listOf(line(10, 2, 300)), listOf(newer, older))
        assertEquals(1L, r[0]?.promotionId)
        assertEquals(100L, r[0]?.discount)
    }

    @Test
    fun invalidPromotionsAreRefused() {
        assertFailsWith<IllegalArgumentException> { multi(1, 0, 100, 10) }
        assertFailsWith<IllegalArgumentException> { bogo(1, 0, 1, 10) }
        assertFailsWith<IllegalArgumentException> { bogo(1, 1, 0, 10) }
        assertFailsWith<IllegalArgumentException> { Promotion(1, "x", 9, 2, productIds = setOf(1L)) }
    }

    @Test
    fun theCartPricesPromotionsAsLineDiscountsBeforeTax() {
        val milo = CartItem(1, productId = 10, name = "Milo", qty = 3_000, unitPrice = 390, taxRateId = 7, taxBp = 600)
        val bread = CartItem(2, productId = 20, name = "Roti", qty = 1_000, unitPrice = 350)
        val cart = Cart(listOf(milo, bread))
        val priced = cart.price(pricesIncludeTax = true, promotions = listOf(multi(5, 3, 1_000, 10)))
        assertEquals(1_520L, priced.subtotal)
        assertEquals(170L, priced.lines[0].lineDiscount)
        assertEquals(1_000L, priced.lines[0].net)
        assertEquals(57L, priced.lines[0].tax) // 6% included in 1000: 56.6 → 57
        assertEquals(1_350L, priced.total)
        assertEquals(5L, priced.promotions[0]?.promotionId)
        assertNull(priced.promotions[1])
        // A line the cashier discounted or re-priced by hand keeps the cashier's price.
        val handled = Cart(listOf(milo.copy(discount = Discount.Percent(1000)), bread))
        assertEquals(117L, handled.price(true, listOf(multi(5, 3, 1_000, 10))).lines[0].lineDiscount)
        // Weighed goods never take part.
        val weighed = Cart(listOf(milo.copy(sellMode = SellMode.WEIGHT)))
        assertEquals(0L, weighed.price(true, listOf(multi(5, 3, 1_000, 10))).lines[0].lineDiscount)
    }

    @Test
    fun savingsNeverExceedALineAndNeverGoNegative() {
        val rnd = Random(11)
        repeat(2_000) {
            val promos = List(1 + rnd.nextInt(3)) { k ->
                val products = (1..(1 + rnd.nextInt(3))).map { (rnd.nextInt(5) + 1).toLong() }.toLongArray()
                if (rnd.nextBoolean()) {
                    multi(k.toLong(), 2 + rnd.nextInt(4), rnd.nextInt(2_000).toLong(), *products)
                } else {
                    bogo(k.toLong(), 1 + rnd.nextInt(3), 1 + rnd.nextInt(2), *products)
                }
            }
            val lines = List(1 + rnd.nextInt(6)) { line((rnd.nextInt(6) + 1).toLong(), 1 + rnd.nextInt(7), rnd.nextInt(1_500).toLong()) }
            val s = savings(lines, *promos.toTypedArray())
            for ((i, l) in lines.withIndex()) {
                val gross = l.unitPrice * (l.qty / 1000L)
                assertTrue(s[i] in 0L..gross, "line $i saving ${s[i]} of gross $gross ($lines, $promos)")
            }
        }
    }
}
