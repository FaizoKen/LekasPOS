package com.lekaspos.data.stock

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.Outbox

data class LowStockItem(val productId: Long, val name: String, val nameKey: String, val lowStock: Long, val qty: Long, val unit: String)

/** One non-sale stock movement with its product name (the "stock changes" log). */
data class MovementRow(
    val id: Long,
    val productId: Long,
    val productName: String?,
    val unit: String?,
    val kind: Int,
    val qty: Long,
    val unitCost: Long?,
    val refId: Long?,
    val reason: String?,
    val staffId: Long?,
    val at: Long,
    val hlc: Long,
)

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

    // ------------------------------------------------------------------ low stock

    private const val LOW_COLUMNS = "p.id, p.name, p.name_key, p.low_stock, COALESCE(s.qty, 0), p.unit"
    private const val LOW_FILTER =
        "p.deleted = 0 AND p.low_stock > 0 AND p.track_stock = 1 AND COALESCE(s.qty, 0) <= p.low_stock"

    private const val LOW_STOCK =
        "SELECT $LOW_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE $LOW_FILTER ORDER BY p.name_key, p.id LIMIT ?"

    // Keyset page: walks the name index (deleted = 0) and filters; low-stock products are the rows kept.
    private const val LOW_STOCK_NEXT =
        "SELECT $LOW_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE $LOW_FILTER AND p.name_key >= ? AND (p.name_key > ? OR p.id > ?) ORDER BY p.name_key, p.id LIMIT ?"

    fun lowStock(db: SQLiteDatabase, limit: Int = 50): List<LowStockItem> = db.queryList(LOW_STOCK, args(limit), ::lowItem)

    fun lowStockPage(db: SQLiteDatabase, after: LowStockItem?, limit: Int = 50): List<LowStockItem> =
        if (after == null) {
            db.queryList(LOW_STOCK, args(limit), ::lowItem)
        } else {
            db.queryList(LOW_STOCK_NEXT, args(after.nameKey, after.nameKey, after.productId, limit), ::lowItem)
        }

    /** How many products are at or below their alert level (scans the catalogue: not for hot paths). */
    fun lowStockCount(db: SQLiteDatabase): Long =
        db.long("SELECT COUNT(*) FROM product p LEFT JOIN stock_level s ON s.product_id = p.id WHERE $LOW_FILTER")

    /** Of [productIds] (e.g. just sold), the ones now at or below their alert level. */
    fun lowAmong(db: SQLiteDatabase, productIds: Collection<Long>): List<LowStockItem> = productIds.distinct().mapNotNull { id ->
        db.queryOne(
            "SELECT $LOW_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id WHERE p.id = ? AND $LOW_FILTER",
            args(id), ::lowItem,
        )
    }

    private fun lowItem(c: Cursor) = LowStockItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5))

    // ------------------------------------------------------------------ movements

    private val MOVE_COLS = arrayOf("id", "product_id", "kind", "qty", "unit_cost", "ref_id", "reason", "staff_id", "at", "hlc")
    private const val INSERT_MOVEMENT =
        "INSERT INTO stock_movement(id, product_id, kind, qty, unit_cost, ref_id, reason, staff_id, at, hlc) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?)"

    /**
     * Records a non-sale movement (receive, adjust, waste, opening…), updates the cache and — while
     * sync is on — appends its STOCK_MOVE event.
     */
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
        val values = arrayOf<Any?>(id, productId, kind, qty, unitCost, refId, reason, staffId, at, hlc)
        insertMovementRow(tx, values)
        if (tx.syncEnabled) {
            Outbox.append(tx, Entity.STOCK_MOVE, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, MOVE_COLS, values) })
        }
        return id
    }

    /**
     * Inserts a movement row without an event of its own: purchases generate their movements
     * (id = purchase line id) and sync them inside the PURCHASE event (references/sync.md §4).
     */
    internal fun insertMovementRow(tx: Db.Tx, values: Array<Any?>) {
        require(values.size == MOVE_COLS.size)
        tx.insert(INSERT_MOVEMENT, *values)
        applyDelta(tx, values[1] as Long, values[3] as Long, values[9] as Long, Ids.deviceOf(values[0] as Long))
    }

    private const val MOVE_SELECT =
        "SELECT m.id, m.product_id, p.name, p.unit, m.kind, m.qty, m.unit_cost, m.ref_id, m.reason, m.staff_id, m.at, m.hlc " +
            "FROM stock_movement m LEFT JOIN product p ON p.id = m.product_id "
    private const val MOVES_FIRST = MOVE_SELECT + "ORDER BY m.hlc DESC, m.id DESC LIMIT ?"
    private const val MOVES_NEXT = MOVE_SELECT + "WHERE m.hlc <= ? AND (m.hlc < ? OR m.id < ?) ORDER BY m.hlc DESC, m.id DESC LIMIT ?"

    /** All stock movements, newest first (keyset on the hlc index). */
    fun movementPage(db: SQLiteDatabase, after: MovementRow?, limit: Int = 50): List<MovementRow> =
        if (after == null) {
            db.queryList(MOVES_FIRST, args(limit), ::movementRow)
        } else {
            db.queryList(MOVES_NEXT, args(after.hlc, after.hlc, after.id, limit), ::movementRow)
        }

    private fun movementRow(c: Cursor) = MovementRow(
        id = c.getLong(0), productId = c.getLong(1), productName = c.stringOrNull(2), unit = c.stringOrNull(3), kind = c.getInt(4),
        qty = c.getLong(5), unitCost = c.longOrNull(6), refId = c.longOrNull(7), reason = c.stringOrNull(8),
        staffId = c.longOrNull(9), at = c.getLong(10), hlc = c.getLong(11),
    )

    // ------------------------------------------------------------------ counts

    private val COUNT_COLS = arrayOf("id", "product_id", "qty", "session_id", "staff_id", "note", "at", "hlc", "expected", "unit_cost")
    private const val INSERT_COUNT =
        "INSERT INTO stock_count(id, product_id, qty, session_id, staff_id, note, at, hlc, expected, unit_cost) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?)"

    /**
     * Records an absolute stock count stamped with a new HLC. A count is the new baseline: the
     * level becomes the counted qty plus whatever happened after it (references/database.md §7).
     * The level the app expected and the product's cost are kept for the variance report.
     */
    fun insertCount(tx: Db.Tx, productId: Long, qty: Long, sessionId: Long?, staffId: Long?, note: String?, at: Long): Long {
        val id = tx.nextId()
        val hlc = tx.hlcNow()
        val expected = level(tx.db, productId)
        val cost = tx.db.longOrNull("SELECT cost FROM product WHERE id = ?", productId)
        val values = arrayOf<Any?>(id, productId, qty, sessionId, staffId, note, at, hlc, expected, cost)
        tx.insert(INSERT_COUNT, *values)
        rebuild(tx, productId)
        if (tx.syncEnabled) {
            Outbox.append(tx, Entity.STOCK_COUNT, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, COUNT_COLS, values) })
        }
        return id
    }

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "stock_apply" to APPLY,
        "low_stock" to LOW_STOCK,
        "low_stock_next" to LOW_STOCK_NEXT,
        "stock_last_count" to LAST_COUNT,
        "stock_moves_after" to MOVES_AFTER,
        "stock_sales_after" to SALES_AFTER,
        "movements_first" to MOVES_FIRST,
        "movements_next" to MOVES_NEXT,
    )
}
