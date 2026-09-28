package com.lekaspos.core.cart

import com.lekaspos.core.model.SellMode
import com.lekaspos.core.pricing.Discount
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CartTest {

    private fun item(key: Long, productId: Long? = 1L, qty: Long = 1000L, price: Long = 250L) =
        CartItem(key = key, productId = productId, name = "Item $productId", qty = qty, unitPrice = price)

    @Test
    fun scanningTheSameItemAgainGrowsTheLine() {
        val a = Cart().add(item(1L))
        val b = a.cart.add(item(2L))
        assertTrue(b.merged)
        assertEquals(1L, b.key)
        assertEquals(1, b.cart.items.size)
        assertEquals(2000L, b.cart.items[0].qty)
    }

    @Test
    fun weighedPricedAndDiscountedLinesStaySeparate() {
        val weighed = item(1L, qty = 253L).copy(sellMode = SellMode.WEIGHT)
        val c1 = Cart().add(weighed).cart.add(weighed.copy(key = 2L, qty = 400L))
        assertFalse(c1.merged)
        assertEquals(2, c1.cart.items.size)

        val discounted = Cart().add(item(1L)).cart.setDiscount(1L, Discount.Percent(1000))
        assertFalse(discounted.add(item(2L)).merged)

        val overridden = Cart().add(item(1L)).cart.overridePrice(1L, 199L)
        assertFalse(overridden.add(item(2L)).merged)

        val label = item(1L).copy(fixedGross = 326L)
        assertFalse(Cart().add(label).cart.add(label.copy(key = 2L)).merged)
    }

    @Test
    fun packBarcodesDoNotMergeWithSingles() {
        val single = item(1L)
        val carton = item(2L, price = 5400L).copy(packQty = 24_000L)
        val r = Cart().add(single).cart.add(carton)
        assertFalse(r.merged)
        assertEquals(2, r.cart.items.size)
    }

    @Test
    fun baseQuantityAndCostOfPacks() {
        val carton = item(1L, qty = 2000L, price = 5400L).copy(packQty = 24_000L, unitCost = 150L)
        assertEquals(48_000L, carton.baseQty)
        assertEquals(7_200L, carton.cost)
        val weighed = item(2L, qty = 253L, price = 1290L).copy(sellMode = SellMode.WEIGHT, unitCost = 800L)
        assertEquals(253L, weighed.baseQty)
        assertEquals(202L, weighed.cost) // 800 × 0.253 = 202.4
    }

    @Test
    fun editsKeepOtherLines() {
        var cart = Cart().add(item(1L, productId = 1L)).cart
        cart = cart.add(item(2L, productId = 2L)).cart
        cart = cart.setQty(2L, 3000L)
        assertEquals(3000L, cart.item(2L)?.qty)
        cart = cart.overridePrice(1L, 199L)
        assertEquals(199L, cart.item(1L)?.unitPrice)
        assertTrue(cart.item(1L)?.priceOverridden == true)
        cart = cart.remove(1L)
        assertEquals(listOf(2L), cart.items.map { it.key })
        assertFailsWith<IllegalArgumentException> { cart.setQty(1L, 1000L) }
    }

    @Test
    fun totalsComeFromThePricingEngine() {
        var cart = Cart().add(item(1L, productId = 1L, qty = 3000L, price = 120L)).cart // 360
        cart = cart.add(item(2L, productId = 2L, price = 1999L)).cart.setDiscount(2L, Discount.Percent(1000)) // 1999 - 200
        cart = cart.withBillDiscount(Discount.Amount(59L))
        val priced = cart.price(pricesIncludeTax = true)
        assertEquals(2359L, priced.subtotal)
        assertEquals(200L, priced.lineDiscounts)
        assertEquals(59L, priced.billDiscount)
        assertEquals(2100L, priced.total)
        assertEquals(2100L, priced.lines.sumOf { it.net })
    }

    @Test
    fun pieceCountCountsWeighedLinesOnce() {
        var cart = Cart().add(item(1L, productId = 1L, qty = 3000L)).cart
        cart = cart.add(item(2L, productId = 2L, qty = 253L).copy(sellMode = SellMode.WEIGHT)).cart
        assertEquals(4L, cart.pieceCount)
    }

    @Test
    fun invalidLinesAreRejected() {
        assertFailsWith<IllegalArgumentException> { item(1L, qty = 0L) }
        assertFailsWith<IllegalArgumentException> { item(1L, price = -1L) }
        val cart = Cart().add(item(1L, productId = 1L)).cart
        assertFailsWith<IllegalArgumentException> { cart.add(item(1L, productId = 2L)) }
    }
}
