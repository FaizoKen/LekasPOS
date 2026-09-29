package com.lekaspos.data.stock

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.data.db.args
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.stringOrNull

/** One entry of a product's stock history. */
data class HistoryEntry(
    val type: Type,
    val id: Long,
    val hlc: Long,
    val at: Long,
    /** Stock change in base milli-units (0 for counts and voided sales). */
    val delta: Long,
    val counted: Long? = null,
    val expected: Long? = null,
    /** Movement kind (for MOVEMENT entries). */
    val kind: Int = 0,
    val unitCost: Long? = null,
    /** Receipt number, adjustment reason, … */
    val text: String? = null,
    val refId: Long? = null,
    val voided: Boolean = false,
) {
    enum class Type { SALE, REFUND, MOVEMENT, COUNT }
}

/**
 * A product's stock history: sale lines, movements and counts merged newest first. Each source
 * is read by its (product_id, hlc) index with the same keyset, so a page costs three index
 * range reads of at most [limit] rows, however long the history is.
 */
object StockHistoryDao {

    private const val SALES =
        "SELECT l.id, l.hlc, s.sold_at, l.stock_qty, s.status, s.kind, s.receipt_no, l.sale_id FROM sale_line l " +
            "JOIN sale s ON s.id = l.sale_id WHERE l.product_id = ? AND l.hlc <= ? AND (l.hlc < ? OR l.id < ?) " +
            "ORDER BY l.hlc DESC, l.id DESC LIMIT ?"
    private const val MOVES =
        "SELECT id, hlc, at, qty, kind, unit_cost, reason, ref_id FROM stock_movement " +
            "WHERE product_id = ? AND hlc <= ? AND (hlc < ? OR id < ?) ORDER BY hlc DESC, id DESC LIMIT ?"
    private const val COUNTS =
        "SELECT id, hlc, at, qty, expected FROM stock_count " +
            "WHERE product_id = ? AND hlc <= ? AND (hlc < ? OR id < ?) ORDER BY hlc DESC, id DESC LIMIT ?"

    /** Entries strictly older than [after] (null = from the newest), at most [limit]. */
    fun page(db: SQLiteDatabase, productId: Long, after: HistoryEntry?, limit: Int = 50): List<HistoryEntry> {
        val hlc = after?.hlc ?: Long.MAX_VALUE
        val id = after?.id ?: Long.MAX_VALUE
        val a = args(productId, hlc, hlc, id, limit)
        val all = ArrayList<HistoryEntry>(limit * 3)
        db.queryList(SALES, a) { c ->
            val voided = c.getInt(4) == SaleStatus.VOIDED
            HistoryEntry(
                type = if (c.getInt(5) == SaleKind.REFUND) HistoryEntry.Type.REFUND else HistoryEntry.Type.SALE,
                id = c.getLong(0), hlc = c.getLong(1), at = c.getLong(2), delta = if (voided) 0L else c.getLong(3),
                text = c.getString(6), refId = c.getLong(7), voided = voided,
            )
        }.let { all.addAll(it) }
        db.queryList(MOVES, a) { c ->
            HistoryEntry(
                type = HistoryEntry.Type.MOVEMENT, id = c.getLong(0), hlc = c.getLong(1), at = c.getLong(2), delta = c.getLong(3),
                kind = c.getInt(4), unitCost = c.longOrNull(5), text = c.stringOrNull(6), refId = c.longOrNull(7),
            )
        }.let { all.addAll(it) }
        db.queryList(COUNTS, a) { c ->
            HistoryEntry(
                type = HistoryEntry.Type.COUNT, id = c.getLong(0), hlc = c.getLong(1), at = c.getLong(2), delta = 0L,
                counted = c.getLong(3), expected = c.longOrNull(4),
            )
        }.let { all.addAll(it) }
        all.sortWith(compareByDescending<HistoryEntry> { it.hlc }.thenByDescending { it.id })
        return if (all.size > limit) all.subList(0, limit).toList() else all
    }

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "history_sales" to SALES,
        "history_moves" to MOVES,
        "history_counts" to COUNTS,
    )
}
