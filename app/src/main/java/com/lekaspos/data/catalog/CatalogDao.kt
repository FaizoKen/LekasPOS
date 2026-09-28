package com.lekaspos.data.catalog

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter

data class Category(val id: Long, val name: String, val color: Int = 0, val sort: Int = 0)

data class TaxRate(val id: Long, val name: String, val code: String?, val rateBp: Int)

data class PaymentMethod(val id: Long, val name: String, val kind: Int, val opensDrawer: Boolean, val sort: Int)

/** LWW table `category`. */
object CategoryDao {
    fun list(db: SQLiteDatabase): List<Category> = db.queryList(
        "SELECT id, name, color, sort FROM category WHERE deleted = 0 ORDER BY sort, name_key",
    ) { c -> Category(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3)) }

    fun insert(tx: Db.Tx, name: String, color: Int, sort: Int, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(
            tx, "category", Entity.CATEGORY, id,
            linkedMapOf("name" to name, "name_key" to SearchText.key(name), "color" to color, "sort" to sort), now,
        )
        return id
    }

    fun update(tx: Db.Tx, id: Long, name: String, color: Int, sort: Int, now: Long): Boolean =
        LwwWriter.update(
            tx, "category", Entity.CATEGORY, id,
            linkedMapOf("name" to name, "name_key" to SearchText.key(name), "color" to color, "sort" to sort), now,
        )

    fun delete(tx: Db.Tx, id: Long, now: Long): Boolean = LwwWriter.delete(tx, "category", Entity.CATEGORY, id, now)
}

/** LWW table `tax_rate`. */
object TaxRateDao {
    fun list(db: SQLiteDatabase): List<TaxRate> = db.queryList(
        "SELECT id, name, code, rate_bp FROM tax_rate WHERE deleted = 0 ORDER BY rate_bp, name",
    ) { c -> TaxRate(c.getLong(0), c.getString(1), c.stringOrNull(2), c.getInt(3)) }

    fun get(db: SQLiteDatabase, id: Long): TaxRate? = db.queryOne(
        "SELECT id, name, code, rate_bp FROM tax_rate WHERE id = ?", args(id),
    ) { c -> TaxRate(c.getLong(0), c.getString(1), c.stringOrNull(2), c.getInt(3)) }

    fun insert(tx: Db.Tx, name: String, code: String?, rateBp: Int, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(tx, "tax_rate", Entity.TAX_RATE, id, linkedMapOf("name" to name, "code" to code, "rate_bp" to rateBp), now)
        return id
    }

    fun update(tx: Db.Tx, id: Long, name: String, code: String?, rateBp: Int, now: Long): Boolean =
        LwwWriter.update(tx, "tax_rate", Entity.TAX_RATE, id, linkedMapOf("name" to name, "code" to code, "rate_bp" to rateBp), now)

    fun delete(tx: Db.Tx, id: Long, now: Long): Boolean = LwwWriter.delete(tx, "tax_rate", Entity.TAX_RATE, id, now)
}

/** LWW table `payment_method` (seed rows: cash, card, e-wallet, customer credit). */
object PaymentMethodDao {
    fun active(db: SQLiteDatabase): List<PaymentMethod> = db.queryList(
        "SELECT id, name, kind, opens_drawer, sort FROM payment_method WHERE deleted = 0 AND active = 1 ORDER BY sort, id",
    ) { c -> PaymentMethod(c.getLong(0), c.getString(1), c.getInt(2), c.bool(3), c.getInt(4)) }
}

/** LWW table `staff` (PIN login and roles arrive in Phase 4). */
object StaffDao {
    fun name(db: SQLiteDatabase, id: Long): String? =
        db.queryOne("SELECT name FROM staff WHERE id = ?", args(id)) { it.getString(0) }
}
