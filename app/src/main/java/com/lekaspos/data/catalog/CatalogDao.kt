package com.lekaspos.data.catalog

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
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

    fun get(db: SQLiteDatabase, id: Long): Category? = db.queryOne(
        "SELECT id, name, color, sort FROM category WHERE id = ?", args(id),
    ) { c -> Category(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3)) }

    private fun fields(c: Category): Map<String, Any?> =
        linkedMapOf("name" to c.name, "name_key" to SearchText.key(c.name), "color" to c.color, "sort" to c.sort)

    /** Writes the fields that differ from the row as stored now. Returns false if the row does not exist. */
    fun update(tx: Db.Tx, id: Long, name: String, color: Int, sort: Int, now: Long): Boolean {
        val current = get(tx.db, id) ?: return false
        return update(tx, current, Category(id, name, color, sort), now)
    }

    /**
     * An edit made on a screen that showed [before]: writes only the fields the user changed
     * ([after] differs from [before]) that also differ from the row as stored now, so a field
     * another till changed meanwhile keeps that change. Returns false if the row does not exist.
     */
    fun update(tx: Db.Tx, before: Category, after: Category, now: Long): Boolean {
        require(before.id == after.id)
        val current = get(tx.db, after.id) ?: return false
        val changes = lwwChanges(fields(before), fields(after), fields(current))
        return LwwWriter.update(tx, "category", Entity.CATEGORY, after.id, changes, now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long): Boolean = LwwWriter.delete(tx, "category", Entity.CATEGORY, id, now)
}

/**
 * The fields of an LWW edit to write (references/database.md §4: only changed fields get a new
 * version): the entries of [after] that differ from [before] (what the editor showed) and from
 * [current] (the row as stored now).
 */
private fun lwwChanges(
    before: Map<String, Any?>,
    after: Map<String, Any?>,
    current: Map<String, Any?>,
): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in after) if (before[k] != v && current[k] != v) out[k] = v
    return out
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

    private fun fields(t: TaxRate): Map<String, Any?> =
        linkedMapOf("name" to t.name, "code" to t.code, "rate_bp" to t.rateBp)

    /** Writes the fields that differ from the row as stored now. Returns false if the row does not exist. */
    fun update(tx: Db.Tx, id: Long, name: String, code: String?, rateBp: Int, now: Long): Boolean {
        val current = get(tx.db, id) ?: return false
        return update(tx, current, TaxRate(id, name, code, rateBp), now)
    }

    /**
     * An edit made on a screen that showed [before]: writes only the fields the user changed that
     * also differ from the row as stored now (a rate another till changed meanwhile is kept).
     * Returns false if the row does not exist.
     */
    fun update(tx: Db.Tx, before: TaxRate, after: TaxRate, now: Long): Boolean {
        require(before.id == after.id)
        val current = get(tx.db, after.id) ?: return false
        val changes = lwwChanges(fields(before), fields(after), fields(current))
        return LwwWriter.update(tx, "tax_rate", Entity.TAX_RATE, after.id, changes, now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long): Boolean = LwwWriter.delete(tx, "tax_rate", Entity.TAX_RATE, id, now)
}

/** A payment method as Settings → Payment methods edits it (hidden ones included). */
data class PaymentMethodRow(
    val id: Long,
    val name: String,
    val kind: Int,
    val opensDrawer: Boolean,
    val sort: Int,
    val active: Boolean,
)

/**
 * LWW table `payment_method` (seed rows: cash, card, e-wallet, customer credit). The shop adds its
 * own (DuitNow QR, Touch 'n Go, bank transfer …): each is counted on its own in the shift and sales
 * reports (2026-10).
 */
object PaymentMethodDao {
    fun active(db: SQLiteDatabase): List<PaymentMethod> = db.queryList(
        "SELECT id, name, kind, opens_drawer, sort FROM payment_method WHERE deleted = 0 AND active = 1 ORDER BY sort, id",
    ) { c -> PaymentMethod(c.getLong(0), c.getString(1), c.getInt(2), c.bool(3), c.getInt(4)) }

    private const val ROW = "SELECT id, name, kind, opens_drawer, sort, active FROM payment_method"

    private fun row(c: android.database.Cursor) = PaymentMethodRow(c.getLong(0), c.getString(1), c.getInt(2), c.bool(3), c.getInt(4), c.bool(5))

    /** Every method, hidden ones too, in till order. */
    fun all(db: SQLiteDatabase): List<PaymentMethodRow> = db.queryList("$ROW WHERE deleted = 0 ORDER BY sort, id") { row(it) }

    fun get(db: SQLiteDatabase, id: Long): PaymentMethodRow? = db.queryOne("$ROW WHERE id = ?", args(id)) { row(it) }

    /** A new method after the others. */
    fun insert(tx: Db.Tx, name: String, kind: Int, opensDrawer: Boolean, now: Long, active: Boolean = true): Long {
        val id = tx.nextId()
        val sort = tx.db.long("SELECT COALESCE(MAX(sort), 0) FROM payment_method") + 1L
        val fields = linkedMapOf<String, Any?>(
            "name" to name, "kind" to kind.toLong(), "opens_drawer" to if (opensDrawer) 1L else 0L, "sort" to sort,
            "active" to if (active) 1L else 0L,
        )
        LwwWriter.insert(tx, "payment_method", Entity.PAYMENT_METHOD, id, fields, now)
        return id
    }

    /** Some sale was paid with [id] (the day totals: small, unlike `payment`). */
    fun used(db: SQLiteDatabase, id: Long): Boolean =
        db.long("SELECT COUNT(*) FROM (SELECT 1 FROM sum_day_payment WHERE method_id = ? LIMIT 1)", id) > 0L

    /** A method by id, hidden ones too, as the payment screens take it. */
    fun method(db: SQLiteDatabase, id: Long): PaymentMethod? =
        get(db, id)?.let { PaymentMethod(it.id, it.name, it.kind, it.opensDrawer, it.sort) }

    private fun fields(m: PaymentMethodRow): Map<String, Any?> = linkedMapOf(
        "name" to m.name, "kind" to m.kind.toLong(), "opens_drawer" to if (m.opensDrawer) 1L else 0L,
        "sort" to m.sort.toLong(), "active" to if (m.active) 1L else 0L,
    )

    /** Writes the fields the user changed from [before] that also differ from the row as stored now. */
    fun update(tx: Db.Tx, before: PaymentMethodRow, after: PaymentMethodRow, now: Long): Boolean {
        require(before.id == after.id)
        val current = get(tx.db, after.id) ?: return false
        val changes = lwwChanges(fields(before), fields(after), fields(current))
        return LwwWriter.update(tx, "payment_method", Entity.PAYMENT_METHOD, after.id, changes, now)
    }

    /** Every method's name, including removed ones (old reports still show them). */
    fun names(db: SQLiteDatabase): Map<Long, String> {
        val out = HashMap<Long, String>()
        db.queryList("SELECT id, name FROM payment_method", null) { it.getLong(0) to it.getString(1) }.forEach { out[it.first] = it.second }
        return out
    }
}

