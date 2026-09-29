package com.lekaspos.data.db

import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.stock.StockDao

/**
 * Recomputes DERIVED tables from EVENT rows with set-based SQL (references/database.md §7).
 * Must produce exactly what the incremental paths (SaleDao/Summaries/StockDao) maintain —
 * DerivedConsistencyTest checks that. Used after bulk loads, restores and for repair.
 */
object DerivedRebuild {

    private const val OK = SaleStatus.COMPLETED
    private const val VOIDED = SaleStatus.VOIDED
    private const val SALE = SaleKind.SALE
    private const val REFUND = SaleKind.REFUND

    // Sales ex. tax: Σ line net = subtotal − discount; minus the tax when prices include it
    // (line taxes add up to the sale tax exactly, so no line join is needed).
    private const val NET_EX = "(s.subtotal - s.discount - CASE WHEN s.prices_incl_tax = 1 THEN s.tax ELSE 0 END)"

    fun summaries(tx: Db.Tx) {
        tx.exec("DELETE FROM sum_day")
        tx.exec(
            "INSERT INTO sum_day(day, sale_count, refund_count, void_count, gross, discount, net_ex, tax, rounding, " +
                "total, cost, refund_total, items) SELECT s.day, " +
                "SUM(CASE WHEN s.status = $OK AND s.kind = $SALE THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK AND s.kind = $REFUND THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $VOIDED THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN s.subtotal ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN s.discount ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN $NET_EX ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN s.tax ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN s.rounding ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN s.total ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK THEN s.cost ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK AND s.kind = $REFUND THEN -s.total ELSE 0 END), " +
                "SUM(CASE WHEN s.status = $OK AND s.kind = $SALE THEN s.line_count ELSE 0 END) " +
                "FROM sale s GROUP BY s.day",
        )
        tx.exec("DELETE FROM sum_day_product")
        tx.exec(
            "INSERT INTO sum_day_product(day, product_id, category_id, qty, net_ex, tax, cost) " +
                "SELECT s.day, COALESCE(l.product_id, 0), MIN(l.category_id), SUM(l.base_qty), " +
                "SUM(l.net - CASE WHEN s.prices_incl_tax = 1 THEN l.tax ELSE 0 END), SUM(l.tax), SUM(l.cost) " +
                "FROM sale_line l JOIN sale s ON s.id = l.sale_id WHERE s.status = $OK " +
                "GROUP BY s.day, COALESCE(l.product_id, 0)",
        )
        tx.exec("DELETE FROM sum_day_payment")
        tx.exec(
            "INSERT INTO sum_day_payment(day, method_id, kind, amount, count) " +
                "SELECT s.day, p.method_id, MIN(p.kind), SUM(p.amount), COUNT(DISTINCT p.sale_id) " +
                "FROM payment p JOIN sale s ON s.id = p.sale_id WHERE s.status = $OK GROUP BY s.day, p.method_id",
        )
        tx.exec("DELETE FROM sum_day_staff")
        tx.exec(
            "INSERT INTO sum_day_staff(day, staff_id, sale_count, total, net_ex) " +
                "SELECT s.day, COALESCE(s.staff_id, 0), SUM(CASE WHEN s.kind = $SALE THEN 1 ELSE 0 END), " +
                "SUM(s.total), SUM($NET_EX) FROM sale s WHERE s.status = $OK GROUP BY s.day, COALESCE(s.staff_id, 0)",
        )
    }

    // Aggregate over the partial index sale_ref, then one PK update per refunded sale. A
    // correlated subquery here would scan `sale` once per sale on SQLite 3.8 (O(n²)).
    const val REFUND_TOTALS =
        "SELECT ref_sale_id, -SUM(total) FROM sale WHERE ref_sale_id IS NOT NULL " +
            "AND kind = $REFUND AND status = $OK GROUP BY ref_sale_id"

    fun refundedAmounts(tx: Db.Tx) {
        tx.exec("UPDATE sale SET refunded = 0 WHERE refunded != 0")
        val totals = tx.db.queryList(REFUND_TOTALS, null) { it.getLong(0) to it.getLong(1) }
        for ((saleId, amount) in totals) tx.update("UPDATE sale SET refunded = ? WHERE id = ?", amount, saleId)
    }

    /** Maintenance statements whose plans the perf suite checks (full scans allowed, correlated scans not). */
    val MAINTENANCE_QUERIES: List<Pair<String, String>> = listOf(
        "rebuild_refunds" to REFUND_TOTALS,
        "rebuild_balances" to CustomerDao.BALANCES,
    )

    fun stockLevels(tx: Db.Tx) {
        tx.exec("DELETE FROM stock_level")
        // Products never counted: everything since the beginning.
        tx.exec(
            "INSERT INTO stock_level(product_id, qty, count_hlc, count_dev) SELECT product_id, SUM(q), 0, 0 FROM (" +
                "SELECT product_id, qty AS q FROM stock_movement " +
                "UNION ALL SELECT l.product_id, l.stock_qty FROM sale_line l JOIN sale s ON s.id = l.sale_id " +
                "WHERE s.status = $OK AND l.product_id IS NOT NULL AND l.stock_qty != 0) " +
                "WHERE product_id NOT IN (SELECT product_id FROM stock_count) GROUP BY product_id",
        )
        // Counted products: last count + later events, one by one (few in practice).
        val counted = tx.db.queryList("SELECT DISTINCT product_id FROM stock_count", null) { it.getLong(0) }
        for (pid in counted) StockDao.rebuild(tx, pid)
    }

    fun customerBalances(tx: Db.Tx) {
        tx.exec("DELETE FROM customer_balance")
        tx.exec(CustomerDao.REBUILD_BALANCES)
    }

    fun all(tx: Db.Tx) {
        refundedAmounts(tx)
        stockLevels(tx)
        summaries(tx)
        customerBalances(tx)
    }
}
