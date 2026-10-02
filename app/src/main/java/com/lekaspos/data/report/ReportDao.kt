package com.lekaspos.data.report

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.report.DayTotals
import com.lekaspos.core.report.Period
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.args
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import java.util.PriorityQueue

data class Totals(
    val saleCount: Long,
    val refundCount: Long,
    val voidCount: Long,
    val gross: Long,
    val discount: Long,
    val netEx: Long,
    val tax: Long,
    val rounding: Long,
    val total: Long,
    val cost: Long,
    val refundTotal: Long,
    val items: Long,
) {
    val grossProfit: Long get() = netEx - cost
}

data class PaymentTotal(val methodId: Long, val name: String?, val kind: Int, val amount: Long, val count: Long)

/** [productId] 0 = items sold without a product (unknown barcodes, "other item"). */
data class ProductTotal(val productId: Long, val name: String?, val qty: Long, val netEx: Long, val tax: Long, val cost: Long) {
    val grossProfit: Long get() = netEx - cost
}

data class StaffTotal(val staffId: Long, val name: String?, val saleCount: Long, val total: Long, val netEx: Long)

data class CategoryTotal(val categoryId: Long?, val name: String?, val qty: Long, val netEx: Long, val cost: Long) {
    val grossProfit: Long get() = netEx - cost
}

/** A product with stock on hand that did not sell in the period; [stockValueMilli] = qty × cost (÷ 1000 for money). */
data class SlowMover(val productId: Long, val name: String, val qty: Long, val cost: Long) {
    val stockValueMilli: Long get() = qty * cost
}

/** Stock on hand at cost for one category; [valueMilli] ÷ 1000 = money. */
data class StockValue(val categoryId: Long?, val name: String?, val products: Long, val valueMilli: Long)

/** One receipt for exports (the monthly consolidated e-invoice uses the same list). */
data class ReceiptRow(
    val id: Long,
    val kind: Int,
    val receiptNo: String,
    val soldAt: Long,
    val status: Int,
    val lines: Int,
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val rounding: Long,
    val total: Long,
    val pricesInclTax: Boolean,
    val staff: String?,
    val customer: String?,
    val customerTin: String?,
    val payments: String?,
    /** Business day (local epoch day stored at sale time). */
    val day: Long = 0L,
) {
    /** Sales ex. tax: lines net minus the tax when prices include it. */
    val netEx: Long get() = subtotal - discount - if (pricesInclTax) tax else 0L
}

/**
 * Reports read only the summary tables, so they stay fast at millions of sale lines (D-009).
 * Day ranges are local epoch days, from inclusive, to exclusive; product and category totals
 * read whole years, months and loose days ([SummaryRange], D-043, D-058).
 */
object ReportDao {

    private const val TOTALS =
        "SELECT COALESCE(SUM(sale_count),0), COALESCE(SUM(refund_count),0), COALESCE(SUM(void_count),0), " +
            "COALESCE(SUM(gross),0), COALESCE(SUM(discount),0), COALESCE(SUM(net_ex),0), COALESCE(SUM(tax),0), " +
            "COALESCE(SUM(rounding),0), COALESCE(SUM(total),0), COALESCE(SUM(cost),0), " +
            "COALESCE(SUM(refund_total),0), COALESCE(SUM(items),0) FROM sum_day WHERE day >= ? AND day < ?"

    private const val DAYS =
        "SELECT day, sale_count, refund_count, total, net_ex, tax, cost, discount FROM sum_day WHERE day >= ? AND day < ? ORDER BY day"

    // A void subtracts its sale from the summary rows and leaves zeros behind (a rebuild has no
    // such rows), so groups that add up to nothing are left out of every list below.
    private const val BY_PAYMENT =
        "SELECT d.method_id, m.name, d.kind, SUM(d.amount), SUM(d.count) FROM sum_day_payment d " +
            "LEFT JOIN payment_method m ON m.id = d.method_id WHERE d.day >= ? AND d.day < ? " +
            "GROUP BY d.method_id HAVING SUM(d.count) != 0 OR SUM(d.amount) != 0 ORDER BY 4 DESC"

    private const val BY_STAFF =
        "SELECT d.staff_id, s.name, SUM(d.sale_count), SUM(d.total), SUM(d.net_ex) FROM sum_day_staff d " +
            "LEFT JOIN staff s ON s.id = d.staff_id WHERE d.day >= ? AND d.day < ? " +
            "GROUP BY d.staff_id HAVING SUM(d.sale_count) != 0 OR SUM(d.total) != 0 OR SUM(d.net_ex) != 0 " +
            "ORDER BY 4 DESC"

    /** After `GROUP BY product_id` over a period's rows: products whose totals are not all zero. */
    private const val NOT_ZERO = "HAVING SUM(qty) != 0 OR SUM(net_ex) != 0 OR SUM(tax) != 0 OR SUM(cost) != 0"

    // One reading of a period's product rows (SummaryRange, D-058) gives both the category totals
    // and the best sellers: each product's totals with its current category. A category follows
    // the product's current one, so a range read from days, months or years puts a product's sales
    // under the same category; the category kept in a summary row (the first sale's of that day,
    // month or year) is used only when there is no product row ("other items").
    private const val PER_PRODUCT_HEAD =
        "SELECT u.product_id, CASE WHEN p.id IS NULL THEN u.category_id ELSE p.category_id END, " +
            "u.qty, u.net_ex, u.tax, u.cost FROM " +
            "(SELECT product_id, MIN(category_id) AS category_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, " +
            "SUM(tax) AS tax, SUM(cost) AS cost FROM ("
    private const val PER_PRODUCT_TAIL = ") GROUP BY product_id $NOT_ZERO) u LEFT JOIN product p ON p.id = u.product_id"

    /** Every product sold in a period, best first, with its name (exports). */
    private const val EXPORT_HEAD =
        "SELECT t.product_id, p.name, t.qty, t.net_ex, t.tax, t.cost FROM " +
            "(SELECT product_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(tax) AS tax, SUM(cost) AS cost FROM ("
    private const val EXPORT_TAIL =
        ") GROUP BY product_id $NOT_ZERO) t LEFT JOIN product p ON p.id = t.product_id ORDER BY t.net_ex DESC, t.product_id"

    /**
     * Products with stock that did not sell in a period, most stock value first: "sold" is more
     * sold than returned, voids excluded. Never a NULL in the list (old "other item" rows): `NOT IN`
     * a list holding NULL is never true. Read once; the count and value of all of them are summed
     * while the first ones are kept (two queries read the period twice).
     */
    private const val SLOW_HEAD =
        "SELECT p.id, l.qty, p.cost FROM stock_level l CROSS JOIN product p ON p.id = l.product_id " +
            "WHERE l.qty > 0 AND p.deleted = 0 AND p.track_stock = 1 AND l.product_id NOT IN " +
            "(SELECT product_id FROM ("
    private const val SLOW_TAIL =
        ") WHERE product_id IS NOT NULL GROUP BY product_id HAVING SUM(qty) > 0) ORDER BY l.qty * p.cost DESC, p.id"

    private const val CATEGORY_NAMES = "SELECT id, name FROM category"
    private const val NAMES_PREFIX = "SELECT id, name FROM product WHERE id IN "
    private const val MAX_IN = 500

    private const val STOCK_VALUE =
        "SELECT p.category_id, c.name, COUNT(*), COALESCE(SUM(l.qty * p.cost), 0) " +
            "FROM stock_level l CROSS JOIN product p ON p.id = l.product_id LEFT JOIN category c ON c.id = p.category_id " +
            "WHERE l.qty > 0 AND p.deleted = 0 AND p.track_stock = 1 GROUP BY p.category_id ORDER BY 4 DESC"

    private const val RECEIPT_COLS =
        "SELECT s.id, s.kind, s.receipt_no, s.sold_at, s.status, s.line_count, s.subtotal, s.discount, s.tax, s.rounding, " +
            "s.total, s.prices_incl_tax, st.name, c.name, c.tin, " +
            "(SELECT GROUP_CONCAT(COALESCE(m.name, p.kind), ' + ') FROM payment p " +
            "LEFT JOIN payment_method m ON m.id = p.method_id WHERE p.sale_id = s.id), s.day " +
            "FROM sale s LEFT JOIN staff st ON st.id = s.staff_id LEFT JOIN customer c ON c.id = s.customer_id "

    // The index range is on sold_at; the stored business day (what every report sums) filters it,
    // so the receipt list of a period matches its report (2026-10 review).
    private const val RECEIPTS_FIRST = RECEIPT_COLS +
        "WHERE s.sold_at >= ? AND s.sold_at < ? AND s.day >= ? AND s.day < ? ORDER BY s.sold_at, s.id LIMIT ?"
    private const val RECEIPTS_NEXT = RECEIPT_COLS +
        "WHERE s.sold_at >= ? AND (s.sold_at > ? OR s.id > ?) AND s.sold_at < ? AND s.day >= ? AND s.day < ? " +
        "ORDER BY s.sold_at, s.id LIMIT ?"

    fun totals(db: SQLiteDatabase, fromDay: Long, toDay: Long): Totals =
        db.queryOne(TOTALS, args(fromDay, toDay)) { c ->
            Totals(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5),
                c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9), c.getLong(10), c.getLong(11),
            )
        } ?: Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** Days that had sales, in order (the UI groups them into days, weeks or months). */
    fun days(db: SQLiteDatabase, fromDay: Long, toDay: Long): List<DayTotals> =
        db.queryList(DAYS, args(fromDay, toDay)) { c ->
            DayTotals(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getLong(7))
        }

    fun byPayment(db: SQLiteDatabase, fromDay: Long, toDay: Long): List<PaymentTotal> =
        db.queryList(BY_PAYMENT, args(fromDay, toDay)) { c ->
            PaymentTotal(c.getLong(0), c.stringOrNull(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }

    fun byStaff(db: SQLiteDatabase, fromDay: Long, toDay: Long): List<StaffTotal> =
        db.queryList(BY_STAFF, args(fromDay, toDay)) { c ->
            StaffTotal(c.getLong(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4))
        }

    /** Categories and best sellers of one period, from one reading of its product rows. */
    class ProductSummary(val categories: List<CategoryTotal>, val top: List<ProductTotal>)

    /**
     * The categories of [p] (most net sales first) and its [topN] best sellers by net sales, or by
     * quantity ([byQty]); [topN] = Int.MAX_VALUE for every product sold, 0 for none.
     */
    fun summary(db: SQLiteDatabase, p: Period, topN: Int = 20, byQty: Boolean = false): ProductSummary {
        val rows = SummaryRange.all(SummaryRange.plan(db, p))
        val cats = HashMap<Long, LongArray>() // category (or NO_CATEGORY) → qty, net_ex, cost
        // Best first: the larger measure, then the smaller id (the old SQL's ORDER BY … DESC, product_id).
        val better = Comparator<LongArray> { a, b ->
            val m = if (byQty) 1 else 2
            if (a[m] != b[m]) b[m].compareTo(a[m]) else a[0].compareTo(b[0])
        }
        // The worst kept one first (Comparator.reversed is API 24).
        val top = PriorityQueue(minOf(topN, 256) + 1, Comparator<LongArray> { a, b -> better.compare(b, a) })
        db.rawQuery(PER_PRODUCT_HEAD + rows.sql + PER_PRODUCT_TAIL, rows.args).use { c ->
            while (c.moveToNext()) {
                val t = longArrayOf(c.getLong(0), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
                val acc = cats.getOrPut(if (c.isNull(1)) NO_CATEGORY else c.getLong(1)) { LongArray(3) }
                acc[0] += t[1]
                acc[1] += t[2]
                acc[2] += t[4]
                if (topN > 0) {
                    top.add(t)
                    if (top.size > topN) top.poll()
                }
            }
        }
        val names = names(db, top.map { it[0] })
        val products = top.sortedWith(better).map { t -> ProductTotal(t[0], names[t[0]], t[1], t[2], t[3], t[4]) }
        val catNames = if (cats.isEmpty()) emptyMap() else categoryNames(db)
        val categories = cats.entries
            .sortedWith(compareByDescending<Map.Entry<Long, LongArray>> { it.value[1] }.thenBy { it.key })
            .map { (k, a) ->
                val id = if (k == NO_CATEGORY) null else k
                CategoryTotal(id, id?.let { catNames[it] }, a[0], a[1], a[2])
            }
        return ProductSummary(categories, products)
    }

    /** Best sellers by net sales (or by quantity); [limit] = Int.MAX_VALUE for every product sold. */
    fun products(db: SQLiteDatabase, p: Period, byQty: Boolean = false, limit: Int = 20): List<ProductTotal> =
        summary(db, p, limit, byQty).top

    fun byCategory(db: SQLiteDatabase, p: Period): List<CategoryTotal> = summary(db, p, 0).categories

    /** Every product sold in [p], best first, streamed (exports). */
    fun eachProduct(db: SQLiteDatabase, p: Period, each: (ProductTotal) -> Unit) {
        val rows = SummaryRange.all(SummaryRange.plan(db, p))
        db.rawQuery(EXPORT_HEAD + rows.sql + EXPORT_TAIL, rows.args).use { c -> while (c.moveToNext()) each(productTotal(c)) }
    }

    /** Products with stock that did not sell in [p]: the first [limit] by stock value, and the count and value of all. */
    class Slow(val items: List<SlowMover>, val count: Long, val valueMilli: Long)

    fun slowMovers(db: SQLiteDatabase, p: Period, limit: Int = 100): Slow {
        val rows = SummaryRange.qty(SummaryRange.plan(db, p))
        val kept = ArrayList<LongArray>()
        var count = 0L
        var value = 0L
        db.rawQuery(SLOW_HEAD + rows.sql + SLOW_TAIL, rows.args).use { c ->
            while (c.moveToNext()) {
                val qty = c.getLong(1)
                val cost = c.getLong(2)
                count++
                value += qty * cost
                if (kept.size < limit) kept.add(longArrayOf(c.getLong(0), qty, cost))
            }
        }
        val names = names(db, kept.map { it[0] })
        return Slow(kept.map { SlowMover(it[0], names[it[0]].orEmpty(), it[1], it[2]) }, count, value)
    }

    /** Names of products by id (deleted ones too: a report names what was sold). */
    private fun names(db: SQLiteDatabase, ids: List<Long>): Map<Long, String> {
        val out = HashMap<Long, String>(ids.size * 2)
        for (chunk in ids.chunked(MAX_IN)) {
            val sql = NAMES_PREFIX + chunk.joinToString(",", "(", ")") { "?" }
            db.rawQuery(sql, args(*chunk.toTypedArray())).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getString(1) }
        }
        return out
    }

    private fun categoryNames(db: SQLiteDatabase): Map<Long, String?> =
        db.queryList(CATEGORY_NAMES) { c -> c.getLong(0) to c.stringOrNull(1) }.toMap()

    private const val NO_CATEGORY = Long.MIN_VALUE

    fun stockValue(db: SQLiteDatabase): List<StockValue> = db.queryList(STOCK_VALUE, null) { c ->
        StockValue(c.longOrNull(0), c.stringOrNull(1), c.getLong(2), c.getLong(3))
    }

    /**
     * Receipts (sales and refunds, voided ones included) sold in [fromMs, toMs), in time order;
     * only those of business days [fromDay, toDay) when given.
     */
    fun receipts(
        db: SQLiteDatabase,
        fromMs: Long,
        toMs: Long,
        after: ReceiptRow?,
        limit: Int = 500,
        fromDay: Long = Long.MIN_VALUE,
        toDay: Long = Long.MAX_VALUE,
    ): List<ReceiptRow> =
        if (after == null) {
            db.queryList(RECEIPTS_FIRST, args(fromMs, toMs, fromDay, toDay, limit), ::receipt)
        } else {
            val a = args(after.soldAt, after.soldAt, after.id, toMs, fromDay, toDay, limit)
            db.queryList(RECEIPTS_NEXT, a, ::receipt)
        }

    /**
     * Receipts of business days [fromDay, toDay) — the stored `day` the reports sum, whatever time
     * zone the till had. A sale's day is its local date (UTC−12 … UTC+14), so its sold_at lies
     * within a day of that date's UTC midnight: that window is the index range.
     */
    fun receiptsOfDays(db: SQLiteDatabase, fromDay: Long, toDay: Long, after: ReceiptRow?, limit: Int = 500) =
        receipts(db, (fromDay - 1L) * Days.DAY_MS, (toDay + 1L) * Days.DAY_MS, after, limit, fromDay, toDay)

    private fun productTotal(c: Cursor) =
        ProductTotal(c.getLong(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))

    private fun receipt(c: Cursor) = ReceiptRow(
        id = c.getLong(0), kind = c.getInt(1), receiptNo = c.getString(2), soldAt = c.getLong(3), status = c.getInt(4),
        lines = c.getInt(5), subtotal = c.getLong(6), discount = c.getLong(7), tax = c.getLong(8), rounding = c.getLong(9),
        total = c.getLong(10), pricesInclTax = c.getLong(11) != 0L, staff = c.stringOrNull(12), customer = c.stringOrNull(13),
        customerTin = c.stringOrNull(14), payments = c.stringOrNull(15), day = c.getLong(16),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "report_totals" to TOTALS,
        "report_days" to DAYS,
        "report_payment" to BY_PAYMENT,
        "report_staff" to BY_STAFF,
        // Built per period from SummaryRange's fragments: checked with every kind of piece.
        "report_has_sales" to SummaryRange.HAS_SALES,
        "report_products" to PER_PRODUCT_HEAD + SummaryRange.all(SummaryRange.SAMPLE).sql + PER_PRODUCT_TAIL,
        "report_export_products" to EXPORT_HEAD + SummaryRange.all(SummaryRange.SAMPLE).sql + EXPORT_TAIL,
        "report_slow_movers" to SLOW_HEAD + SummaryRange.qty(SummaryRange.SAMPLE).sql + SLOW_TAIL,
        "report_product_names" to NAMES_PREFIX + "(?,?,?)",
        "report_stock_value" to STOCK_VALUE,
        "receipts_first" to RECEIPTS_FIRST,
        "receipts_next" to RECEIPTS_NEXT,
    )
}
