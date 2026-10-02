package com.lekaspos.data.purchase

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.inventory.CostMath
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.money.Checked
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.sync.Outbox

/** A delivery to record. [qty] milli-units of the base unit, [unitCost] per base unit. */
data class PurchaseLineIn(val productId: Long, val qty: Long, val unitCost: Long, val total: Long)

data class PurchaseIn(val supplierId: Long?, val refNo: String?, val note: String?, val lines: List<PurchaseLineIn>)

data class PurchaseRow(
    val id: Long,
    val supplierId: Long?,
    val supplierName: String?,
    val refNo: String?,
    val total: Long,
    val lineCount: Int,
    val note: String?,
    val staffId: Long?,
    val at: Long,
)

data class PurchaseLineRow(val id: Long, val productId: Long, val name: String?, val unit: String?, val qty: Long, val unitCost: Long, val total: Long)

/**
 * EVENT tables `purchase` / `purchase_line`: stock received from suppliers. A purchase also
 * writes one RECEIVE movement per line (with the line's id, so importers can regenerate them)
 * and moves each product's cost to the moving average (D-033), all in one transaction.
 */
object PurchaseDao {
    private val PURCHASE_COLS = arrayOf("id", "supplier_id", "ref_no", "total", "note", "staff_id", "at", "hlc")
    private val LINE_COLS = arrayOf("id", "purchase_id", "product_id", "qty", "unit_cost", "total")
    private const val INSERT_PURCHASE =
        "INSERT INTO purchase(id, supplier_id, ref_no, total, note, staff_id, at, hlc) VALUES(?,?,?,?,?,?,?,?)"
    private const val INSERT_LINE =
        "INSERT INTO purchase_line(id, purchase_id, product_id, qty, unit_cost, total) VALUES(?,?,?,?,?,?)"

    /** Stores a delivery; returns the purchase id. Must run inside Db.write. */
    fun commit(tx: Db.Tx, p: PurchaseIn, staffId: Long?, at: Long): Long {
        require(p.lines.isNotEmpty()) { "a delivery needs at least one line" }
        val id = tx.nextId()
        // Its RECEIVE movements come after the products' last counts (StockDao.stampAfterCounts).
        val hlc = StockDao.stampAfterCounts(tx.db, tx.hlcNow(), p.lines.map { it.productId }.distinct())
        var total = 0L
        for (l in p.lines) {
            require(l.qty > 0L && l.total >= 0L && l.unitCost >= 0L) { "invalid delivery line" }
            total = Checked.add(total, l.total)
        }
        val purchase = arrayOf<Any?>(id, p.supplierId, p.refNo?.takeIf { it.isNotBlank() }, total, p.note?.takeIf { it.isNotBlank() }, staffId, at, hlc)
        tx.insert(INSERT_PURCHASE, *purchase)
        val lineValues = ArrayList<Array<Any?>>(p.lines.size)
        for (l in p.lines) {
            val product = ProductDao.get(tx.db, l.productId) ?: throw IllegalArgumentException("no product ${l.productId}")
            val lineId = tx.nextId()
            val v = arrayOf<Any?>(lineId, id, l.productId, l.qty, l.unitCost, l.total)
            tx.insert(INSERT_LINE, *v)
            lineValues.add(v)
            // Average against what is on hand *before* this line arrives. A product without stock
            // tracking has no stock to average with: its sales never lower the level, so the level
            // only grew with every delivery and price rises never reached its cost (2026-10 review).
            val onHand = if (product.trackStock) StockDao.level(tx.db, l.productId) else 0L
            val newCost = CostMath.movingAverage(onHand, product.cost, l.qty, l.total)
            StockDao.insertMovementRow(
                tx, arrayOf<Any?>(lineId, l.productId, MovementKind.RECEIVE, l.qty, l.unitCost, id, null, staffId, at, hlc),
            )
            if (newCost != product.cost) ProductDao.update(tx, product, product.copy(cost = newCost), at)
        }
        if (tx.syncEnabled) {
            val payload = Outbox.json { w ->
                w.beginObject()
                w.name("purchase")
                Outbox.writeRow(w, PURCHASE_COLS, purchase)
                w.name("lines").beginArray()
                for (v in lineValues) Outbox.writeRow(w, LINE_COLS, v)
                w.endArray()
                w.endObject()
            }
            Outbox.append(tx, Entity.PURCHASE, EventOp.INSERT, id, hlc, payload)
        }
        return id
    }

    // ------------------------------------------------------------------ queries

    private const val SELECT =
        "SELECT p.id, p.supplier_id, s.name, p.ref_no, p.total, " +
            "(SELECT COUNT(*) FROM purchase_line l WHERE l.purchase_id = p.id), p.note, p.staff_id, p.at " +
            "FROM purchase p LEFT JOIN supplier s ON s.id = p.supplier_id "
    private const val PAGE_FIRST = SELECT + "ORDER BY p.at DESC, p.id DESC LIMIT ?"
    private const val PAGE_NEXT = SELECT + "WHERE p.at <= ? AND (p.at < ? OR p.id < ?) ORDER BY p.at DESC, p.id DESC LIMIT ?"
    private const val SUPPLIER_FIRST = SELECT + "WHERE p.supplier_id = ? ORDER BY p.at DESC, p.id DESC LIMIT ?"
    private const val SUPPLIER_NEXT =
        SELECT + "WHERE p.supplier_id = ? AND p.at <= ? AND (p.at < ? OR p.id < ?) ORDER BY p.at DESC, p.id DESC LIMIT ?"

    /** Deliveries, newest first; only one supplier's when [supplierId] is set. Keyset-paginated. */
    fun page(db: SQLiteDatabase, supplierId: Long?, after: PurchaseRow?, limit: Int = 50): List<PurchaseRow> = when {
        supplierId == null && after == null -> db.queryList(PAGE_FIRST, args(limit), ::row)
        supplierId == null && after != null -> db.queryList(PAGE_NEXT, args(after.at, after.at, after.id, limit), ::row)
        after == null -> db.queryList(SUPPLIER_FIRST, args(supplierId, limit), ::row)
        else -> db.queryList(SUPPLIER_NEXT, args(supplierId, after.at, after.at, after.id, limit), ::row)
    }

    fun get(db: SQLiteDatabase, id: Long): PurchaseRow? = db.queryOne(SELECT + "WHERE p.id = ?", args(id), ::row)

    fun lines(db: SQLiteDatabase, purchaseId: Long): List<PurchaseLineRow> = db.queryList(
        "SELECT l.id, l.product_id, p.name, p.unit, l.qty, l.unit_cost, l.total FROM purchase_line l " +
            "LEFT JOIN product p ON p.id = l.product_id WHERE l.purchase_id = ? ORDER BY l.id",
        args(purchaseId),
    ) { c -> PurchaseLineRow(c.getLong(0), c.getLong(1), c.stringOrNull(2), c.stringOrNull(3), c.getLong(4), c.getLong(5), c.getLong(6)) }

    private fun row(c: Cursor) = PurchaseRow(
        id = c.getLong(0), supplierId = c.longOrNull(1), supplierName = c.stringOrNull(2), refNo = c.stringOrNull(3),
        total = c.getLong(4), lineCount = c.getInt(5), note = c.stringOrNull(6), staffId = c.longOrNull(7), at = c.getLong(8),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "purchases_first" to PAGE_FIRST,
        "purchases_next" to PAGE_NEXT,
        "purchases_supplier_first" to SUPPLIER_FIRST,
        "purchases_supplier_next" to SUPPLIER_NEXT,
    )
}
