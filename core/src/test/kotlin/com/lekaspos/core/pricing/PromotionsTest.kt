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

    private fun ladder(id: Long, prices: List<Pair<Int, Long>>, vararg products: Long) = Promotion(
        id, "promo $id", PromoKind.MULTI_PRICE, buyQty = prices[0].first, groupPrice = prices[0].second,
        productIds = products.toSet(), morePrices = prices.drop(1).map { PriceTier(it.first, it.second) },
    )

    /** D-073, a shop's deal: "1 for RM2, 2 for RM3, 3 for RM4" charged RM5 for 3 (only one price applied). */
    @Test
    fun aPriceLadderChargesTheCheapestMix() {
        val p = ladder(1, listOf(1 to 200L, 2 to 300L, 3 to 400L), 10)
        // Totals for 1..7 units: 2, 3, 4, 6 (3+1), 7 (3+2), 8 (3+3), 10 (3+3+1).
        val totals = listOf(200L, 300L, 400L, 600L, 700L, 800L, 1_000L)
        for ((i, total) in totals.withIndex()) {
            val units = i + 1
            assertEquals(listOf(units * 200L - total), savings(listOf(line(10, units, 200)), p), "$units units")
        }
        // Without "1 for RM2" (the shelf price already) the same.
        val q = ladder(1, listOf(2 to 300L, 3 to 400L), 10)
        for ((i, total) in totals.withIndex()) {
            assertEquals(listOf((i + 1) * 200L - total), savings(listOf(line(10, i + 1, 200)), q))
        }
    }

    /** The shop had made two promotions: they combine into one ladder, each line named by the deal that saved most. */
    @Test
    fun separateMultiBuysOnTheSameProductsCombine() {
        val two = multi(1, 2, 300, 10)
        val three = multi(2, 3, 400, 10)
        val r = Promotions.apply(listOf(line(10, 3, 200)), listOf(two, three))
        assertEquals(200L, r[0]?.discount) // RM4, not RM5
        assertEquals(2L, r[0]?.promotionId)
        assertEquals("promo 2", r[0]?.name)
        assertEquals(1L, Promotions.apply(listOf(line(10, 2, 200)), listOf(two, three))[0]?.promotionId)
        // Other products: not the same deal, the older one only.
        val wider = multi(3, 3, 400, 10, 11)
        assertEquals(100L, Promotions.apply(listOf(line(10, 3, 200)), listOf(two, wider))[0]?.discount)
    }

    /** The biggest group first is not always cheapest: 6 units are 3 + 3 (RM9.60), not 4 + 2 at the shelf price (RM10). */
    @Test
    fun theLadderFindsTheCheapestMixNotTheBiggestGroup() {
        val p = ladder(1, listOf(4 to 600L, 3 to 480L), 10)
        assertEquals(listOf(240L), savings(listOf(line(10, 6, 200)), p))
        assertEquals(listOf(200L), savings(listOf(line(10, 4, 200)), p))
        assertEquals(listOf(320L), savings(listOf(line(10, 7, 200)), p)) // 4 + 3 = RM10.80 of RM14
    }

    @Test
    fun aLadderOverMixedProductsGroupsTheDearestUnits() {
        val p = ladder(1, listOf(2 to 500L, 3 to 700L), 10, 11)
        // Units 300, 300, 280, 250, 250: 3 + 2 → 700 + 500 = 1200 of 1380, saving 180.
        val s = savings(listOf(line(10, 2, 300), line(11, 1, 280), line(12, 1, 999), line(11, 2, 250)), p)
        assertEquals(180L, s.sum())
        assertEquals(0L, s[2])
    }

    @Test
    fun aLadderOnThousandsOfUnitsStaysExact() {
        val p = ladder(1, listOf(2 to 300L, 3 to 400L), 10)
        // 20,000 units: 6,666 groups of 3 and one of 2.
        val regular = 20_000L * 200L
        assertEquals(listOf(regular - (6_666L * 400L + 300L)), savings(listOf(line(10, 20_000, 200)), p))
    }

    /** One line at one price: the ladder's total equals the cheapest of every possible mix. */
    @Test
    fun aLadderMatchesEveryMixTriedByHand() {
        val rnd = Random(73)
        repeat(300) {
            val qtys = (1..6).shuffled(rnd).take(1 + rnd.nextInt(4))
            val prices = qtys.map { it to (50L + rnd.nextInt(1_500)) }
            val p = ladder(1, prices, 10)
            val shelf = 1L + rnd.nextInt(400)
            val units = rnd.nextInt(16)
            // cheapest[k]: k units, each grouped or at the shelf price.
            val cheapest = LongArray(units + 1) { it * shelf }
            for (k in 1..units) for ((q, price) in prices) if (q <= k) cheapest[k] = minOf(cheapest[k], cheapest[k - q] + price)
            assertEquals(listOf(units * shelf - cheapest[units]), savings(listOf(line(10, units, shelf)), p), "$prices $shelf $units")
        }
    }

    @Test
    fun priceTiersTravelAsText() {
        val tiers = listOf(PriceTier(3, 400), PriceTier(5, 600))
        assertEquals("3:400,5:600", PriceTiers.encode(tiers))
        assertEquals(tiers, PriceTiers.decode("3:400,5:600"))
        assertEquals(emptyList(), PriceTiers.decode(""))
        assertEquals(listOf(PriceTier(2, 300)), PriceTiers.decode("x,2:300,:5,4:"))
    }

    @Test
    fun invalidPromotionsAreRefused() {
        assertFailsWith<IllegalArgumentException> { ladder(1, listOf(2 to 300L, 2 to 250L), 10) }
        assertFailsWith<IllegalArgumentException> { ladder(1, listOf(2 to 300L, 3 to -1L), 10) }
        assertFailsWith<IllegalArgumentException> { ladder(1, listOf(2 to 300L, 1_001 to 100L), 10) }
        assertFailsWith<IllegalArgumentException> { ladder(1, (1..7).map { it to 100L * it }, 10) }
        assertFailsWith<IllegalArgumentException> {
            Promotion(1, "x", PromoKind.BUY_GET_FREE, 1, 1, productIds = setOf(1L), morePrices = listOf(PriceTier(3, 100)))
        }
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
                val kind = rnd.nextInt(3)
                if (kind == 0) {
                    multi(k.toLong(), 2 + rnd.nextInt(4), rnd.nextInt(2_000).toLong(), *products)
                } else if (kind == 1) {
                    ladder(k.toLong(), (1..5).shuffled(rnd).take(1 + rnd.nextInt(3)).map { it to rnd.nextInt(2_000).toLong() }, *products)
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
