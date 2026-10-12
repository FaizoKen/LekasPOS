package com.lekaspos.data.promo

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.pricing.PriceTier
import com.lekaspos.core.pricing.PriceTiers
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
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
    /** A multi-buy's further prices ("3 for RM4" besides "2 for RM3"; `promotion_price`, D-073). */
    val morePrices: List<PriceTier> = emptyList(),
) {
    /** Running on [day] (epoch day)? */
    fun runsOn(day: Long): Boolean = active && (startDay == null || day >= startDay) && (endDay == null || day <= endDay)
}

/**
 * LWW tables `promotion` (schema v6) and `promotion_price` (v10, id = the promotion's id). A shop has a
 * handful, so they are read whole.
 */
object PromotionDao {
    private const val COLUMNS =
        "p.id, p.name, p.kind, p.buy_qty, p.free_qty, p.group_price, p.products, p.start_day, p.end_day, p.active, pp.prices"
    private const val FROM = " FROM promotion p LEFT JOIN promotion_price pp ON pp.id = p.id AND pp.deleted = 0"
    private const val LIST = "SELECT $COLUMNS$FROM WHERE p.deleted = 0 ORDER BY p.name, p.id"

    fun list(db: SQLiteDatabase): List<PromotionRow> = db.queryList(LIST, null, ::row)

    fun get(db: SQLiteDatabase, id: Long): PromotionRow? =
        db.queryOne("SELECT $COLUMNS$FROM WHERE p.id = ? AND p.deleted = 0", args(id), ::row)

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
        setPrices(tx, id, emptyList(), p.morePrices, now)
        return id
    }

    /** Writes only the fields that changed. */
    fun update(tx: Db.Tx, before: PromotionRow, after: PromotionRow, now: Long) {
        val old = fields(before)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(after)) if (old[k] != v) changes[k] = v
        if (changes.isNotEmpty()) LwwWriter.update(tx, "promotion", Entity.PROMOTION, before.id, changes, now)
        setPrices(tx, before.id, before.morePrices, after.morePrices, now)
    }

    /** The further prices, written when the form changed them from [shown] (the whole list: last writer wins). */
    private fun setPrices(tx: Db.Tx, id: Long, shown: List<PriceTier>, edited: List<PriceTier>, now: Long) {
        if (shown == edited) return
        val fields = mapOf("prices" to PriceTiers.encode(edited))
        if (tx.db.long("SELECT COUNT(*) FROM promotion_price WHERE id = ?", id) > 0L) {
            LwwWriter.update(tx, "promotion_price", Entity.PROMOTION_PRICE, id, fields, now)
        } else if (edited.isNotEmpty()) {
            LwwWriter.insert(tx, "promotion_price", Entity.PROMOTION_PRICE, id, fields, now)
        }
    }

    /** A deleted promotion's prices stay (nothing reads them without their promotion). */
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
        morePrices = PriceTiers.decode(c.stringOrNull(10)),
    )
}
