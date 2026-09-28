package com.lekaspos.data.cart

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.CartStatus
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
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

data class Cart(
    val id: Long,
    val status: Int,
    val label: String?,
    val customerId: Long?,
    val staffId: Long?,
    val billDiscKind: Int,
    val billDiscValue: Long,
    val openedAt: Long,
    val lines: List<CartLine>,
)

/** LOCAL tables `cart` / `cart_line`: the open bill and parked (held) bills. */
object CartDao {

    private const val INSERT_CART =
        "INSERT INTO cart(status, staff_id, opened_at, updated_at) VALUES(?, ?, ?, ?)"
    private const val INSERT_LINE =
        "INSERT INTO cart_line(cart_id, line_no, product_id, name, barcode, unit, category_id, sell_mode, qty, " +
            "pack_qty, base_qty, unit_price, fixed_gross, price_overridden, disc_kind, disc_value, tax_rate_id, " +
            "tax_bp, unit_cost, track_stock, added_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    private const val UPDATE_QTY = "UPDATE cart_line SET qty = ?, base_qty = ? WHERE id = ?"
    private const val DELETE_LINE = "DELETE FROM cart_line WHERE id = ?"
    private const val TOUCH = "UPDATE cart SET updated_at = ? WHERE id = ?"

    fun openCart(tx: Db.Tx, staffId: Long?, now: Long): Long = tx.insert(INSERT_CART, CartStatus.OPEN, staffId, now, now)

    fun insertLine(tx: Db.Tx, cartId: Long, l: CartLine, now: Long): Long {
        val id = tx.insert(
            INSERT_LINE, cartId, l.lineNo, l.productId, l.name, l.barcode, l.unit, l.categoryId, l.sellMode,
            l.qty, l.packQty, l.baseQty, l.unitPrice, l.fixedGross, l.priceOverridden, l.discKind, l.discValue,
            l.taxRateId, l.taxBp, l.unitCost, l.trackStock, l.addedAt,
        )
        tx.update(TOUCH, now, cartId)
        return id
    }

    fun updateQty(tx: Db.Tx, cartId: Long, lineId: Long, qty: Long, baseQty: Long?, now: Long) {
        tx.update(UPDATE_QTY, qty, baseQty, lineId)
        tx.update(TOUCH, now, cartId)
    }

    fun deleteLine(tx: Db.Tx, cartId: Long, lineId: Long, now: Long) {
        tx.update(DELETE_LINE, lineId)
        tx.update(TOUCH, now, cartId)
    }

    fun deleteCart(tx: Db.Tx, cartId: Long) {
        tx.update("DELETE FROM cart WHERE id = ?", cartId) // lines cascade
    }

    fun setStatus(tx: Db.Tx, cartId: Long, status: Int, label: String?, now: Long) {
        tx.update("UPDATE cart SET status = ?, label = ?, updated_at = ? WHERE id = ?", status, label, now, cartId)
    }

    /** The open bill (at most one), with lines in order. */
    fun loadOpen(db: SQLiteDatabase): Cart? {
        val id = db.queryOne(
            "SELECT id FROM cart WHERE status = ? ORDER BY updated_at DESC LIMIT 1", args(CartStatus.OPEN),
        ) { it.getLong(0) } ?: return null
        return load(db, id)
    }

    fun load(db: SQLiteDatabase, cartId: Long): Cart? {
        val header = db.queryOne(
            "SELECT id, status, label, customer_id, staff_id, bill_disc_kind, bill_disc_value, opened_at " +
                "FROM cart WHERE id = ?",
            args(cartId),
        ) { c ->
            Cart(
                c.getLong(0), c.getInt(1), c.stringOrNull(2), c.longOrNull(3), c.longOrNull(4), c.getInt(5),
                c.getLong(6), c.getLong(7), emptyList(),
            )
        } ?: return null
        val lines = db.queryList(
            "SELECT id, line_no, product_id, name, barcode, unit, category_id, sell_mode, qty, pack_qty, base_qty, " +
                "unit_price, fixed_gross, price_overridden, disc_kind, disc_value, tax_rate_id, tax_bp, unit_cost, " +
                "track_stock, added_at FROM cart_line WHERE cart_id = ? ORDER BY line_no",
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
}
