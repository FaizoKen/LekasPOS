package com.lekaspos.data.sale

import com.lekaspos.core.model.SaleKind
import com.lekaspos.data.db.Db

/**
 * Incremental maintenance of the per-day summary tables (DERIVED, references/database.md §7).
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
        for ((pid, a) in byProduct) {
            tx.updateOrInsert(
                PRODUCT_UPDATE, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, s.day, pid),
                PRODUCT_INSERT, arrayOf<Any?>(a[0] * k, a[1] * k, a[2] * k, a[3] * k, s.day, pid, categories[pid]),
            )
        }

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
}
