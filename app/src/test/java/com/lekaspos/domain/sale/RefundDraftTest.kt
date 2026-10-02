package com.lekaspos.domain.sale

import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.refund.Refunds
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.sale.SaleHeader
import com.lekaspos.data.sale.SaleLineFull
import kotlin.test.assertEquals
import org.junit.Test

class RefundDraftTest {

    private val header = SaleHeader(
        id = 5L, kind = SaleKind.SALE, receiptNo = "AB12-0-000001", refSaleId = null, staffId = 1L, customerId = null,
        openedAt = 0L, soldAt = 0L, lineCount = 1, subtotal = 1003L, discount = 0L, tax = 57L, rounding = 2L, total = 1005L,
        paid = 1005L, change = 0L, pricesInclTax = true, status = 0, refunded = 0L, note = null,
    )
    private val line = SaleLineFull(
        id = 11L, lineNo = 1, productId = 1L, refLineId = null, name = "Susu", barcode = "9556", unit = "pcs", categoryId = 3L,
        qty = 3000L, baseQty = 3000L, unitPrice = 334L, gross = 1003L, discount = 0L, billDiscount = 0L, net = 1003L,
        taxRateId = 9L, taxBp = 600, tax = 57L, cost = 600L, priceOverridden = false, stockQty = -3000L,
    )
    private val cash = PaymentMethod(1L, "Cash", PaymentKind.CASH, true, 1)
    private val card = PaymentMethod(2L, "Card", PaymentKind.CARD, false, 2)

    private fun parts(qty: Long) = listOf(Refunds.part(SaleActions.source(line, null), qty) to line)

    @Test
    fun cashRefundMirrorsTheRounding() {
        val d = SaleActions.refundDraft(header, parts(1000L), restock = true, reason = "damaged", method = cash, cashStep = 5L, staffId = 1L, now = 7L)
        assertEquals(SaleKind.REFUND, d.kind)
        assertEquals(5L, d.refSaleId)
        assertEquals(-335L, d.total) // 334 owed → 335 paid out in cash
        assertEquals(-1L, d.rounding)
        assertEquals(listOf(-335L), d.payments.map { it.amount })
        val l = d.lines.single()
        assertEquals(-1000L, l.qty)
        assertEquals(-1000L, l.baseQty)
        assertEquals(-334L, l.net)
        assertEquals(-19L, l.tax)
        assertEquals(-200L, l.cost)
        assertEquals(11L, l.refLineId)
        assertEquals(true, l.trackStock)
        assertEquals(true, l.restock)
        assertEquals("damaged", d.note)
    }

    @Test
    fun cardRefundPaysTheExactAmount() {
        val d = SaleActions.refundDraft(header, parts(3000L), restock = false, reason = "wrong item", method = card, cashStep = 5L, staffId = null, now = 7L)
        assertEquals(-1003L, d.total)
        assertEquals(0L, d.rounding)
        assertEquals(-1003L, d.subtotal)
        assertEquals(-57L, d.tax)
        assertEquals(false, d.lines.single().restock)
    }

    /** 2026-10 review: Cash was always offered first, also for goods taken on customer credit. */
    @Test
    fun aRefundPaysBackTheWayTheSaleWasPaid() {
        fun pay(method: Long, kind: Int, amount: Long) = com.lekaspos.data.sale.PaymentRow(method, method, null, kind, amount, amount, 0L, false)
        val credit = SaleActions.RefundInfo(header, listOf(line), emptyMap(), payments = listOf(pay(1L, PaymentKind.CASH, 800L), pay(4L, PaymentKind.CREDIT, 205L)))
        assertEquals(4L, credit.usualMethodId) // any part on credit: back onto the account
        val card = SaleActions.RefundInfo(header, listOf(line), emptyMap(), payments = listOf(pay(1L, PaymentKind.CASH, 205L), pay(2L, PaymentKind.CARD, 800L)))
        assertEquals(2L, card.usualMethodId)
        assertEquals(null, SaleActions.RefundInfo(header, listOf(line), emptyMap()).usualMethodId)
    }

    @Test
    fun goodsNotPutBackKeepTheirCostInCostOfGoods() {
        // Spoiled milk taken back and thrown away: the money goes back, the goods are lost.
        val thrownAway = SaleActions.refundDraft(header, parts(1000L), restock = false, reason = "spoiled", method = cash, cashStep = 5L, staffId = 1L, now = 7L)
        assertEquals(0L, thrownAway.lines.single().cost)
        assertEquals(-334L, thrownAway.lines.single().net)
        val backOnTheShelf = SaleActions.refundDraft(header, parts(1000L), restock = true, reason = "unopened", method = cash, cashStep = 5L, staffId = 1L, now = 7L)
        assertEquals(-200L, backOnTheShelf.lines.single().cost)
    }
}
