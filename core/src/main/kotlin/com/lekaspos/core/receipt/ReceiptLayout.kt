package com.lekaspos.core.receipt

import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.text.TextWidth
import com.lekaspos.core.time.DateText
import java.util.TimeZone

/** One printed line of a receipt. Text is already padded/aligned to the paper width. */
sealed class PrintLine {
    /** [big] = double width and height: then [text] fits in half the columns. */
    data class Text(val text: String, val bold: Boolean = false, val big: Boolean = false) : PrintLine()

    /** The store logo (rendered by the app for the printer's dot width). */
    object Logo : PrintLine()

    data class Qr(val data: String) : PrintLine()

    data class Feed(val lines: Int) : PrintLine()
}

/**
 * Lays a receipt out on a fixed grid of [cols] columns (58 mm paper: 32, 80 mm: 42 or 48).
 * The same lines feed the ESC/POS text encoder, the raster renderer and the share image, so
 * every output looks the same. Pure: no Android, no I/O.
 */
class ReceiptLayout(
    private val cols: Int,
    private val currency: CurrencySpec,
    private val t: ReceiptText,
    private val tz: TimeZone,
) {
    init {
        require(cols in MIN_COLS..MAX_COLS) { "cols must be $MIN_COLS..$MAX_COLS" }
    }

    fun layout(doc: ReceiptDoc, logo: Boolean = false): List<PrintLine> {
        val out = ArrayList<PrintLine>(64)
        if (logo) out.add(PrintLine.Logo)
        header(doc, out)
        for (item in doc.items) item(item, out)
        out.add(rule('-'))
        totals(doc, out)
        footer(doc, out)
        return out
    }

    private fun header(doc: ReceiptDoc, out: MutableList<PrintLine>) {
        val s = doc.store
        val half = cols / 2
        if (s.name.isNotBlank()) {
            if (TextWidth.of(s.name) <= half) {
                out.add(PrintLine.Text(TextWidth.center(s.name, half).trimEnd(), bold = true, big = true))
            } else {
                center(s.name, out, bold = true)
            }
        }
        multiline(s.address, out)
        s.phone.nonBlank()?.let { center("${t.phone}: $it", out) }
        s.email.nonBlank()?.let { center(it, out) }
        s.brn.nonBlank()?.let { center("${t.brn}: $it", out) }
        s.sstNo.nonBlank()?.let { center("${t.sst}: $it", out) }
        s.tin.nonBlank()?.let { center("${t.tin}: $it", out) }
        multiline(s.header, out)
        out.add(rule('='))
        center(if (doc.refund) t.refund else t.receipt, out, bold = true)
        if (doc.voided) center("*** ${t.voided} ***", out, bold = true)
        if (doc.reprint) center("*** ${t.reprint} ***", out)
        left("${t.receiptNo}: ${doc.receiptNo}", out)
        left("${t.date}: ${DateText.dateTime(doc.soldAt, tz)}", out)
        doc.refReceiptNo?.let { left("${t.original}: $it", out) }
        doc.cashier.nonBlank()?.let { left("${t.cashier}: $it", out) }
        doc.customer.nonBlank()?.let { left("${t.customer}: $it", out) }
        out.add(rule('-'))
    }

    private fun item(item: ReceiptItem, out: MutableList<PrintLine>) {
        val amount = money(item.gross)
        val simple = !item.weighed && (item.qty == 1000L || item.qty == -1000L) && item.discount == 0L
        if (simple) {
            twoCol(item.name, amount, out)
        } else {
            for (line in TextWidth.wrap(item.name, cols)) out.add(PrintLine.Text(line))
            val qty = MoneyFormat.formatQty(item.qty)
            val price = money(item.unitPrice)
            val unit = item.unit.nonBlank()
            // A price-labelled pack from the scale: its weight is worked back from the label's price
            // and today's price per kg, so "weight x price/kg" would not come to the amount (0.388 kg
            // x 12.90/kg is 5.01, the label says 5.00) — the weight alone (2026-10 review).
            val labelled = item.weighed && PricingEngine.lineGross(item.unitPrice, item.qty) != item.gross
            val detail = when {
                labelled -> "  $qty${unit?.let { " $it" }.orEmpty()}"
                item.weighed && unit != null -> "  $qty $unit x $price/$unit"
                else -> "  $qty x $price"
            }
            twoCol(detail, amount, out)
        }
        if (item.discount != 0L) twoCol("  ${item.promo ?: t.discount}", money(Checked.neg(item.discount)), out)
    }

    private fun totals(doc: ReceiptDoc, out: MutableList<PrintLine>) {
        twoCol("${t.items}: ${pieces(doc.items)}", "", out)
        twoCol(t.subtotal, money(doc.subtotal), out)
        if (doc.lineDiscounts != 0L) twoCol(t.itemDiscounts, money(Checked.neg(doc.lineDiscounts)), out)
        if (doc.billDiscount != 0L) twoCol(t.billDiscount, money(Checked.neg(doc.billDiscount)), out)
        if (!doc.pricesIncludeTax) for (tax in doc.taxes) twoCol("${tax.name} ${percent(tax.bp)}", money(tax.amount), out)
        if (doc.rounding != 0L) twoCol(t.rounding, money(doc.rounding), out)

        val total = MoneyFormat.format(doc.total, currency)
        val half = cols / 2
        if (TextWidth.of(t.total) + 1 + TextWidth.of(total) <= half) {
            out.add(PrintLine.Text(TextWidth.padEnd(t.total, half - TextWidth.of(total)) + total, bold = true, big = true))
        } else {
            twoCol(t.total, total, out, bold = true)
        }
        for (p in doc.payments) {
            // The cash handed over when it was more than the amount; a refund (negative amount, nothing
            // tendered) prints its amount.
            val shown = if (p.amount >= 0L && p.tendered > p.amount) p.tendered else p.amount
            twoCol(p.name, money(shown), out)
        }
        if (doc.change != 0L) twoCol(t.change, money(doc.change), out, bold = true)
        if (doc.pricesIncludeTax) {
            for (tax in doc.taxes) twoCol("${t.taxIncluded} ${tax.name} ${percent(tax.bp)}", money(tax.amount), out)
        }
        out.add(rule('-'))
    }

    private fun footer(doc: ReceiptDoc, out: MutableList<PrintLine>) {
        doc.note.nonBlank()?.let { left("${t.note}: $it", out) }
        val footer = doc.store.footer.nonBlank()
        if (footer != null) multiline(footer, out) else center(t.thankYou, out)
        doc.qrData.nonBlank()?.let {
            out.add(PrintLine.Feed(1))
            out.add(PrintLine.Qr(it))
            center(t.einvoice, out)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun money(v: Long): String = MoneyFormat.format(v, currency, withSymbol = false)

    private fun rule(c: Char): PrintLine.Text {
        val sb = StringBuilder(cols)
        for (i in 0 until cols) sb.append(c)
        return PrintLine.Text(sb.toString())
    }

    private fun center(s: String, out: MutableList<PrintLine>, bold: Boolean = false) {
        for (line in TextWidth.wrap(s, cols)) out.add(PrintLine.Text(TextWidth.center(line, cols).trimEnd(), bold))
    }

    private fun left(s: String, out: MutableList<PrintLine>) {
        for (line in wrapIndented(s)) out.add(PrintLine.Text(line))
    }

    /** Wraps like [TextWidth.wrap] but keeps the leading indentation on every line. */
    private fun wrapIndented(s: String): List<String> {
        val indent = s.length - s.trimStart(' ').length
        if (indent == 0 || indent >= cols / 2) return TextWidth.wrap(s, cols)
        val pad = s.substring(0, indent)
        return TextWidth.wrap(s.substring(indent), cols - indent).map { pad + it }
    }

    private fun multiline(s: String?, out: MutableList<PrintLine>) {
        if (s.isNullOrBlank()) return
        for (line in s.split('\n')) if (line.isNotBlank()) center(line.trim(), out)
    }

    /** [left] and [right] on one line when they fit; otherwise [left] wraps and [right] ends its last line. */
    private fun twoCol(left: String, right: String, out: MutableList<PrintLine>, bold: Boolean = false) {
        val rw = TextWidth.of(right)
        val lines = wrapIndented(left)
        for (i in 0 until lines.size - 1) out.add(PrintLine.Text(lines[i], bold))
        val last = lines[lines.size - 1]
        if (rw == 0) {
            out.add(PrintLine.Text(last, bold))
        } else if (TextWidth.of(last) + 1 + rw <= cols) {
            out.add(PrintLine.Text(TextWidth.padEnd(last, cols - rw) + right, bold))
        } else {
            out.add(PrintLine.Text(last, bold))
            out.add(PrintLine.Text(TextWidth.padStart(right, cols), bold))
        }
    }

    /** Pieces for counted goods (rounded up), one per weighed line. */
    private fun pieces(items: List<ReceiptItem>): Long {
        var n = 0L
        for (i in items) {
            val q = if (i.qty < 0L) -i.qty else i.qty
            n += if (i.weighed) 1L else (q + 999L) / 1000L
        }
        return n
    }

    companion object {
        const val MIN_COLS = 24
        const val MAX_COLS = 64

        /** "6%", "12.5%", "0.25%". */
        fun percent(bp: Int): String {
            val whole = bp / 100
            var frac = bp % 100
            if (frac == 0) return "$whole%"
            val digits = if (frac % 10 == 0) 1 else 2
            if (digits == 1) frac /= 10
            val f = if (digits == 2 && frac < 10) "0$frac" else frac.toString()
            return "$whole.$f%"
        }

        private fun String?.nonBlank(): String? = if (this == null || isBlank()) null else trim()
    }
}
