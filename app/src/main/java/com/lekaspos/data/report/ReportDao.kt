package com.lekaspos.data.report

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.data.db.args
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull

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

data class ProductTotal(val productId: Long, val name: String?, val qty: Long, val netEx: Long, val tax: Long, val cost: Long) {
    val grossProfit: Long get() = netEx - cost
}

data class StaffTotal(val staffId: Long, val name: String?, val saleCount: Long, val total: Long, val netEx: Long)

data class CategoryTotal(val categoryId: Long?, val name: String?, val qty: Long, val netEx: Long, val cost: Long)

/**
 * Reports read only the per-day summary tables, so they stay fast at millions of sale lines
 * (D-009). Ranges are local epoch days, [fromDay] inclusive, [toDay] exclusive.
 */
object ReportDao {

    private const val TOTALS =
        "SELECT COALESCE(SUM(sale_count),0), COALESCE(SUM(refund_count),0), COALESCE(SUM(void_count),0), " +
            "COALESCE(SUM(gross),0), COALESCE(SUM(discount),0), COALESCE(SUM(net_ex),0), COALESCE(SUM(tax),0), " +
            "COALESCE(SUM(rounding),0), COALESCE(SUM(total),0), COALESCE(SUM(cost),0), " +
            "COALESCE(SUM(refund_total),0), COALESCE(SUM(items),0) FROM sum_day WHERE day >= ? AND day < ?"

    private const val BY_PAYMENT =
        "SELECT d.method_id, m.name, d.kind, SUM(d.amount), SUM(d.count) FROM sum_day_payment d " +
            "LEFT JOIN payment_method m ON m.id = d.method_id WHERE d.day >= ? AND d.day < ? " +
            "GROUP BY d.method_id ORDER BY 4 DESC"

    private const val TOP_PRODUCTS =
        "SELECT t.product_id, p.name, t.qty, t.net_ex, t.tax, t.cost FROM " +
            "(SELECT product_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(tax) AS tax, SUM(cost) AS cost " +
            "FROM sum_day_product WHERE day >= ? AND day < ? GROUP BY product_id ORDER BY 4 DESC LIMIT ?) t " +
            "LEFT JOIN product p ON p.id = t.product_id ORDER BY t.net_ex DESC"

    private const val BY_STAFF =
        "SELECT d.staff_id, s.name, SUM(d.sale_count), SUM(d.total), SUM(d.net_ex) FROM sum_day_staff d " +
            "LEFT JOIN staff s ON s.id = d.staff_id WHERE d.day >= ? AND d.day < ? " +
            "GROUP BY d.staff_id ORDER BY 4 DESC"

    private const val BY_CATEGORY =
        "SELECT t.category_id, c.name, t.qty, t.net_ex, t.cost FROM " +
            "(SELECT category_id, SUM(qty) AS qty, SUM(net_ex) AS net_ex, SUM(cost) AS cost " +
            "FROM sum_day_product WHERE day >= ? AND day < ? GROUP BY category_id) t " +
            "LEFT JOIN category c ON c.id = t.category_id ORDER BY t.net_ex DESC"

    fun totals(db: SQLiteDatabase, fromDay: Long, toDay: Long): Totals =
        db.queryOne(TOTALS, args(fromDay, toDay)) { c ->
            Totals(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5),
                c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9), c.getLong(10), c.getLong(11),
            )
        } ?: Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    fun byPayment(db: SQLiteDatabase, fromDay: Long, toDay: Long): List<PaymentTotal> =
        db.queryList(BY_PAYMENT, args(fromDay, toDay)) { c ->
            PaymentTotal(c.getLong(0), c.stringOrNull(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }

    fun topProducts(db: SQLiteDatabase, fromDay: Long, toDay: Long, limit: Int = 10): List<ProductTotal> =
        db.queryList(TOP_PRODUCTS, args(fromDay, toDay, limit)) { c ->
            ProductTotal(c.getLong(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
        }

    fun byStaff(db: SQLiteDatabase, fromDay: Long, toDay: Long): List<StaffTotal> =
        db.queryList(BY_STAFF, args(fromDay, toDay)) { c ->
            StaffTotal(c.getLong(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4))
        }

    fun byCategory(db: SQLiteDatabase, fromDay: Long, toDay: Long): List<CategoryTotal> =
        db.queryList(BY_CATEGORY, args(fromDay, toDay)) { c ->
            CategoryTotal(c.longOrNull(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getLong(4))
        }

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "report_totals" to TOTALS,
        "report_payment" to BY_PAYMENT,
        "report_top_products" to TOP_PRODUCTS,
        "report_staff" to BY_STAFF,
    )
}
