package com.lekaspos.data.sale

import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.report.Months
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne

/**
 * Incremental maintenance of the summary tables — per day, and per month and year of products
 * (DERIVED, references/database.md §7).
 * Called with sign = +1 when a sale/refund is stored and −1 when it is voided.
 */
object Summaries {

    class LineSum(val productId: Long?, val categoryId: Long?, val baseQty: Long, val netEx: Long, val tax: Long, val cost: Long)

    class PaySum(val methodId: Long, val kind: Int, val amount: Long)

    class Input(
        val day: Long,
        val kind: Int,
        val staffId: Long?,
        val subtotal: Long,
        val discount: Long,
        val tax: Long,
        val rounding: Long,
        val total: Long,
        val cost: Long,
        val lines: List<LineSum>,
        val payments: List<PaySum>,
    )

    private const val DAY_UPDATE =
        "UPDATE sum_day SET sale_count = sale_count + ?, refund_count = refund_count + ?, " +
            "void_count = void_count + ?, gross = gross + ?, discount = discount + ?, net_ex = net_ex + ?, " +
            "tax = tax + ?, rounding = rounding + ?, total = total + ?, cost = cost + ?, " +
            "refund_total = refund_total + ?, items = items + ? WHERE day = ?"
    private const val DAY_INSERT =
        "INSERT INTO sum_day(sale_count, refund_count, void_count, gross, discount, net_ex, tax, rounding, " +
            "total, cost, refund_total, items, day) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"

    private const val PRODUCT_UPDATE =
        "UPDATE sum_day_product SET qty = qty + ?, net_ex = net_ex + ?, tax = tax + ?, cost = cost + ? " +
            "WHERE day = ? AND product_id = ?"
    private const val PRODUCT_INSERT =
        "INSERT INTO sum_day_product(qty, net_ex, tax, cost, day, product_id, category_id) VALUES(?,?,?,?,?,?,?)"

    private const val MONTH_UPDATE =
        "UPDATE sum_month_product SET qty = qty + ?, net_ex = net_ex + ?, tax = tax + ?, cost = cost + ? " +
            "WHERE month = ? AND product_id = ?"
    private const val MONTH_INSERT =
        "INSERT INTO sum_month_product(qty, net_ex, tax, cost, month, product_id, category_id) VALUES(?,?,?,?,?,?,?)"

    private const val YEAR_UPDATE =
        "UPDATE sum_year_product SET qty = qty + ?, net_ex = net_ex + ?, tax = tax + ?, cost = cost + ? " +
            "WHERE year = ? AND product_id = ?"
    private const val YEAR_INSERT =
        "INSERT INTO sum_year_product(qty, net_ex, tax, cost, year, product_id, category_id) VALUES(?,?,?,?,?,?,?)"

    /**
     * Rebuilds the per-month table from the per-day one (D-043). An epoch day times 86,400 read as
     * UTC seconds is that local date, so strftime gives the same yyyymm as :core `Months.key`.
     */
    const val MONTHS_FROM_DAYS =
        "INSERT INTO sum_month_product(month, product_id, category_id, qty, net_ex, tax, cost) " +
            "SELECT CAST(strftime('%Y%m', day * 86400, 'unixepoch') AS INTEGER), product_id, MIN(category_id), " +
            "SUM(qty), SUM(net_ex), SUM(tax), SUM(cost) FROM sum_day_product GROUP BY 1, product_id"

    /** Rebuilds the per-year table from the per-month one (D-058): year = month / 100. */
    const val YEARS_FROM_MONTHS =
        "INSERT INTO sum_year_product(year, product_id, category_id, qty, net_ex, tax, cost) " +
            "SELECT month / 100, product_id, MIN(category_id), SUM(qty), SUM(net_ex), SUM(tax), SUM(cost) " +
            "FROM sum_month_product GROUP BY 1, product_id"

    /**
     * Rebuilds the category totals (D-058): a product's sales count under its current category; those
     * of a product whose row has not arrived yet (another till's, out of order), under the category
     * their summary row was filed under. 0 = no category.
     */
    val CATEGORIES_FROM_PRODUCTS: List<String> = listOf("day", "month", "year").map { p ->
        "INSERT INTO sum_${p}_category($p, category_id, qty, net_ex, cost) " +
            "SELECT s.$p, COALESCE(CASE WHEN p.id IS NULL THEN s.category_id ELSE p.category_id END, 0), " +
            "SUM(s.qty), SUM(s.net_ex), SUM(s.cost) FROM sum_${p}_product s LEFT JOIN product p ON p.id = s.product_id GROUP BY 1, 2"
    }

    private val CATEGORY_UPDATE = listOf("day", "month", "year").map { p ->
        "UPDATE sum_${p}_category SET qty = qty + ?, net_ex = net_ex + ?, cost = cost + ? WHERE $p = ? AND category_id = ?"
    }
    private val CATEGORY_INSERT = listOf("day", "month", "year").map { p ->
        "INSERT INTO sum_${p}_category(qty, net_ex, cost, $p, category_id) VALUES(?,?,?,?,?)"
    }

    /** A product's rows per day, month and year. */
    private val ROWS = listOf("day", "month", "year").map { p -> "SELECT $p, qty, net_ex, cost FROM sum_${p}_product WHERE product_id = ?" }

    /** A product's rows (per day, month, year) filed under another category than [to] (a product just arrived). */
    private val MOVED = listOf("day", "month", "year").map { p ->
        "SELECT $p, category_id, qty, net_ex, cost FROM sum_${p}_product WHERE product_id = ? AND category_id IS NOT ?"
    }
    private val MOVE = listOf("day", "month", "year").map { p ->
        "UPDATE sum_${p}_product SET category_id = ? WHERE product_id = ? AND category_id IS NOT ?"
    }
    private val ROW_CATEGORY = listOf("day", "month", "year").map { p ->
        "SELECT category_id FROM sum_${p}_product WHERE $p = ? AND product_id = ?"
    }
    private const val PRODUCT_CATEGORY = "SELECT category_id FROM product WHERE id = ?"

    /** Plan checks: the lookups made while a sale is stored and a product changes category. */
    val HOT_QUERIES: List<Pair<String, String>> =
        MOVED.mapIndexed { i, s -> "summary_moved_$i" to s } + ROWS.mapIndexed { i, s -> "summary_rows_$i" to s } +
            ROW_CATEGORY.mapIndexed { i, s -> "summary_row_category_$i" to s }

    private const val PAYMENT_UPDATE =
        "UPDATE sum_day_payment SET amount = amount + ?, count = count + ? WHERE day = ? AND method_id = ?"
    private const val PAYMENT_INSERT =
        "INSERT INTO sum_day_payment(amount, count, day, method_id, kind) VALUES(?,?,?,?,?)"

    private const val STAFF_UPDATE =
        "UPDATE sum_day_staff SET sale_count = sale_count + ?, total = total + ?, net_ex = net_ex + ? " +
            "WHERE day = ? AND staff_id = ?"
    private const val STAFF_INSERT =
        "INSERT INTO sum_day_staff(sale_count, total, net_ex, day, staff_id) VALUES(?,?,?,?,?)"

    fun apply(tx: Db.Tx, s: Input, sign: Int, voided: Boolean = false) {
        val k = sign.toLong()
        val isSale = s.kind == SaleKind.SALE
        val netEx = s.lines.sumOf { it.netEx }
        val day = arrayOf<Any?>(
            if (isSale) k else 0L,
            if (isSale) 0L else k,
            if (voided) 1L else 0L,
            s.subtotal * k,
            s.discount * k,
            netEx * k,
            s.tax * k,
            s.rounding * k,
            s.total * k,
            s.cost * k,
            if (isSale) 0L else -s.total * k,
            if (isSale) s.lines.size * k else 0L,
            s.day,
        )
        tx.updateOrInsert(DAY_UPDATE, day, DAY_INSERT, day)

        // One row per product and day (lines of the same product are merged first).
        val byProduct = LinkedHashMap<Long, LongArray>()
        val categories = HashMap<Long, Long?>()
        for (l in s.lines) {
            val pid = l.productId ?: 0L
            val acc = byProduct.getOrPut(pid) { LongArray(4) }
            acc[0] += l.baseQty
            acc[1] += l.netEx
            acc[2] += l.tax
            acc[3] += l.cost
            if (!categories.containsKey(pid)) categories[pid] = l.categoryId
        }
        val month = Months.key(s.day)
        val year = month / 100
        val keys = longArrayOf(s.day, month.toLong(), year.toLong())
        // Category totals of this sale per day, month and year: the category its product rows are under.
        val byCategory = Array(3) { LinkedHashMap<Long, LongArray>() }
        for ((pid, a) in byProduct) {
            // The product's current category (D-058); without a product row (another till's product
            // not synced yet), the category its rows already have, or this sale's.
            val current = productCategory(tx, pid)
            val cat = LongArray(3) { i ->
                if (current != null) current else rowCategory(tx, i, keys[i], pid) ?: (categories[pid] ?: 0L)
            }
            fun c(i: Int): Long? = cat[i].takeIf { it != 0L }
            tx.updateOrInsert(
                PRODUCT_UPDATE, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, s.day, pid),
                PRODUCT_INSERT, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, s.day, pid, c(0)),
            )
            tx.updateOrInsert(
                MONTH_UPDATE, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, month, pid),
                MONTH_INSERT, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, month, pid, c(1)),
            )
            tx.updateOrInsert(
                YEAR_UPDATE, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, year, pid),
                YEAR_INSERT, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, year, pid, c(2)),
            )
            for (i in 0..2) {
                val acc = byCategory[i].getOrPut(cat[i]) { LongArray(3) }
                acc[0] += a[0]
                acc[1] += a[1]
                acc[2] += a[3]
            }
        }
        for (i in 0..2) for ((cat, a) in byCategory[i]) addToCategory(tx, i, keys[i], cat, a[0] * k, a[1] * k, a[2] * k)

        val byMethod = LinkedHashMap<Long, LongArray>()
        val kinds = HashMap<Long, Int>()
        for (p in s.payments) {
            val acc = byMethod.getOrPut(p.methodId) { LongArray(1) }
            acc[0] += p.amount
            kinds[p.methodId] = p.kind
        }
        for ((mid, a) in byMethod) {
            tx.updateOrInsert(
                PAYMENT_UPDATE, arrayOf<Any?>(a[0] * k, k, s.day, mid),
                PAYMENT_INSERT, arrayOf<Any?>(a[0] * k, k, s.day, mid, kinds[mid]),
            )
        }

        val staff = s.staffId ?: 0L
        val staffArgs = arrayOf<Any?>(if (isSale) k else 0L, s.total * k, netEx * k, s.day, staff)
        tx.updateOrInsert(STAFF_UPDATE, staffArgs, STAFF_INSERT, staffArgs)
    }

    /**
     * [productId]'s category changed from [from] to [to] (null: none) in the transaction that calls
     * this — here, or as another till's change is applied: its sales move between the category
     * totals (D-058). [hadRow] false: the product row has just arrived (another till's product,
     * after its sales): its sales move from the categories their summary rows were filed under.
     */
    fun recategorize(tx: Db.Tx, productId: Long, from: Long?, to: Long?, hadRow: Boolean) {
        val target = to ?: 0L
        val source = from ?: 0L
        if (hadRow && source == target) return
        for (i in 0..2) {
            if (hadRow) {
                tx.db.queryList(ROWS[i], args(productId)) { c -> longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) }
                    .forEach { r ->
                        addToCategory(tx, i, r[0], source, -r[1], -r[2], -r[3])
                        addToCategory(tx, i, r[0], target, r[1], r[2], r[3])
                    }
            } else {
                val bind = args(productId, to)
                tx.db.queryList(MOVED[i], bind) { c ->
                    longArrayOf(c.getLong(0), if (c.isNull(1)) 0L else c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                }.forEach { r ->
                    addToCategory(tx, i, r[0], r[1], -r[2], -r[3], -r[4])
                    addToCategory(tx, i, r[0], target, r[2], r[3], r[4])
                }
                tx.db.execSQL(MOVE[i], arrayOf(to, productId, to))
            }
        }
    }

    /** 0 = no category; null = no product row. */
    private fun productCategory(tx: Db.Tx, productId: Long): Long? =
        tx.db.queryOne(PRODUCT_CATEGORY, args(productId)) { c -> if (c.isNull(0)) 0L else c.getLong(0) }

    /** The category of a product's existing row for day, month or year [i]; null when there is no row. */
    private fun rowCategory(tx: Db.Tx, i: Int, key: Long, productId: Long): Long? =
        tx.db.queryOne(ROW_CATEGORY[i], args(key, productId)) { c -> if (c.isNull(0)) 0L else c.getLong(0) }

    private fun addToCategory(tx: Db.Tx, i: Int, key: Long, category: Long, qty: Long, netEx: Long, cost: Long) {
        tx.updateOrInsert(
            CATEGORY_UPDATE[i], arrayOf<Any?>(qty, netEx, cost, key, category),
            CATEGORY_INSERT[i], arrayOf<Any?>(qty, netEx, cost, key, category),
        )
    }
}
