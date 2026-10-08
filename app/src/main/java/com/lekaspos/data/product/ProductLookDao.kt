package com.lekaspos.data.product

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.TileColor
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter
import com.lekaspos.data.sync.Outbox

/** A product's colour ([TileColor]) and picture ([imageId], null = none) on the selling screen (D-066). */
data class ProductLook(val color: Int = TileColor.NONE, val imageId: Long? = null)

/**
 * LWW `product_look` (id = the product's id) and EVENT `product_image` (D-066). A picture is never
 * changed: a new one is a new row and the look points at it, so a tile's picture is always one whole
 * picture whichever till's edit wins.
 */
object ProductLookDao {

    fun get(db: SQLiteDatabase, productId: Long): ProductLook = db.queryOne(
        "SELECT color, image_id FROM product_look WHERE id = ?", args(productId),
    ) { c -> ProductLook(c.getInt(0), if (c.isNull(1)) null else c.getLong(1)) } ?: ProductLook()

    private fun fields(l: ProductLook): Map<String, Any?> = linkedMapOf("color" to l.color, "image_id" to l.imageId)

    /**
     * An edit made on a screen that showed [shown]: writes the fields the user changed there that also
     * differ from what is stored now (another till's change to the other field stays). Returns whether
     * anything was written.
     */
    fun update(tx: Db.Tx, productId: Long, shown: ProductLook, edited: ProductLook, now: Long): Boolean {
        val exists = tx.db.long("SELECT COUNT(*) FROM product_look WHERE id = ?", productId) > 0L
        if (!exists) {
            if (edited == ProductLook()) return false
            LwwWriter.insert(tx, "product_look", Entity.PRODUCT_LOOK, productId, fields(edited), now)
            return true
        }
        val seen = fields(shown)
        val stored = fields(get(tx.db, productId))
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(edited)) if (seen[k] != v && stored[k] != v) changes[k] = v
        if (changes.isEmpty()) return false
        return LwwWriter.update(tx, "product_look", Entity.PRODUCT_LOOK, productId, changes, now)
    }

    private val IMAGE_COLS = arrayOf("id", "product_id", "data", "staff_id", "at", "hlc")
    private const val INSERT_IMAGE = "INSERT INTO product_image(id, product_id, data, staff_id, at, hlc) VALUES(?,?,?,?,?,?)"

    /** Stores a picture of [productId] ([data]: a JPEG as base64) with its sync event; returns its id. */
    fun addImage(tx: Db.Tx, productId: Long, data: String, staffId: Long?, now: Long): Long {
        require(data.isNotEmpty()) { "empty picture" }
        val id = tx.nextId()
        val hlc = tx.hlcNow()
        val values = arrayOf<Any?>(id, productId, data, staffId, now, hlc)
        tx.insert(INSERT_IMAGE, *values)
        if (tx.syncEnabled) {
            Outbox.append(tx, Entity.PRODUCT_IMAGE, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, IMAGE_COLS, values) })
        }
        return id
    }

    /** The picture [imageId] (base64 JPEG), or null while it has not arrived from the till that took it. */
    fun imageData(db: SQLiteDatabase, imageId: Long): String? =
        db.stringOrNull("SELECT data FROM product_image WHERE id = ?", imageId)
}
