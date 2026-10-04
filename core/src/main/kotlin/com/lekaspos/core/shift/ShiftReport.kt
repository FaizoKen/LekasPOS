package com.lekaspos.core.shift

import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
import com.lekaspos.core.time.DateText
import java.util.TimeZone

/**
 * Cash that went through one till's drawer during a shift (D-038). Amounts are signed as
 * stored: refunds are negative, and [voided] is the cash of documents voided during this
 * shift (a voided sale gives its cash back; a voided refund takes it back in).
 */
data class ShiftCash(
    val openingFloat: Long = 0L,
    val cashSales: Long = 0L,
    val cashRefunds: Long = 0L,
    val voided: Long = 0L,
    val cashIn: Long = 0L,
    val cashOut: Long = 0L,
    val drops: Long = 0L,
    val creditRepayments: Long = 0L,
) {
    /** What should be in the drawer. */
    val expected: Long
        get() {
            var e = Checked.add(openingFloat, cashSales)
            e = Checked.add(e, cashRefunds)
            e = Checked.sub(e, voided)
            e = Checked.add(e, cashIn)
            e = Checked.sub(e, cashOut)
            e = Checked.sub(e, drops)
            return Checked.add(e, creditRepayments)
        }

    /** Positive = more cash than expected, negative = short. */
    fun difference(counted: Long): Long = Checked.sub(counted, expected)
}

/** Takings of one payment method in a shift: payments received minus those of voided documents. */
data class MethodTotal(val name: String, val kind: Int, val count: Int, val amount: Long)

data class ShiftReport(
    val storeName: String,
    val deviceNo: Int,
    val openedBy: String?,
    val openedAt: Long,
    val closedBy: String? = null,
    val closedAt: Long? = null,
    val sales: Int = 0,
    val salesTotal: Long = 0L,
    val refunds: Int = 0,
    /** Negative, as stored. */
    val refundsTotal: Long = 0L,
    val voids: Int = 0,
    val voidsTotal: Long = 0L,
    val discounts: Long = 0L,
    val tax: Long = 0L,
    val methods: List<MethodTotal> = emptyList(),
    val cash: ShiftCash = ShiftCash(),
    /** Counted cash, once the shift is closed. */
    val counted: Long? = null,
    val creditCharged: Long = 0L,
    val creditRepaid: List<MethodTotal> = emptyList(),
    /** What the closer wrote, e.g. why the drawer is over or short (2026-10 review: it could not be said). */
    val note: String? = null,
) {
    val open: Boolean get() = closedAt == null
}

/** One label/value line of a report, shared by the screen and the printed slip. */
data class ReportRow(val label: String, val value: String, val bold: Boolean = false)

data class ReportSection(val title: String?, val rows: List<ReportRow>)

/** Report labels in the receipt language. */
data class ShiftText(
    val title: String,
    val till: String,
    val opened: String,
    val closed: String,
    val stillOpen: String,
    val openedBy: String,
    val closedBy: String,
    val salesSection: String,
    val sales: String,
    val refunds: String,
    val voids: String,
    val discounts: String,
    val tax: String,
    val paymentsSection: String,
    val cashSection: String,
    val openingFloat: String,
    val cashSales: String,
    val cashRefunds: String,
    val voided: String,
    val cashIn: String,
    val cashOut: String,
    val drops: String,
    val creditRepayments: String,
    val expected: String,
    val counted: String,
    val difference: String,
    val creditSection: String,
    val creditCharged: String,
    val creditRepaid: String,
    val note: String,
) {
    companion object {
        val EN = ShiftText(
            title = "SHIFT REPORT", till = "Till", opened = "Opened", closed = "Closed", stillOpen = "still open",
            openedBy = "Opened by", closedBy = "Closed by", salesSection = "Sales", sales = "Sales", refunds = "Refunds",
            voids = "Voids", discounts = "Discounts", tax = "Tax", paymentsSection = "Payments", cashSection = "Cash drawer",
            openingFloat = "Opening float", cashSales = "Cash sales", cashRefunds = "Cash refunds", voided = "Voided (cash)",
            cashIn = "Cash in", cashOut = "Cash out", drops = "Cash drops", creditRepayments = "Credit repaid in cash",
            expected = "Expected cash", counted = "Counted cash", difference = "Over / short",
            creditSection = "Customer credit", creditCharged = "Sold on credit", creditRepaid = "Repaid", note = "Note",
        )
        val MS = ShiftText(
            title = "LAPORAN SYIF", till = "Kaunter", opened = "Dibuka", closed = "Ditutup", stillOpen = "masih dibuka",
            openedBy = "Dibuka oleh", closedBy = "Ditutup oleh", salesSection = "Jualan", sales = "Jualan",
            refunds = "Bayaran balik", voids = "Dibatalkan", discounts = "Diskaun", tax = "Cukai", paymentsSection = "Bayaran",
            cashSection = "Laci wang", openingFloat = "Wang apungan", cashSales = "Jualan tunai",
            cashRefunds = "Bayaran balik tunai", voided = "Dibatalkan (tunai)", cashIn = "Wang masuk", cashOut = "Wang keluar",
            drops = "Simpanan wang", creditRepayments = "Bayaran hutang tunai", expected = "Tunai dijangka",
            counted = "Tunai dikira", difference = "Lebih / kurang", creditSection = "Kredit pelanggan",
            creditCharged = "Jualan kredit", creditRepaid = "Bayaran hutang", note = "Catatan",
        )

        fun forLanguage(lang: String): ShiftText = if (lang == "ms") MS else EN
    }
}

/**
 * Turns a [ShiftReport] into sections (for the screen) and printer lines. Pure. [showCash]
 * = false leaves out expected cash and the difference (blind close, D-038).
 */
class ShiftReportLayout(private val currency: CurrencySpec, private val t: ShiftText, private val tz: TimeZone) {

    fun sections(r: ShiftReport, showCash: Boolean = true): List<ReportSection> {
        val out = ArrayList<ReportSection>(5)
        out.add(
            ReportSection(
                null,
                listOfNotNull(
                    ReportRow(t.till, r.deviceNo.toString()),
                    ReportRow(t.opened, DateText.dateTime(r.openedAt, tz)),
                    r.openedBy?.let { ReportRow(t.openedBy, it) },
                    ReportRow(t.closed, r.closedAt?.let { DateText.dateTime(it, tz) } ?: t.stillOpen),
                    r.closedBy?.let { ReportRow(t.closedBy, it) },
                ),
            ),
        )
        out.add(
            ReportSection(
                t.salesSection,
                listOfNotNull(
                    ReportRow("${t.sales} (${r.sales})", money(r.salesTotal), bold = true),
                    if (r.refunds > 0) ReportRow("${t.refunds} (${r.refunds})", money(r.refundsTotal)) else null,
                    if (r.voids > 0) ReportRow("${t.voids} (${r.voids})", money(r.voidsTotal)) else null,
                    if (r.discounts != 0L) ReportRow(t.discounts, money(r.discounts)) else null,
                    if (r.tax != 0L) ReportRow(t.tax, money(r.tax)) else null,
                ),
            ),
        )
        if (r.methods.isNotEmpty()) {
            out.add(ReportSection(t.paymentsSection, r.methods.map { ReportRow("${it.name} (${it.count})", money(it.amount)) }))
        }
        if (showCash) {
            val c = r.cash
            val rows = ArrayList<ReportRow>(10)
            rows.add(ReportRow(t.openingFloat, money(c.openingFloat)))
            rows.add(ReportRow(t.cashSales, money(c.cashSales)))
            if (c.cashRefunds != 0L) rows.add(ReportRow(t.cashRefunds, money(c.cashRefunds)))
            if (c.voided != 0L) rows.add(ReportRow(t.voided, money(-c.voided)))
            if (c.cashIn != 0L) rows.add(ReportRow(t.cashIn, money(c.cashIn)))
            if (c.cashOut != 0L) rows.add(ReportRow(t.cashOut, money(-c.cashOut)))
            if (c.drops != 0L) rows.add(ReportRow(t.drops, money(-c.drops)))
            if (c.creditRepayments != 0L) rows.add(ReportRow(t.creditRepayments, money(c.creditRepayments)))
            rows.add(ReportRow(t.expected, money(c.expected), bold = true))
            if (r.counted != null) {
                rows.add(ReportRow(t.counted, money(r.counted), bold = true))
                rows.add(ReportRow(t.difference, signed(c.difference(r.counted)), bold = true))
            }
            out.add(ReportSection(t.cashSection, rows))
        } else if (r.counted != null) {
            out.add(ReportSection(t.cashSection, listOf(ReportRow(t.counted, money(r.counted), bold = true))))
        }
        if (r.creditCharged != 0L || r.creditRepaid.isNotEmpty()) {
            val rows = ArrayList<ReportRow>(4)
            rows.add(ReportRow(t.creditCharged, money(r.creditCharged)))
            for (m in r.creditRepaid) rows.add(ReportRow("${t.creditRepaid}: ${m.name} (${m.count})", money(m.amount)))
            out.add(ReportSection(t.creditSection, rows))
        }
        if (!r.note.isNullOrBlank()) out.add(ReportSection(t.note, listOf(ReportRow(r.note.trim(), ""))))
        return out
    }

    /** The printed slip on a [cols]-column printer. */
    fun lines(r: ShiftReport, cols: Int, showCash: Boolean = true): List<PrintLine> {
        val out = ArrayList<PrintLine>(48)
        if (r.storeName.isNotBlank()) center(r.storeName, cols, out, bold = true)
        center(t.title, cols, out, bold = true)
        out.add(PrintLine.Text(rule('=', cols)))
        for ((i, s) in sections(r, showCash).withIndex()) {
            if (i > 0) out.add(PrintLine.Text(rule('-', cols)))
            if (s.title != null) out.add(PrintLine.Text(TextWidth.take(s.title.uppercase(), cols), bold = true))
            for (row in s.rows) twoCol(row.label, row.value, cols, row.bold, out)
        }
        out.add(PrintLine.Text(rule('=', cols)))
        return out
    }

    private fun money(v: Long): String = MoneyFormat.format(v, currency, withSymbol = false)

    private fun signed(v: Long): String = if (v > 0L) "+" + money(v) else money(v)

    private fun rule(c: Char, cols: Int): String {
        val sb = StringBuilder(cols)
        for (i in 0 until cols) sb.append(c)
        return sb.toString()
    }

    private fun center(s: String, cols: Int, out: MutableList<PrintLine>, bold: Boolean) {
        for (line in TextWidth.wrap(s, cols)) out.add(PrintLine.Text(TextWidth.center(line, cols).trimEnd(), bold))
    }

    private fun twoCol(left: String, right: String, cols: Int, bold: Boolean, out: MutableList<PrintLine>) {
        val rw = TextWidth.of(right)
        val lines = TextWidth.wrap(left, cols)
        for (i in 0 until lines.size - 1) out.add(PrintLine.Text(lines[i], bold))
        val last = lines.lastOrNull() ?: ""
        if (TextWidth.of(last) + 1 + rw <= cols) {
            out.add(PrintLine.Text(TextWidth.padEnd(last, cols - rw) + right, bold))
        } else {
            out.add(PrintLine.Text(last, bold))
            out.add(PrintLine.Text(TextWidth.padStart(right, cols), bold))
        }
    }
}
