package com.lekaspos.domain.print

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.escpos.EscPosText
import com.lekaspos.core.escpos.TextMode
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.receipt.ReceiptDoc
import com.lekaspos.core.receipt.ReceiptItem
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.core.receipt.ReceiptPayment
import com.lekaspos.core.receipt.ReceiptTax
import com.lekaspos.core.receipt.ReceiptText
import com.lekaspos.core.time.Days
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.data.settings.StoreSettings
import java.util.TimeZone

/** Builds receipts from the stored sale (never from the live bill), so reprints are identical. */
object ReceiptBuilder {

    fun build(db: SQLiteDatabase, saleId: Long, copy: Boolean, store: StoreSettings, tz: TimeZone): ReceiptDoc? {
        val h = SaleQueries.header(db, saleId) ?: return null
        val lines = SaleQueries.lines(db, saleId)
        val pays = SaleQueries.payments(db, saleId)
        val lineDiscounts = lines.sumOf { it.discount }

        val taxes = ArrayList<ReceiptTax>(2)
        val byRate = LinkedHashMap<Long, Pair<Int, Long>>()
        for (l in lines) {
            val id = l.taxRateId ?: continue
            if (l.taxBp == 0) continue
            val prev = byRate[id]
            byRate[id] = l.taxBp to ((prev?.second ?: 0L) + l.tax)
        }
        for ((id, v) in byRate) {
            val name = TaxRateDao.get(db, id)?.name ?: "Tax"
            taxes.add(ReceiptTax(name, v.first, v.second))
        }

        // Not on a voided sale: a copy of it asked the customer to request an e-invoice for a sale
        // that no longer exists (2026-10 review).
        val qr = if (store.einvoiceQr && store.einvoiceUrl.isNotBlank() && h.kind == SaleKind.SALE && !h.voided) {
            store.einvoiceUrl
                .replace("{receipt}", h.receiptNo)
                .replace("{total}", MoneyFormat.plain(h.total, store.currency.decimals))
                .replace("{date}", Days.toYmd(Days.epochDay(h.soldAt, tz)).toString())
        } else {
            null
        }

        return ReceiptDoc(
            store = store.storeInfo(),
            refund = h.kind == SaleKind.REFUND,
            receiptNo = h.receiptNo,
            refReceiptNo = h.refSaleId?.let { SaleQueries.receiptNo(db, it) },
            soldAt = h.soldAt,
            cashier = h.staffId?.let { StaffDao.name(db, it) },
            customer = h.customerId?.let { CustomerDao.name(db, it) },
            items = lines.map {
                ReceiptItem(
                    name = oneLine(it.name), qty = it.qty, unit = it.unit?.let(::oneLine), weighed = it.qty % 1000L != 0L,
                    unitPrice = it.unitPrice, gross = it.gross, discount = it.discount, promo = it.promoName?.let(::oneLine),
                )
            },
            subtotal = h.subtotal,
            lineDiscounts = lineDiscounts,
            billDiscount = h.discount - lineDiscounts,
            tax = h.tax,
            taxes = taxes,
            pricesIncludeTax = h.pricesInclTax,
            rounding = h.rounding,
            total = h.total,
            // A cash part of 0.00 (e-wallet paid all but the 2 sen the rounding took) is no payment:
            // "Cash 0.00" under the rounding line only puzzled customers.
            payments = pays.filter { it.amount != 0L || it.tendered != 0L || it.change != 0L }
                .map { ReceiptPayment(it.name ?: kindName(it.kind), it.amount, it.tendered, it.change) },
            change = h.change,
            note = if (h.kind == SaleKind.REFUND) h.note?.let(::oneLine) else null,
            qrData = qr,
            voided = h.voided,
            reprint = copy,
        )
    }

    fun layout(doc: ReceiptDoc, cols: Int, store: StoreSettings, logo: Boolean, tz: TimeZone): List<PrintLine> =
        ReceiptLayout(cols, store.currency, ReceiptText.forLanguage(store.receiptLanguage), tz).layout(doc, logo)

    /** True when some text cannot be printed in [mode] (e.g. Tamil, or Chinese on a Latin printer). */
    fun needsImage(lines: List<PrintLine>, mode: TextMode): Boolean =
        lines.any { it is PrintLine.Text && !EscPosText.canEncode(it.text, mode) }

    /**
     * Line breaks, tabs and other control characters as spaces: text mode dropped them ("Milo\nTin"
     * printed "MiloTin") and picture mode drew boxes.
     */
    internal fun oneLine(s: String): String =
        if (s.none { it < ' ' || it == '\u007F' }) s else s.map { if (it < ' ' || it == '\u007F') ' ' else it }.joinToString("").trim()

    private fun kindName(kind: Int) = when (kind) {
        PaymentKind.CASH -> "Cash"
        PaymentKind.CARD -> "Card"
        PaymentKind.EWALLET -> "E-wallet"
        PaymentKind.CREDIT -> "Credit"
        else -> "Other"
    }
}
