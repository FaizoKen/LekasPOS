package com.lekaspos.domain.sell

import com.lekaspos.core.cart.Cart
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.product.ScanHit
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.domain.sell.CartSession.Companion.toItem
import com.lekaspos.domain.sell.CartSession.Companion.toLine
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class CheckoutDraftTest {

    private fun item(key: Long, productId: Long, qty: Long, price: Long, taxId: Long? = null, bp: Int = 0) =
        CartItem(key = key, productId = productId, name = "P$productId", qty = qty, unitPrice = price, taxRateId = taxId, taxBp = bp, unitCost = price / 2)

    private fun cash(applied: Long, tendered: Long, change: Long) = Tender(1L, PaymentKind.CASH, "Cash", true, applied, tendered, change)

    @Test
    fun cashSaleCarriesRoundingChangeAndTax() {
        var cart = Cart().add(item(1L, 1L, 2000L, 250L)).cart // 500
        cart = cart.add(item(2L, 2L, 1000L, 503L, taxId = 9L, bp = 600)).cart // 503 incl. 6% SST → 28
        val priced = cart.price(pricesIncludeTax = true)
        assertEquals(1003L, priced.total)
        val s = Settlement.cash(priced.total, 2000L, 5L) as Settlement.Result.Settled
        val d = CheckoutService.draft(cart, priced, listOf(cash(s.applied, 2000L, s.change)), s.rounding, 1L, 10L, 20L)
        assertEquals(1005L, d.total)
        assertEquals(2L, d.rounding)
        assertEquals(1005L, d.paid)
        assertEquals(995L, d.change)
        assertEquals(28L, d.tax)
        assertEquals(1003L, d.lines.sumOf { it.net })
        assertEquals(listOf(null, 9L), d.lines.map { it.taxRateId })
        assertEquals(listOf(0, 600), d.lines.map { it.taxBp })
        assertEquals(listOf(2000L, 1000L), d.lines.map { it.baseQty })
        assertEquals(listOf(1005L), d.payments.map { it.amount })
        assertEquals(10L, d.openedAt)
        assertEquals(20L, d.soldAt)
    }

    @Test
    fun splitTenderMustSettleTheTotal() {
        val cart = Cart().add(item(1L, 1L, 1000L, 1003L)).cart
        val priced = cart.price(true)
        val card = Tender(2L, PaymentKind.CARD, "Card", false, 500L, 500L, 0L)
        val s = Settlement.cash(503L, 505L, 5L) as Settlement.Result.Settled
        val ok = CheckoutService.draft(cart, priced, listOf(card, cash(s.applied, 505L, 0L)), s.rounding, null, 0L, 0L)
        assertEquals(1005L, ok.total)
        assertEquals(listOf(500L, 505L), ok.payments.map { it.amount })
        assertFailsWith<IllegalArgumentException> { CheckoutService.draft(cart, priced, listOf(card), 0L, null, 0L, 0L) }
    }

    @Test
    fun packLinesStoreBaseQuantityAndCost() {
        val carton = item(1L, 1L, 2000L, 5400L).copy(packQty = 24_000L, unitCost = 150L)
        val cart = Cart().add(carton).cart
        val priced = cart.price(true)
        val d = CheckoutService.draft(cart, priced, listOf(cash(10_800L, 10_800L, 0L)), 0L, null, 0L, 0L)
        assertEquals(2000L, d.lines[0].qty)
        assertEquals(48_000L, d.lines[0].baseQty)
        assertEquals(7_200L, d.lines[0].cost)
    }

    @Test
    fun cartLinesRoundTripThroughTheDatabaseRow() {
        val items = listOf(
            item(7L, 1L, 1253L, 1290L).copy(sellMode = SellMode.WEIGHT, unit = "kg", barcode = "2001234012530"),
            item(8L, 2L, 3000L, 250L).copy(discount = Discount.Percent(1000), priceOverridden = true, categoryId = 4L),
            item(9L, 3L, 1000L, 800L).copy(discount = Discount.Amount(50L), fixedGross = 777L, trackStock = false, addedAt = 99L),
        )
        for ((i, it) in items.withIndex()) assertEquals(it, it.toLine(i + 1).toItem())
        assertEquals(3, items[0].toLine(3).lineNo)
    }

    @Test
    fun barcodePricesAndLabelQuantities() {
        val p = SellableProduct(1L, "Milo", "pcs", SellMode.UNIT, 250L, 100L, null, null, 0, true, true)
        assertEquals(250L, BarcodeLookup.unitPrice(ScanHit(p, "1", 0, 1000L, null)))
        assertEquals(6000L, BarcodeLookup.unitPrice(ScanHit(p, "2", 0, 24_000L, null)))
        assertEquals(5400L, BarcodeLookup.unitPrice(ScanHit(p, "3", 0, 24_000L, 5400L)))
        val prawns = p.copy(sellMode = SellMode.WEIGHT, price = 1290L, unit = "kg")
        assertEquals(253L, BarcodeLookup.labelQty(prawns, 326L)) // 326 / 12.90 per kg = 0.2527 kg
        assertEquals(1000L, BarcodeLookup.labelQty(p, 326L))
        assertEquals(1000L, BarcodeLookup.labelQty(prawns.copy(price = 0L), 326L))
        assertEquals(1L, BarcodeLookup.labelQty(prawns, 0L))
    }

    @Test
    fun discountColumns() {
        assertEquals(Discount.None, CartSession.discountOf(0, 0L))
        assertEquals(Discount.Amount(150L), CartSession.discountOf(1, 150L))
        assertEquals(Discount.Percent(1000), CartSession.discountOf(2, 1000L))
        assertEquals(Discount.None, CartSession.discountOf(2, 20_000L)) // corrupt value: ignored, not a crash
        assertEquals(1 to 150L, CartSession.discountColumns(Discount.Amount(150L)))
    }
}
