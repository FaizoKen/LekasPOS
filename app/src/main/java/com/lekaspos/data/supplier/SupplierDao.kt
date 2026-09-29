package com.lekaspos.data.supplier

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter

data class Supplier(
    val id: Long,
    val name: String,
    val contact: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val note: String? = null,
)

/** LWW table `supplier`: a simple supplier list (Phase 3). */
object SupplierDao {
    private const val COLUMNS = "id, name, contact, phone, email, address, note"
    private const val LIST = "SELECT $COLUMNS FROM supplier WHERE deleted = 0 ORDER BY name_key, id LIMIT ?"

    fun list(db: SQLiteDatabase, limit: Int = 500): List<Supplier> = db.queryList(LIST, args(limit), ::row)

    fun get(db: SQLiteDatabase, id: Long): Supplier? =
        db.queryOne("SELECT $COLUMNS FROM supplier WHERE id = ?", args(id), ::row)

    fun fields(s: Supplier): LinkedHashMap<String, Any?> = linkedMapOf(
        "name" to s.name,
        "name_key" to SearchText.key(s.name),
        "contact" to s.contact,
        "phone" to s.phone,
        "email" to s.email,
        "address" to s.address,
        "note" to s.note,
    )

    fun insert(tx: Db.Tx, s: Supplier, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(tx, "supplier", Entity.SUPPLIER, id, fields(s), now)
        return id
    }

    /** Writes only the fields that changed. */
    fun update(tx: Db.Tx, before: Supplier, after: Supplier, now: Long) {
        val old = fields(before)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(after)) if (old[k] != v) changes[k] = v
        if (changes.isNotEmpty()) LwwWriter.update(tx, "supplier", Entity.SUPPLIER, before.id, changes, now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "supplier", Entity.SUPPLIER, id, now)
    }

    private fun row(c: Cursor) = Supplier(
        c.getLong(0), c.getString(1), c.stringOrNull(2), c.stringOrNull(3), c.stringOrNull(4), c.stringOrNull(5), c.stringOrNull(6),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf("supplier_list" to LIST)
}
