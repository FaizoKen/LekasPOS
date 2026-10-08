package com.lekaspos.data.cart

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.CartStatus
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull

/** One line of an open or held bill, persisted so a crash never loses the current bill. */
data class CartLine(
    val id: Long = 0L,
    val lineNo: Int,
    val productId: Long?,
    val name: String,
    val barcode: String? = null,
    val unit: String? = null,
    val categoryId: Long? = null,
    val sellMode: Int = 0,
    val qty: Long,
    val packQty: Long = 1000L,
    val baseQty: Long? = null,
    val unitPrice: Long,
    val fixedGross: Long? = null,
    val priceOverridden: Boolean = false,
    val discKind: Int = 0,
    val discValue: Long = 0L,
    val taxRateId: Long? = null,
    val taxBp: Int = 0,
    val unitCost: Long = 0L,
    val trackStock: Boolean = true,
    val addedAt: Long,
)

data class StoredCart(
    val id: Long,
    val status: Int,
    val label: String?,
    val customerId: Long?,
    val staffId: Long?,
    val billDiscKind: Int,
    val billDiscValue: Long,
    val openedAt: Long,
    val updatedAt: Long,
    val lines: List<CartLine>,
    /** The payment screen showed its total (D-067). */
    val payShown: Boolean = false,
)

/** A parked bill; [by] = who started it (D-067: the held list says whose bill it is). */
data class HeldCartRow(val id: Long, val label: String?, val openedAt: Long, val updatedAt: Long, val lines: Int, val by: String? = null)

/**
 * LOCAL tables `cart` / `cart_line`: the open bill and parked (held) bills. IDs are assigned by
 * the cart session (in memory first, persisted right after), so the UI never waits for a write.
 */
object CartDao {

    private const val INSERT_CART =
        "INSERT INTO cart(id, status, label, staff_id, bill_disc_kind, bill_disc_value, opened_at, updated_at) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
    private const val UPSERT_LINE =
        "INSERT OR REPLACE INTO cart_line(id, cart_id, line_no, product_id, name, barcode, unit, category_id, sell_mode, " +
            "qty, pack_qty, base_qty, unit_price, fixed_gross, price_overridden, disc_kind, disc_value, tax_rate_id, " +
            "tax_bp, unit_cost, track_stock, added_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    private const val DELETE_LINE = "DELETE FROM cart_line WHERE id = ?"
    private const val TOUCH = "UPDATE cart SET updated_at = ? WHERE id = ?"

    fun insertCart(tx: Db.Tx, id: Long, staffId: Long?, openedAt: Long, now: Long, status: Int = CartStatus.OPEN) {
        tx.insert(INSERT_CART, id, status, null, staffId, 0, 0L, openedAt, now)
    }

    /** Inserts or replaces a line (the whole row: qty, price, discount…). */
    fun putLine(tx: Db.Tx, cartId: Long, l: CartLine, now: Long) {
        require(l.id > 0L) { "cart lines need an id" }
        tx.insert(
            UPSERT_LINE, l.id, cartId, l.lineNo, l.productId, l.name, l.barcode, l.unit, l.categoryId, l.sellMode,
            l.qty, l.packQty, l.baseQty, l.unitPrice, l.fixedGross, l.priceOverridden, l.discKind, l.discValue,
            l.taxRateId, l.taxBp, l.unitCost, l.trackStock, l.addedAt,
        )
        tx.update(TOUCH, now, cartId)
    }

    fun deleteLine(tx: Db.Tx, cartId: Long, lineId: Long, now: Long) {
        tx.update(DELETE_LINE, lineId)
        tx.update(TOUCH, now, cartId)
    }

    fun deleteLines(tx: Db.Tx, cartId: Long, now: Long) {
        tx.update("DELETE FROM cart_line WHERE cart_id = ?", cartId)
        tx.update(TOUCH, now, cartId)
    }

    fun setBillDiscount(tx: Db.Tx, cartId: Long, kind: Int, value: Long, now: Long) {
        tx.update("UPDATE cart SET bill_disc_kind = ?, bill_disc_value = ?, updated_at = ? WHERE id = ?", kind, value, now, cartId)
    }

    fun setCustomer(tx: Db.Tx, cartId: Long, customerId: Long?, now: Long) {
        tx.update("UPDATE cart SET customer_id = ?, updated_at = ? WHERE id = ?", customerId, now, cartId)
    }

    /** The payment screen showed bill [cartId]'s total: what is taken off it from now on is marked so (D-067). */
    fun setPayShown(tx: Db.Tx, cartId: Long) {
        tx.update("UPDATE cart SET pay_shown = 1 WHERE id = ?", cartId)
    }

    fun deleteCart(tx: Db.Tx, cartId: Long) {
        tx.update("DELETE FROM cart WHERE id = ?", cartId) // lines cascade
    }

    fun setStatus(tx: Db.Tx, cartId: Long, status: Int, label: String?, now: Long) {
        tx.update("UPDATE cart SET status = ?, label = ?, updated_at = ? WHERE id = ?", status, label, now, cartId)
    }

    private const val SET_OPEN =
        "UPDATE cart SET status = ?, label = NULL, customer_id = ?, bill_disc_kind = ?, bill_disc_value = ?, " +
            "updated_at = ? WHERE id = ?"
    private const val PARK_OPEN = "UPDATE cart SET status = ?, updated_at = ? WHERE status = ? AND id <> ?"

    /**
     * Writes open bill [id] whole, as the cart session has it, after one of its writes failed (2026-10
     * review): its row (created again if that write was the one lost), customer, discount and every
     * line. Any other bill still marked open is parked, so it is neither lost nor loaded in its place.
     */
    fun rewriteOpen(
        tx: Db.Tx, id: Long, staffId: Long?, openedAt: Long, customerId: Long?, discKind: Int, discValue: Long,
        lines: List<CartLine>, now: Long, payShown: Boolean = false,
    ) {
        if (tx.update(SET_OPEN, CartStatus.OPEN, customerId, discKind, discValue, now, id) == 0) {
            insertCart(tx, id, staffId, openedAt, now)
            tx.update(SET_OPEN, CartStatus.OPEN, customerId, discKind, discValue, now, id)
        }
        if (payShown) setPayShown(tx, id)
        tx.update("DELETE FROM cart_line WHERE cart_id = ?", id)
        for (l in lines) putLine(tx, id, l, now)
        parkOpen(tx, id, now)
    }

    /** Parks every bill marked open except [keepId] (0: all of them). */
    fun parkOpen(tx: Db.Tx, keepId: Long, now: Long) {
        tx.update(PARK_OPEN, CartStatus.HELD, now, CartStatus.OPEN, keepId)
    }

    /** The open bill (the most recently touched one if there are several), with lines in order. */
    fun loadOpen(db: SQLiteDatabase): StoredCart? {
        val id = db.queryOne(
            "SELECT id FROM cart WHERE status = ? ORDER BY updated_at DESC LIMIT 1", args(CartStatus.OPEN),
        ) { it.getLong(0) } ?: return null
        return load(db, id)
    }

    fun load(db: SQLiteDatabase, cartId: Long): StoredCart? {
        val header = db.queryOne(
            "SELECT id, status, label, customer_id, staff_id, bill_disc_kind, bill_disc_value, opened_at, updated_at, pay_shown " +
                "FROM cart WHERE id = ?",
            args(cartId),
        ) { c ->
            StoredCart(
                c.getLong(0), c.getInt(1), c.stringOrNull(2), c.longOrNull(3), c.longOrNull(4), c.getInt(5),
                c.getLong(6), c.getLong(7), c.getLong(8), emptyList(), payShown = c.getInt(9) != 0,
            )
        } ?: return null
        val lines = db.queryList(
            "SELECT id, line_no, product_id, name, barcode, unit, category_id, sell_mode, qty, pack_qty, base_qty, " +
                "unit_price, fixed_gross, price_overridden, disc_kind, disc_value, tax_rate_id, tax_bp, unit_cost, " +
                "track_stock, added_at FROM cart_line WHERE cart_id = ? ORDER BY line_no, id",
            args(cartId),
        ) { c ->
            CartLine(
                id = c.getLong(0), lineNo = c.getInt(1), productId = c.longOrNull(2), name = c.getString(3),
                barcode = c.stringOrNull(4), unit = c.stringOrNull(5), categoryId = c.longOrNull(6),
                sellMode = c.getInt(7), qty = c.getLong(8), packQty = c.getLong(9), baseQty = c.longOrNull(10),
                unitPrice = c.getLong(11), fixedGross = c.longOrNull(12), priceOverridden = c.bool(13),
                discKind = c.getInt(14), discValue = c.getLong(15), taxRateId = c.longOrNull(16),
                taxBp = c.getInt(17), unitCost = c.getLong(18), trackStock = c.bool(19), addedAt = c.getLong(20),
            )
        }
        return header.copy(lines = lines)
    }

    /** Parked bills, most recently parked first. */
    fun held(db: SQLiteDatabase): List<HeldCartRow> = db.queryList(
        "SELECT c.id, c.label, c.opened_at, c.updated_at, (SELECT COUNT(*) FROM cart_line l WHERE l.cart_id = c.id), s.name " +
            "FROM cart c LEFT JOIN staff s ON s.id = c.staff_id WHERE c.status = ? ORDER BY c.updated_at DESC",
        args(CartStatus.HELD),
    ) { c -> HeldCartRow(c.getLong(0), c.stringOrNull(1), c.getLong(2), c.getLong(3), c.getInt(4), c.stringOrNull(5)) }

    fun heldCount(db: SQLiteDatabase): Int = db.long("SELECT COUNT(*) FROM cart WHERE status = ?", CartStatus.HELD).toInt()

    /** Highest cart and cart-line ids in use, so the session can hand out new ones in memory. */
    fun maxIds(db: SQLiteDatabase): Pair<Long, Long> =
        db.long("SELECT MAX(id) FROM cart") to db.long("SELECT MAX(id) FROM cart_line")
}
