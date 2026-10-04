package com.lekaspos.data.stock

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.sync.Lww
import com.lekaspos.core.time.Hlc
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
            // and is already reflected in it, so INSERT OR IGNORE correctly does nothing — but the
            // level the first count after it expected changes ([countExpectedMoved]).
            if (tx.insert(INSERT_IF_MISSING, productId, delta) == -1L) countExpectedMoved(tx, productId, delta, hlc, dev)
        }
    }

    // The first count at or after (hlc, dev): device order through the id's top bits (`id >= dev << 41`),
    // a typed comparison with the INTEGER id (a text-bound `(id >> 41) > ?` is always true).
    private const val FIRST_COUNT_FROM =
        "SELECT id FROM stock_count WHERE product_id = ? AND hlc >= ? AND (hlc > ? OR id >= ?) ORDER BY hlc, id LIMIT 1"
    private const val EXPECTED_ADD = "UPDATE stock_count SET expected = expected + ? WHERE id = ?"

    /**
     * An event stamped (hlc, dev) arrived after a count it comes before (another till's sale, made
     * before the count but synced after it): the level that count expected changes by [delta]. The
     * count report said "lost 3" for good for a shelf that was right (2026-10 review).
     */
    private fun countExpectedMoved(tx: Db.Tx, productId: Long, delta: Long, hlc: Long, dev: Int) {
        val countId = tx.db.longOrNull(FIRST_COUNT_FROM, productId, hlc, hlc, dev.toLong() shl Ids.SEQ_BITS) ?: return
        tx.update(EXPECTED_ADD, delta, countId)
    }

    fun level(db: SQLiteDatabase, productId: Long): Long =
        db.long("SELECT qty FROM stock_level WHERE product_id = ?", productId)

    /**
     * The timestamp a new local sale or movement of [productIds] gets: [now], or just above the
     * products' last counts when one of them is stamped later. Stock is ordered by these timestamps
     * on every till (count, then what came after it); a count from a till whose clock had run a year
     * ahead made every later sale of the other tills count as "before" it for a year, and recounts
     * seemed to do nothing (2026-10 review). This till's own clock is not moved.
     */
    fun stampAfterCounts(db: SQLiteDatabase, now: Long, productIds: Collection<Long>): Long {
        var floor = 0L
        for (id in productIds) floor = maxOf(floor, db.long(COUNT_HLC, id))
        return Lww.stampAbove(now, floor)
    }

    /**
     * The timestamp a new local count of [productId] gets: after everything known about its stock (see
     * [stampAfterCounts]) — except sales and movements stamped more than [Hlc.MAX_FUTURE_MS] ahead of
     * [now]: one sale from a till whose date was a year ahead stamped every later count a year ahead,
     * and those counts then swallowed the other tills' sales made after them but not yet synced (the
     * shelf said 30, every till 50), spreading to each product sold on one bill with it (2026-10
     * review). Such an event now counts after the count: the error stays with the broken till's own sale.
     */
    fun stampAfterEverything(db: SQLiteDatabase, now: Long, productId: Long): Long {
        val countHlc = db.long(COUNT_HLC, productId)
        // Never below what [stampAfterCounts] put just above the last count (every sale after a count
        // from a clock far ahead): a recount stamped level with those sales took some of them twice.
        val bound = maxOf(Hlc.pack(Hlc.physicalOf(now) + Hlc.MAX_FUTURE_MS, 0), countHlc + 2)
        val floor = maxOf(countHlc, db.long(LAST_SALE_HLC, productId, bound), db.long(LAST_MOVE_HLC, productId, bound))
        return Lww.stampAbove(now, floor)
    }

    private const val COUNT_HLC = "SELECT count_hlc FROM stock_level WHERE product_id = ?"
    private const val LAST_SALE_HLC = "SELECT MAX(hlc) FROM sale_line WHERE product_id = ? AND hlc < ?"
    private const val LAST_MOVE_HLC = "SELECT MAX(hlc) FROM stock_movement WHERE product_id = ? AND hlc < ?"

    private const val LAST_COUNT =
        "SELECT qty, hlc, id FROM stock_count WHERE product_id = ? ORDER BY hlc DESC, (id >> 41) DESC LIMIT 1"

    // "After (count hlc, count device)" written with an index range first: `hlc >= ?` makes it a
    // range of (product_id, hlc), where the bare `hlc > ? OR (hlc = ? AND …)` form may read every
    // event of the product on old SQLite (references/database.md §8; QueryPlans checks the range).
    private const val MOVES_AFTER =
        "SELECT SUM(qty) FROM stock_movement WHERE product_id = ? AND hlc >= ? AND (hlc > ? OR (id >> 41) > ?)"
    private const val SALES_AFTER =
        "SELECT SUM(l.stock_qty) FROM sale_line l JOIN sale s ON s.id = l.sale_id " +
            "WHERE l.product_id = ? AND l.hlc >= ? AND (l.hlc > ? OR (l.id >> 41) > ?) " +
            "AND s.status = ${SaleStatus.COMPLETED}"

    // "Before (count hlc, count device)" and "between two counts": the same order as [rebuild], with an
    // index range on (product_id, hlc) first. COUNT_BEFORE's arguments are bound as text (rawQuery): the
    // device is compared through the INTEGER id (`id < dev << 41`), as `(id >> 41) < '3'` is always true.
    private const val COUNT_BEFORE =
        "SELECT qty, hlc, id FROM stock_count WHERE product_id = ? AND hlc <= ? AND (hlc < ? OR id < ?) " +
            "ORDER BY hlc DESC, id DESC LIMIT 1"
    private const val MOVES_BETWEEN =
        "SELECT SUM(qty) FROM stock_movement WHERE product_id = ? AND hlc >= ? AND hlc <= ? " +
            "AND (hlc > ? OR (id >> 41) > ?) AND (hlc < ? OR (id >> 41) < ?)"
    private const val SALES_BETWEEN =
        "SELECT SUM(l.stock_qty) FROM sale_line l JOIN sale s ON s.id = l.sale_id " +
            "WHERE l.product_id = ? AND l.hlc >= ? AND l.hlc <= ? AND (l.hlc > ? OR (l.id >> 41) > ?) " +
            "AND (l.hlc < ? OR (l.id >> 41) < ?) AND s.status = ${SaleStatus.COMPLETED}"

    /**
     * The stock of [productId] just before its count stamped ([hlc], [dev]), as this till's events say
     * now: the count before it (by hlc, then device) plus what came between. Reads every event between
     * the two counts: used when a count arrives from another till ([countImported]), never per row of a
     * list (a first count of a busy product reads its whole history).
     */
    fun levelBefore(db: SQLiteDatabase, productId: Long, hlc: Long, dev: Int): Long {
        val prev = db.queryOne(COUNT_BEFORE, args(productId, hlc, hlc, dev.toLong() shl Ids.SEQ_BITS)) {
            Triple(it.getLong(0), it.getLong(1), Ids.deviceOf(it.getLong(2)))
        }
        val base = prev?.first ?: 0L
        val fromHlc = prev?.second ?: 0L
        val fromDev = prev?.third ?: -1
        val moves = db.longOrNull(MOVES_BETWEEN, productId, fromHlc, hlc, fromHlc, fromDev, hlc, dev) ?: 0L
        val sales = db.longOrNull(SALES_BETWEEN, productId, fromHlc, hlc, fromHlc, fromDev, hlc, dev) ?: 0L
        return base + moves + sales
    }

    private const val NEXT_COUNT =
        "SELECT id, hlc FROM stock_count WHERE product_id = ? AND hlc >= ? AND (hlc > ? OR id > ?) ORDER BY hlc, id LIMIT 1"
    private const val EXPECTED_SET = "UPDATE stock_count SET expected = ? WHERE id = ?"

    /**
     * A count arrived from another till: the level it expected is worked out from this till's events
     * (the counting till had not heard of every till's sales before it yet), and so is that of the count
     * after it, which now follows this one. Events that arrive later move them ([countExpectedMoved]):
     * every till ends with the same report, cheap to read (2026-10 review).
     */
    fun countImported(tx: Db.Tx, productId: Long, countId: Long, hlc: Long) {
        val db = tx.db
        tx.update(EXPECTED_SET, levelBefore(db, productId, hlc, Ids.deviceOf(countId)), countId)
        val next = db.queryOne(NEXT_COUNT, args(productId, hlc, hlc, countId)) { it.getLong(0) to it.getLong(1) } ?: return
        tx.update(EXPECTED_SET, levelBefore(db, productId, next.second, Ids.deviceOf(next.first)), next.first)
    }

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
    // Switched-off products are not reordered, so they never raise an alert.
    private const val LOW_FILTER =
        "p.deleted = 0 AND p.active = 1 AND p.low_stock > 0 AND p.track_stock = 1 AND COALESCE(s.qty, 0) <= p.low_stock"

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
        val hlc = stampAfterCounts(tx.db, tx.hlcNow(), listOf(productId))
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
    /** Like [insertMovementRow] but a movement already stored is left alone (sync import). Returns true if new. */
    internal fun insertMovementRowIfNew(tx: Db.Tx, values: Array<Any?>): Boolean {
        require(values.size == MOVE_COLS.size)
        if (tx.insert(INSERT_MOVEMENT.replaceFirst("INSERT INTO", "INSERT OR IGNORE INTO"), *values) == -1L) return false
        applyDelta(tx, values[1] as Long, values[3] as Long, values[9] as Long, Ids.deviceOf(values[0] as Long))
        return true
    }

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
        val hlc = stampAfterEverything(tx.db, tx.hlcNow(), productId)
        // The level now, without what is stamped after this count (a sale from a till whose clock ran
        // far ahead, see [stampAfterEverything]): that comes after the count, not before it.
        val dev = tx.deviceNo
        val after = (tx.db.longOrNull(MOVES_AFTER, productId, hlc, hlc, dev) ?: 0L) + (tx.db.longOrNull(SALES_AFTER, productId, hlc, hlc, dev) ?: 0L)
        val expected = level(tx.db, productId) - after
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
        "stock_count_hlc" to COUNT_HLC,
        "stock_last_sale_hlc" to LAST_SALE_HLC,
        "stock_count_before" to COUNT_BEFORE,
        "stock_moves_between" to MOVES_BETWEEN,
        "stock_sales_between" to SALES_BETWEEN,
        "stock_first_count_from" to FIRST_COUNT_FROM,
        "stock_next_count" to NEXT_COUNT,
        "stock_last_move_hlc" to LAST_MOVE_HLC,
        "stock_sales_after" to SALES_AFTER,
        "movements_first" to MOVES_FIRST,
        "movements_next" to MOVES_NEXT,
    )
}
