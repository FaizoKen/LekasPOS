package com.lekaspos.data.report

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.report.DayTotals
import com.lekaspos.core.report.MonthSplit
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
) {
    /** Sales ex. tax: lines net minus the tax when prices include it. */
    val netEx: Long get() = subtotal - discount - if (pricesInclTax) tax else 0L
}

/**
 * Reports read only the summary tables, so they stay fast at millions of sale lines (D-009).
 * Day ranges are local epoch days, from inclusive, to exclusive; product and category totals
 * combine whole months from `sum_month_product` with the loose days at both ends (D-043).
 */
object ReportDao {

    private const val TOTALS =
        "SELECT COALESCE(SUM(sale_count),0), COALESCE(SUM(refund_count),0), COALESCE(SUM(void_count),0), " +
            "COALESCE(SUM(gross),0), COALESCE(SUM(discount),0), COALESCE(SUM(net_ex),0), COALESCE(SUM(tax),0), " +
            "COALESCE(SUM(rounding),0), COALESCE(SUM(total),0), COALESCE(SUM(cost),0), " +
            "COALESCE(SUM(refund_total),0), COALESCE(SUM(items),0) FROM sum_day WHERE day >= ? AND day < ?"

    private const val DAYS =
        "SELECT day, sale_count, refund_count, total, net_ex, tax, cost, discount FROM sum_day WHERE day >= ? AND day < ? ORDER BY day"

    private const val BY_PAYMENT =
        "SELECT d.method_id, m.name, d.kind, SUM(d.amount), SUM(d.count) FROM sum_day_payment d " +
            "LEFT JOIN payment_method m ON m.id = d.method_id WHERE d.day >= ? AND d.day < ? " +
            "GROUP BY d.method_id ORDER BY 4 DESC"

    private const val BY_STAFF =
        "SELECT d.staff_id, s.name, SUM(d.sale_count), SUM(d.total), SUM(d.net_ex) FROM sum_day_staff d " +
            "LEFT JOIN staff s ON s.id = d.staff_id WHERE d.day >= ? AND d.day < ? " +
            "GROUP BY d.staff_id ORDER BY 4 DESC"

    /** Product rows of a split range: head days, whole months, tail days (6 bind args). */
    private const val UNION =
        "SELECT product_id, category_id, qty, net_ex, tax, cost FROM sum_day_product WHERE day >= ? AND day < ? " +
            "UNION ALL SELECT product_id, category_id, qty, net_ex, tax, cost FROM sum_month_product WHERE month >= ? AND month < ? " +
            "UNION ALL SELECT product_id, category_id, qty, net_ex, tax, cost FROM sum_day_product WHERE day >= ? AND day < ?"

    private const val PRODUCTS_BY_NET =
        "SELECT t.product_id, p.name, t.qty, t.net_ex, t.tax, t.cost FROM " +
            "(SELECT product_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(tax) AS tax, SUM(cost) AS cost " +
            "FROM ($UNION) GROUP BY product_id ORDER BY 4 DESC, 1 LIMIT ?) t " +
            "LEFT JOIN product p ON p.id = t.product_id ORDER BY t.net_ex DESC, t.product_id"

    private const val PRODUCTS_BY_QTY =
        "SELECT t.product_id, p.name, t.qty, t.net_ex, t.tax, t.cost FROM " +
            "(SELECT product_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(tax) AS tax, SUM(cost) AS cost " +
            "FROM ($UNION) GROUP BY product_id ORDER BY 2 DESC, 1 LIMIT ?) t " +
            "LEFT JOIN product p ON p.id = t.product_id ORDER BY t.qty DESC, t.product_id"

    private const val BY_CATEGORY =
        "SELECT t.category_id, c.name, t.qty, t.net_ex, t.cost FROM " +
            "(SELECT category_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(cost) AS cost " +
            "FROM ($UNION) GROUP BY category_id) t " +
            "LEFT JOIN category c ON c.id = t.category_id ORDER BY t.net_ex DESC"

    /** One pass for the report screen: per product and category, in product order. */
    private const val BY_PRODUCT_CATEGORY =
        "SELECT product_id, category_id, SUM(qty), SUM(net_ex), SUM(tax), SUM(cost) FROM ($UNION) " +
            "GROUP BY product_id, category_id ORDER BY product_id, category_id"

    /** Products sold in the range (ids only), for "not sold" lists. */
    private const val SOLD_IDS =
        "SELECT product_id FROM sum_day_product WHERE day >= ? AND day < ? " +
            "UNION SELECT product_id FROM sum_month_product WHERE month >= ? AND month < ? " +
            "UNION SELECT product_id FROM sum_day_product WHERE day >= ? AND day < ?"

    private const val SLOW_FROM =
        "FROM stock_level l CROSS JOIN product p ON p.id = l.product_id " +
            "WHERE l.qty > 0 AND p.deleted = 0 AND p.track_stock = 1 AND l.product_id NOT IN ($SOLD_IDS)"
    private const val SLOW_MOVERS = "SELECT p.id, p.name, l.qty, p.cost $SLOW_FROM ORDER BY l.qty * p.cost DESC, p.id LIMIT ?"
    private const val SLOW_TOTAL = "SELECT COUNT(*), COALESCE(SUM(l.qty * p.cost), 0) $SLOW_FROM"

    private const val STOCK_VALUE =
        "SELECT p.category_id, c.name, COUNT(*), COALESCE(SUM(l.qty * p.cost), 0) " +
            "FROM stock_level l CROSS JOIN product p ON p.id = l.product_id LEFT JOIN category c ON c.id = p.category_id " +
            "WHERE l.qty > 0 AND p.deleted = 0 AND p.track_stock = 1 GROUP BY p.category_id ORDER BY 4 DESC"

    private const val RECEIPT_COLS =
        "SELECT s.id, s.kind, s.receipt_no, s.sold_at, s.status, s.line_count, s.subtotal, s.discount, s.tax, s.rounding, " +
            "s.total, s.prices_incl_tax, st.name, c.name, c.tin, " +
            "(SELECT GROUP_CONCAT(COALESCE(m.name, p.kind), ' + ') FROM payment p " +
            "LEFT JOIN payment_method m ON m.id = p.method_id WHERE p.sale_id = s.id) " +
            "FROM sale s LEFT JOIN staff st ON st.id = s.staff_id LEFT JOIN customer c ON c.id = s.customer_id "
    private const val RECEIPTS_FIRST = RECEIPT_COLS + "WHERE s.sold_at >= ? AND s.sold_at < ? ORDER BY s.sold_at, s.id LIMIT ?"
    private const val RECEIPTS_NEXT = RECEIPT_COLS +
        "WHERE s.sold_at >= ? AND (s.sold_at > ? OR s.id > ?) AND s.sold_at < ? ORDER BY s.sold_at, s.id LIMIT ?"

    private fun split(s: MonthSplit): Array<Any> = arrayOf(s.headFrom, s.headTo, s.fromMonth, s.toMonth, s.tailFrom, s.tailTo)

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

    /** Best sellers by net sales (or by quantity); [limit] = Int.MAX_VALUE for every product sold. */
    fun products(db: SQLiteDatabase, s: MonthSplit, byQty: Boolean = false, limit: Int = 20): List<ProductTotal> =
        db.queryList(if (byQty) PRODUCTS_BY_QTY else PRODUCTS_BY_NET, args(*split(s), limit), ::productTotal)

    /** Every product sold in the range, streamed (exports). */
    fun eachProduct(db: SQLiteDatabase, s: MonthSplit, each: (ProductTotal) -> Unit) {
        db.rawQuery(PRODUCTS_BY_NET, args(*split(s), -1)).use { c -> while (c.moveToNext()) each(productTotal(c)) }
    }

    fun byCategory(db: SQLiteDatabase, s: MonthSplit): List<CategoryTotal> =
        db.queryList(BY_CATEGORY, args(*split(s))) { c ->
            CategoryTotal(c.longOrNull(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4))
        }

    /**
     * Category totals and the [topN] best sellers by net sales from ONE pass over the product
     * rows of the range (the report screen needs both; two GROUP BYs cost twice as much).
     * Same answers as [products] and [byCategory].
     */
    fun productsAndCategories(db: SQLiteDatabase, s: MonthSplit, topN: Int): Pair<List<ProductTotal>, List<CategoryTotal>> {
        val cats = LinkedHashMap<Long?, LongArray>()
        val best = PriorityQueue(topN + 1, compareBy<ProductTotal>({ it.netEx }, { -it.productId }))
        var pid = Long.MIN_VALUE
        val acc = LongArray(4)
        fun flush() {
            if (pid == Long.MIN_VALUE) return
            best.add(ProductTotal(pid, null, acc[0], acc[1], acc[2], acc[3]))
            if (best.size > topN) best.poll()
        }
        db.rawQuery(BY_PRODUCT_CATEGORY, args(*split(s))).use { c ->
            while (c.moveToNext()) {
                val p = c.getLong(0)
                val cat = c.longOrNull(1)
                val qty = c.getLong(2)
                val net = c.getLong(3)
                val tax = c.getLong(4)
                val cost = c.getLong(5)
                if (p != pid) {
                    flush()
                    pid = p
                    acc.fill(0L)
                }
                acc[0] += qty
                acc[1] += net
                acc[2] += tax
                acc[3] += cost
                val k = cats.getOrPut(cat) { LongArray(3) }
                k[0] += qty
                k[1] += net
                k[2] += cost
            }
        }
        flush()
        val top = best.sortedWith(compareBy<ProductTotal>({ -it.netEx }, { it.productId }))
        val productNames = names(db, "product", top.map { it.productId }.filter { it != 0L })
        val categoryNames = names(db, "category", cats.keys.filterNotNull())
        val products = top.map { it.copy(name = productNames[it.productId]) }
        val categories = cats.map { (id, v) -> CategoryTotal(id, id?.let { categoryNames[it] }, v[0], v[1], v[2]) }
            .sortedByDescending { it.netEx }
        return products to categories
    }

    /** Names of a few rows by primary key (at most [MAX_IN] per query). */
    private fun names(db: SQLiteDatabase, table: String, ids: List<Long>): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (chunk in ids.distinct().chunked(MAX_IN)) {
            val marks = chunk.joinToString(",") { "?" }
            db.queryList("SELECT id, name FROM $table WHERE id IN ($marks)", args(*chunk.toTypedArray())) { it.getLong(0) to it.getString(1) }
                .forEach { out[it.first] = it.second }
        }
        return out
    }

    private const val MAX_IN = 200

    /** Products with stock that did not sell in the range, most stock value first. */
    fun slowMovers(db: SQLiteDatabase, s: MonthSplit, limit: Int = 100): List<SlowMover> =
        db.queryList(SLOW_MOVERS, args(*split(s), limit)) { c -> SlowMover(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3)) }

    /** How many products have stock but did not sell, and their stock value (× 1000). */
    fun slowTotal(db: SQLiteDatabase, s: MonthSplit): Pair<Long, Long> =
        db.queryOne(SLOW_TOTAL, args(*split(s))) { it.getLong(0) to it.getLong(1) } ?: (0L to 0L)

    fun stockValue(db: SQLiteDatabase): List<StockValue> = db.queryList(STOCK_VALUE, null) { c ->
        StockValue(c.longOrNull(0), c.stringOrNull(1), c.getLong(2), c.getLong(3))
    }

    /** Receipts (sales and refunds, voided ones included) sold in [fromMs, toMs), in time order. */
    fun receipts(db: SQLiteDatabase, fromMs: Long, toMs: Long, after: ReceiptRow?, limit: Int = 500): List<ReceiptRow> =
        if (after == null) {
            db.queryList(RECEIPTS_FIRST, args(fromMs, toMs, limit), ::receipt)
        } else {
            db.queryList(RECEIPTS_NEXT, args(after.soldAt, after.soldAt, after.id, toMs, limit), ::receipt)
        }

    private fun productTotal(c: Cursor) =
        ProductTotal(c.getLong(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))

    private fun receipt(c: Cursor) = ReceiptRow(
        id = c.getLong(0), kind = c.getInt(1), receiptNo = c.getString(2), soldAt = c.getLong(3), status = c.getInt(4),
        lines = c.getInt(5), subtotal = c.getLong(6), discount = c.getLong(7), tax = c.getLong(8), rounding = c.getLong(9),
        total = c.getLong(10), pricesInclTax = c.getLong(11) != 0L, staff = c.stringOrNull(12), customer = c.stringOrNull(13),
        customerTin = c.stringOrNull(14), payments = c.stringOrNull(15),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "report_totals" to TOTALS,
        "report_days" to DAYS,
        "report_payment" to BY_PAYMENT,
        "report_staff" to BY_STAFF,
        "report_products" to PRODUCTS_BY_NET,
        "report_products_qty" to PRODUCTS_BY_QTY,
        "report_categories" to BY_CATEGORY,
        "report_product_category" to BY_PRODUCT_CATEGORY,
        "report_slow_movers" to SLOW_MOVERS,
        "report_stock_value" to STOCK_VALUE,
        "receipts_first" to RECEIPTS_FIRST,
        "receipts_next" to RECEIPTS_NEXT,
    )
}
