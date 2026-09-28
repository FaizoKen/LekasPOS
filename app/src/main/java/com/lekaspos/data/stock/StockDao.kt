package com.lekaspos.data.stock

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne

data class LowStockItem(val productId: Long, val name: String, val lowStock: Long, val qty: Long)

/**
 * Stock levels (references/database.md §7): level = latest count by (hlc, device) + every
 * movement after it + stock effects of non-voided sale lines after it. `stock_level` caches the
 * result and is updated in the same transaction as each event.
 */
object StockDao {

    // Applies only when the movement is newer than the product's last count.
    private const val APPLY =
        "UPDATE stock_level SET qty = qty + ? WHERE product_id = ? " +
            "AND (count_hlc < ? OR (count_hlc = ? AND count_dev < ?))"
    private const val INSERT_IF_MISSING =
        "INSERT OR IGNORE INTO stock_level(product_id, qty, count_hlc, count_dev) VALUES(?, ?, 0, 0)"

    /** Adds [delta] (milli-units) stamped (hlc, dev) to the cached level of [productId]. */
    fun applyDelta(tx: Db.Tx, productId: Long, delta: Long, hlc: Long, dev: Int) {
        if (delta == 0L) return
        if (tx.update(APPLY, delta, productId, hlc, hlc, dev) == 0) {
            // No row yet → create it. If the row exists, the movement predates the last count
            // and is already reflected in it, so INSERT OR IGNORE correctly does nothing.
            tx.insert(INSERT_IF_MISSING, productId, delta)
        }
    }

    fun level(db: SQLiteDatabase, productId: Long): Long =
        db.long("SELECT qty FROM stock_level WHERE product_id = ?", productId)

    private const val LAST_COUNT =
        "SELECT qty, hlc, id FROM stock_count WHERE product_id = ? ORDER BY hlc DESC, (id >> 41) DESC LIMIT 1"
    private const val MOVES_AFTER =
        "SELECT SUM(qty) FROM stock_movement WHERE product_id = ? AND (hlc > ? OR (hlc = ? AND (id >> 41) > ?))"
    private const val SALES_AFTER =
        "SELECT SUM(l.stock_qty) FROM sale_line l JOIN sale s ON s.id = l.sale_id " +
            "WHERE l.product_id = ? AND s.status = ${SaleStatus.COMPLETED} " +
            "AND (l.hlc > ? OR (l.hlc = ? AND (l.id >> 41) > ?))"

    /** Recomputes the cached level of one product from events. */
    fun rebuild(tx: Db.Tx, productId: Long) {
        val db = tx.db
        val count = db.queryOne(LAST_COUNT, args(productId)) {
            Triple(it.getLong(0), it.getLong(1), Ids.deviceOf(it.getLong(2)))
        }
        val base = count?.first ?: 0L
        val cHlc = count?.second ?: 0L
        val cDev = count?.third ?: 0
        val moves = db.longOrNull(MOVES_AFTER, productId, cHlc, cHlc, cDev) ?: 0L
        val sales = db.longOrNull(SALES_AFTER, productId, cHlc, cHlc, cDev) ?: 0L
        tx.exec(
            "INSERT OR REPLACE INTO stock_level(product_id, qty, count_hlc, count_dev) VALUES(?, ?, ?, ?)",
            productId, base + moves + sales, cHlc, cDev,
        )
    }

    private const val LOW_STOCK =
        "SELECT p.id, p.name, p.low_stock, COALESCE(s.qty, 0) FROM product p " +
            "LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE p.deleted = 0 AND p.low_stock > 0 AND p.track_stock = 1 " +
            "AND COALESCE(s.qty, 0) <= p.low_stock ORDER BY p.name_key LIMIT ?"

    fun lowStock(db: SQLiteDatabase, limit: Int = 50): List<LowStockItem> =
        db.queryList(LOW_STOCK, args(limit)) { LowStockItem(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3)) }

    private const val INSERT_MOVEMENT =
        "INSERT INTO stock_movement(id, product_id, kind, qty, unit_cost, ref_id, reason, staff_id, at, hlc) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?)"

    /** Records a non-sale movement (receive, adjust, waste, …) and updates the cache. */
    fun insertMovement(
        tx: Db.Tx,
        productId: Long,
        kind: Int,
        qty: Long,
        unitCost: Long?,
        refId: Long?,
        reason: String?,
        staffId: Long?,
        at: Long,
    ): Long {
        val id = tx.nextId()
        val hlc = tx.hlcNow()
        tx.insert(INSERT_MOVEMENT, id, productId, kind, qty, unitCost, refId, reason, staffId, at, hlc)
        applyDelta(tx, productId, qty, hlc, tx.deviceNo)
        return id
    }

    private const val INSERT_COUNT =
        "INSERT INTO stock_count(id, product_id, qty, session_id, staff_id, note, at, hlc) VALUES(?,?,?,?,?,?,?,?)"

    /**
     * Records an absolute stock count stamped with a new HLC. A count is the new baseline: the
     * level becomes the counted qty plus whatever happened after it (references/database.md §7).
     */
    fun insertCount(tx: Db.Tx, productId: Long, qty: Long, sessionId: Long?, staffId: Long?, note: String?, at: Long): Long {
        val id = tx.nextId()
        tx.insert(INSERT_COUNT, id, productId, qty, sessionId, staffId, note, at, tx.hlcNow())
        rebuild(tx, productId)
        return id
    }

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "stock_apply" to APPLY,
        "low_stock" to LOW_STOCK,
        "stock_last_count" to LAST_COUNT,
        "stock_moves_after" to MOVES_AFTER,
        "stock_sales_after" to SALES_AFTER,
    )
}
