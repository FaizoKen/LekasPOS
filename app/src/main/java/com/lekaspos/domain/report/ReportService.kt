package com.lekaspos.domain.report

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.app.AppGraph
import com.lekaspos.core.csv.CsvWriter
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.report.Bucket
import com.lekaspos.core.report.Buckets
import com.lekaspos.core.report.Granularity
import com.lekaspos.core.report.Period
import com.lekaspos.core.report.ReportMath
import com.lekaspos.core.staff.Checks
import com.lekaspos.core.staff.StaffCheck
import com.lekaspos.core.staff.Tally
import com.lekaspos.core.time.DateText
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.report.CategoryTotal
import com.lekaspos.data.report.PaymentTotal
import com.lekaspos.data.report.ProductTotal
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.report.ReceiptRow
import com.lekaspos.data.report.SlowMover
import com.lekaspos.data.report.StaffTotal
import com.lekaspos.data.report.StockValue
import com.lekaspos.data.report.Totals
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.data.staff.StaffDao
import java.util.TimeZone
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Sales reports from the summary tables (D-043) and their CSV exports (D-041). Reading
 * reports needs the REPORTS permission (profit and cost are sensitive).
 */
class ReportService(private val graph: AppGraph) {

    /** Everything the report screen shows for one period. */
    data class Report(
        val period: Period,
        val totals: Totals,
        /** The totals of [compare], for the change in sales. */
        val previous: Totals,
        val granularity: Granularity,
        val buckets: List<Bucket>,
        val payments: List<PaymentTotal>,
        val staff: List<StaffTotal>,
        val categories: List<CategoryTotal>,
        val topProducts: List<ProductTotal>,
        val compare: Period = period.previous(),
    ) {
        val marginBp: Int? get() = ReportMath.marginBp(totals.netEx, totals.cost)
        val averageSale: Long get() = ReportMath.average(totals.total + totals.refundTotal, totals.saleCount)
    }

    /** Stock on hand at cost, by category; values in minor units. */
    data class Stock(val categories: List<StockValue>, val products: Long, val value: Long)

    enum class Export { SUMMARY, DAILY, PRODUCTS, RECEIPTS }

    /** [compare]: the period the totals are compared with ([Preset.comparison] for a preset). */
    suspend fun build(p: Period, topN: Int = 20, compare: Period = p.previous()): Report {
        graph.permissions.actor(Perm.REPORTS)
        return graph.db().read { r -> build(r, p, topN, compare) }
    }

    suspend fun slowMovers(p: Period, limit: Int = 200): Pair<List<SlowMover>, Pair<Long, Long>> {
        graph.permissions.actor(Perm.REPORTS)
        return graph.db().read { r -> ReportDao.slowMovers(r, p, limit).let { it.items to (it.count to it.valueMilli) } }
    }

    suspend fun stock(): Stock {
        graph.permissions.actor(Perm.REPORTS)
        return graph.db().read { r -> stock(r) }
    }

    /**
     * The staff check of [p] (D-067): per person, their sales, what the owner should look at (bills
     * cleared, items taken off — after the total was shown above all —, voids, refunds, the drawer opened
     * without a sale …) and the cash of the shifts they opened. Needs VIEW_AUDIT, like the activity log.
     */
    suspend fun staffCheck(p: Period, tz: TimeZone = TimeZone.getDefault()): List<StaffCheck> {
        graph.permissions.actor(Perm.VIEW_AUDIT)
        return graph.db().read { r -> staffCheck(r, p, tz) }
    }

    /** Writes [what] for [p] as CSV (UTF-8 with BOM) to [out]; returns the number of data rows. */
    suspend fun export(what: Export, p: Period, out: Appendable, tz: TimeZone = TimeZone.getDefault()): Long {
        graph.permissions.actor(Perm.REPORTS)
        return write(what, p, out, tz)
    }

    /**
     * [export] without asking who may: for the daily report to Google Drive that the owner turned on
     * (D-065), which runs in the background with nobody signed in.
     */
    suspend fun write(what: Export, p: Period, out: Appendable, tz: TimeZone = TimeZone.getDefault()): Long {
        val currency = graph.settings.store.value.currency
        val db = graph.db()
        out.append(CsvWriter.BOM)
        val w = CsvWriter(out)
        when (what) {
            Export.SUMMARY -> db.read { r -> summaryCsv(w, build(r, p, 50), stock(r), currency) }
            Export.DAILY -> db.read { r -> dailyCsv(w, Buckets.of(ReportDao.days(r, p.from, p.to), p, Granularity.DAY), currency, dayChecks(r, p, tz)) }
            Export.PRODUCTS -> db.read { r ->
                w.row("product", "qty", "net_sales_ex_tax", "tax", "cost", "gross_profit")
                ReportDao.eachProduct(r, p) { t ->
                    w.row(t.name ?: OTHER, qty(t.qty), m(t.netEx, currency), m(t.tax, currency), m(t.cost, currency), m(t.grossProfit, currency))
                }
            }
            Export.RECEIPTS -> {
                w.row(
                    "receipt_no", "date", "time", "type", "status", "cashier", "customer", "customer_tin", "items",
                    "subtotal", "discount", "net_ex_tax", "tax", "rounding", "total", "payments",
                )
                // The stored business day, like the report itself (not sold_at in this phone's zone).
                var after: ReceiptRow? = null
                while (true) {
                    coroutineContext.ensureActive()
                    val page = db.read { r -> ReportDao.receiptsOfDays(r, p.from, p.to, after, PAGE) }
                    for (s in page) receiptCsv(w, s, currency, tz)
                    if (page.size < PAGE) break
                    after = page.last()
                }
            }
        }
        return w.rows - 1
    }

    companion object {
        private const val PAGE = 500
        private const val OTHER = "(other items)"

        fun build(r: SQLiteDatabase, p: Period, topN: Int, compare: Period = p.previous()): Report {
            val g = Granularity.forPeriod(p)
            val products = ReportDao.summary(r, p, topN) // categories and best sellers in one reading (D-058)
            return Report(
                period = p,
                totals = ReportDao.totals(r, p.from, p.to),
                previous = ReportDao.totals(r, compare.from, compare.to),
                compare = compare,
                granularity = g,
                buckets = Buckets.of(ReportDao.days(r, p.from, p.to), p, g),
                payments = ReportDao.byPayment(r, p.from, p.to),
                staff = ReportDao.byStaff(r, p.from, p.to),
                categories = products.categories,
                topProducts = products.top,
            )
        }

        fun stock(r: SQLiteDatabase): Stock {
            val cats = ReportDao.stockValue(r)
            return Stock(cats, cats.sumOf { it.products }, ReportMath.value(cats.sumOf { it.valueMilli }, 1L))
        }

        private fun m(v: Long, c: CurrencySpec) = MoneyFormat.plain(v, c.decimals)

        private fun qty(v: Long) = MoneyFormat.formatQty(v)

        private fun date(day: Long) = DateText.isoDate(day)

        private fun summaryCsv(w: CsvWriter, rep: Report, stock: Stock, c: CurrencySpec) {
            val t = rep.totals
            w.row("section", "item", "count", "amount")
            w.row("period", "from", null, date(rep.period.from))
            w.row("period", "to", null, date(rep.period.to - 1))
            w.row("sales", "sales", t.saleCount.toString(), m(t.total + t.refundTotal, c))
            w.row("sales", "refunds", t.refundCount.toString(), m(-t.refundTotal, c))
            w.row("sales", "voided", t.voidCount.toString(), null)
            w.row("sales", "gross", null, m(t.gross, c))
            w.row("sales", "discounts", null, m(t.discount, c))
            w.row("sales", "net_sales_ex_tax", null, m(t.netEx, c))
            w.row("sales", "tax", null, m(t.tax, c))
            w.row("sales", "rounding", null, m(t.rounding, c))
            w.row("sales", "total", null, m(t.total, c))
            w.row("sales", "cost", null, m(t.cost, c))
            w.row("sales", "gross_profit", null, m(t.grossProfit, c))
            w.row("sales", "margin_percent", null, rep.marginBp?.let { MoneyFormat.plain(it.toLong(), 2) })
            for (x in rep.payments) w.row("payment", x.name ?: "#${x.methodId}", x.count.toString(), m(x.amount, c))
            for (x in rep.staff) w.row("cashier", x.name ?: "-", x.saleCount.toString(), m(x.total, c))
            for (x in rep.categories) w.row("category", x.name ?: "-", qty(x.qty), m(x.netEx, c))
            for (x in rep.topProducts) w.row("top_product", x.name ?: OTHER, qty(x.qty), m(x.netEx, c))
            for (x in stock.categories) w.row("stock_value", x.name ?: "-", x.products.toString(), m(ReportMath.value(x.valueMilli, 1L), c))
        }

        /** See [ReportService.staffCheck]: everyone with sales, checks or closed shifts in [p], the most to look at first. */
        fun staffCheck(r: SQLiteDatabase, p: Period, tz: TimeZone): List<StaffCheck> {
            val from = Days.startOfDay(p.from, tz)
            val to = Days.startOfDay(p.to, tz)
            val names = StaffDao.names(r)
            val sales = ReportDao.byStaff(r, p.from, p.to).associateBy { it.staffId }
            val audit = AuditDao.totalsByStaff(r, from, to)
            val shifts = ShiftDao.overShortByOpener(r, from, to)
            val ids = LinkedHashSet<Long>().apply {
                addAll(sales.keys)
                addAll(audit.keys)
                addAll(shifts.keys)
            }
            val list = ids.map { id ->
                val s = sales[id]
                val sh = shifts[id]
                StaffCheck(
                    staffId = id, name = names[id] ?: s?.name, sales = Tally(s?.saleCount ?: 0L, s?.total ?: 0L),
                    checks = Checks.of(audit[id].orEmpty()), shifts = sh?.shifts ?: 0L, overShort = sh?.difference ?: 0L,
                    shortShifts = sh?.short ?: 0L,
                )
            }
            return StaffCheck.rank(list.filter { it.sales.count > 0L || !it.checks.isEmpty || it.shifts > 0L })
        }

        /** Each day's checks of every till (D-067) and the over/short of the shifts opened that day, for the daily CSV. */
        private fun dayChecks(r: SQLiteDatabase, p: Period, tz: TimeZone): Map<Long, Pair<Checks, Long>> {
            val out = HashMap<Long, Pair<Checks, Long>>()
            var day = p.from
            while (day < p.to) {
                val from = Days.startOfDay(day, tz)
                val to = Days.startOfDay(day + 1, tz)
                val checks = Checks.of(AuditDao.totalsByStaff(r, from, to).values.flatten())
                val overShort = ShiftDao.overShortByOpener(r, from, to).values.sumOf { it.difference }
                out[day] = checks to overShort
                day++
            }
            return out
        }

        /**
         * A row per day. The checks columns (D-067) came after the sales ones in 1.12.0: what the owner
         * reads in Google Drive every morning (D-065) says when bills were cleared or items taken off.
         */
        private fun dailyCsv(w: CsvWriter, days: List<Bucket>, c: CurrencySpec, checks: Map<Long, Pair<Checks, Long>>) {
            w.row(
                "date", "sales", "refunds", "net_sales_ex_tax", "tax", "total", "discount", "cost", "gross_profit",
                "bills_cleared", "bills_cleared_value", "items_taken_off", "items_taken_off_value", "after_total_shown",
                "after_total_shown_value", "drawer_opened_no_sale", "cash_over_short",
            )
            for (b in days) {
                val (k, overShort) = checks[b.start] ?: (Checks() to 0L)
                w.row(
                    date(b.start), b.sales.toString(), b.refunds.toString(), m(b.netEx, c), m(b.tax, c), m(b.total, c),
                    m(b.discount, c), m(b.cost, c), m(b.grossProfit, c),
                    k.cleared.count.toString(), m(k.cleared.amount, c), k.removed.count.toString(), m(k.removed.amount, c),
                    k.afterPay.count.toString(), m(k.afterPay.amount, c), k.drawerOpens.toString(), m(overShort, c),
                )
            }
        }

        private fun receiptCsv(w: CsvWriter, s: ReceiptRow, c: CurrencySpec, tz: TimeZone) {
            w.row(
                s.receiptNo, date(s.day), DateText.time(s.soldAt, tz), if (s.kind == SaleKind.REFUND) "refund" else "sale",
                if (s.status == SaleStatus.VOIDED) "voided" else "completed", s.staff, s.customer, s.customerTin, s.lines.toString(),
                m(s.subtotal, c), m(s.discount, c), m(s.netEx, c), m(s.tax, c), m(s.rounding, c), m(s.total, c), s.payments,
            )
        }
    }
}
