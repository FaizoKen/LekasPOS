package com.lekaspos.data.promo

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.sync.LwwWriter

/** A promotion as stored (Phase 8). Days are epoch days (inclusive); null = no limit. */
data class PromotionRow(
    val id: Long,
    val name: String,
    val kind: Int,
    val buyQty: Int,
    val freeQty: Int,
    val groupPrice: Long,
    val productIds: List<Long>,
    val startDay: Long? = null,
    val endDay: Long? = null,
    val active: Boolean = true,
) {
    /** Running on [day] (epoch day)? */
    fun runsOn(day: Long): Boolean = active && (startDay == null || day >= startDay) && (endDay == null || day <= endDay)
}

/** LWW table `promotion` (schema v6). A shop has a handful, so they are read whole. */
object PromotionDao {
    private const val COLUMNS = "id, name, kind, buy_qty, free_qty, group_price, products, start_day, end_day, active"
    private const val LIST = "SELECT $COLUMNS FROM promotion WHERE deleted = 0 ORDER BY name, id"

    fun list(db: SQLiteDatabase): List<PromotionRow> = db.queryList(LIST, null, ::row)

    fun get(db: SQLiteDatabase, id: Long): PromotionRow? =
        db.queryOne("SELECT $COLUMNS FROM promotion WHERE id = ? AND deleted = 0", args(id), ::row)

    fun fields(p: PromotionRow): LinkedHashMap<String, Any?> = linkedMapOf(
        "name" to p.name,
        "kind" to p.kind,
        "buy_qty" to p.buyQty,
        "free_qty" to p.freeQty,
        "group_price" to p.groupPrice,
        "products" to p.productIds.joinToString(","),
        "start_day" to p.startDay,
        "end_day" to p.endDay,
        "active" to p.active,
    )

    fun insert(tx: Db.Tx, p: PromotionRow, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(tx, "promotion", Entity.PROMOTION, id, fields(p), now)
        return id
    }

    /** Writes only the fields that changed. */
    fun update(tx: Db.Tx, before: PromotionRow, after: PromotionRow, now: Long) {
        val old = fields(before)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(after)) if (old[k] != v) changes[k] = v
        if (changes.isNotEmpty()) LwwWriter.update(tx, "promotion", Entity.PROMOTION, before.id, changes, now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "promotion", Entity.PROMOTION, id, now)
    }

    fun parseProducts(text: String?): List<Long> =
        text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()

    private fun row(c: Cursor) = PromotionRow(
        id = c.getLong(0),
        name = c.getString(1),
        kind = c.getInt(2),
        buyQty = c.getInt(3),
        freeQty = c.getInt(4),
        groupPrice = c.getLong(5),
        productIds = parseProducts(c.getString(6)),
        startDay = c.longOrNull(7),
        endDay = c.longOrNull(8),
        active = c.bool(9),
    )
}
