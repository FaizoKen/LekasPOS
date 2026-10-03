package com.lekaspos.data.sync

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.sync.FieldVersions
import com.lekaspos.core.sync.Lww
import com.lekaspos.core.sync.Version
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.Summaries
import com.lekaspos.data.stock.StockDao

/** One event of a sync segment: what the outbox row held on the till that wrote it. */
class SyncEvent(val entity: Int, val op: Int, val rowId: Long?, val hlc: Long, val payload: Map<String, Any?>)

/**
 * Applies events from other tills (references/sync.md §4, D-045). Every rule is idempotent and
 * order-independent: EVENT rows are inserted once (derived data changes only when a row is new),
 * LWW rows merge field by field on (hlc, device). Table and column names come from this code or
 * are checked against the table's real columns — never taken blindly from a file.
 */
class Importer(private val db: SQLiteDatabase) {

    private class Column(val name: String, val type: String, val notNull: Boolean, val hasDefault: Boolean)

    private val columns = HashMap<String, Map<String, Column>>()

    private fun columnsOf(table: String): Map<String, Column> = columns.getOrPut(table) {
        db.queryList("PRAGMA table_info($table)", null) { c ->
            Column(c.getString(1), c.getString(2) ?: "", c.getInt(3) != 0, !c.isNull(4))
        }.associateBy { it.name }
    }

    /** Whether this version can apply events of [entity]; others are kept (sync_deferred, D-047). */
    fun knows(entity: Int): Boolean = entity in EVENT_ENTITIES || entity in LWW_TABLES

    /** Applies [e]; returns true when it changed something. Must run inside Db.write. */
    fun apply(tx: Db.Tx, e: SyncEvent): Boolean {
        val p = e.payload
        return when (e.entity) {
            Entity.SETTING -> setting(tx, p)
            Entity.SALE -> SaleDao.applyRemote(tx, map(p["sale"]), list(p["lines"]), list(p["pays"]))
            Entity.SALE_VOID -> SaleDao.applyRemoteVoid(tx, p)
            Entity.STOCK_MOVE -> StockDao.insertMovementRowIfNew(tx, row(MOVE_COLS, p))
            Entity.STOCK_COUNT -> insert(tx, "stock_count", p).also { new -> if (new) StockDao.rebuild(tx, p["product_id"] as Long) }
            Entity.PURCHASE -> purchase(tx, map(p["purchase"]), list(p["lines"]))
            Entity.CASH_MOVE -> insert(tx, "cash_movement", p)
            Entity.AUDIT -> insert(tx, "audit_log", p)
            Entity.CREDIT -> insert(tx, "credit_entry", p).also { new ->
                if (new) CustomerDao.applyBalance(tx, p["customer_id"] as Long, CreditMath.delta((p["kind"] as Long).toInt(), p["amount"] as Long))
            }
            else -> {
                val table = LWW_TABLES[e.entity] ?: return false // an entity from a newer version: skipped
                lww(tx, table, p)
            }
        }
    }

    // ------------------------------------------------------------------ LWW

    /** Merges one LWW change: each field wins only if its version is newer than what is here. */
    private fun lww(tx: Db.Tx, table: String, p: Map<String, Any?>): Boolean {
        val id = p["id"] as Long
        val incoming = Version(p["hlc"] as Long, (p["dev"] as Long).toInt())
        val cols = columnsOf(table)
        val fields = map(p["f"]).filterKeys { it in cols && it !in NOT_FIELDS }
        if (fields.isEmpty()) return false
        val now = System.currentTimeMillis()
        val current = db.queryOne("SELECT ver_hlc, ver_dev, fver FROM $table WHERE id = ?", args(id)) { c ->
            Pair(Version(c.getLong(0), c.getInt(1)), FieldVersions.decode(c.stringOrNull(2)))
        }
        if (current == null) {
            // A creation carries created_at; a change can arrive before its creation (from a third till).
            val creation = "created_at" in fields
            val values = LinkedHashMap<String, Any?>(fields)
            for (c in cols.values) {
                if (c.name in values || c.name == "id" || !c.notNull || c.hasDefault) continue
                values[c.name] = if (c.type.equals("TEXT", ignoreCase = true)) "" else 0L
            }
            values["updated_at"] = now
            if ("created_at" !in values) values["created_at"] = now
            val base = if (creation) incoming else Version(0L, 0)
            values["ver_hlc"] = base.hlc
            values["ver_dev"] = base.dev.toLong()
            values["fver"] = if (creation) null else FieldVersions.decode(null).with(fields.keys, incoming).encode()
            val names = listOf("id") + values.keys
            db.execSQL(
                "INSERT INTO $table(${names.joinToString(", ")}) VALUES(${names.joinToString(",") { "?" }})",
                (listOf<Any?>(id) + values.values).toTypedArray(),
            )
            after(tx, table, id, fields.keys, created = true)
            return true
        }
        val (base, versions) = current
        val winners = Lww.winningFields(fields.keys, incoming, base, versions).toMutableList()
        // Built-in rows (Cash, Owner, Manager …) are made by every till in its own language, all at
        // version (0, 0): the same version never wins, so a till set up in English kept "Cash" and one
        // in Malay "Tunai" for good (2026-10 review). At that tie the larger name wins on every till.
        val theirs = fields["name"] as? String // only tables with a name column carry one
        if (incoming == SEED && theirs != null && "name" !in winners && versions.of("name", base) == SEED) {
            val ours = db.queryOne("SELECT name FROM $table WHERE id = ?", args(id)) { it.stringOrNull(0) }
            if (ours != null && theirs > ours) winners.add("name")
        }
        if (winners.isEmpty()) return false
        val fver = versions.with(winners, incoming).encode()
        val bind = ArrayList<Any?>(winners.size + 3)
        for (w in winners) bind.add(fields[w])
        bind.add(fver)
        bind.add(now)
        bind.add(id)
        val oldCategory = if (table == "product" && "category_id" in winners) category(id) else null
        db.execSQL("UPDATE $table SET ${winners.joinToString(", ") { "$it = ?" }}, fver = ?, updated_at = ? WHERE id = ?", bind.toTypedArray())
        after(tx, table, id, winners, oldCategory = oldCategory)
        return true
    }

    private val SEED = Version(0L, 0)

    private fun after(tx: Db.Tx, table: String, id: Long, changed: Collection<String>, created: Boolean = false, oldCategory: Long? = null) {
        if (table != "product") return
        if ("name" in changed || "sku" in changed || "deleted" in changed) ProductDao.reindex(tx, id)
        // Its sales follow it to its category in the reports' category totals — also sales that
        // arrived before the product itself (D-058).
        if (created || "category_id" in changed) Summaries.recategorize(tx, id, oldCategory, category(id), hadRow = !created)
    }

    private fun category(productId: Long): Long? =
        db.queryOne("SELECT category_id FROM product WHERE id = ?", args(productId)) { c -> if (c.isNull(0)) null else c.getLong(0) }

    private fun setting(tx: Db.Tx, p: Map<String, Any?>): Boolean {
        val key = p["key"] as String
        val incoming = Version(p["hlc"] as Long, (p["dev"] as Long).toInt())
        val current = db.queryOne("SELECT ver_hlc, ver_dev FROM setting WHERE key = ?", args(key)) { Version(it.getLong(0), it.getInt(1)) }
        if (current != null && incoming <= current) return false
        val now = System.currentTimeMillis()
        val values = arrayOf<Any?>(p["value"], now, incoming.hlc, incoming.dev, key)
        tx.updateOrInsert(
            "UPDATE setting SET value = ?, updated_at = ?, ver_hlc = ?, ver_dev = ? WHERE key = ?", values,
            "INSERT INTO setting(value, updated_at, ver_hlc, ver_dev, key) VALUES(?, ?, ?, ?, ?)", values,
        )
        return true
    }

    // ------------------------------------------------------------------ EVENT rows

    /** INSERT OR IGNORE of one row with the columns the table really has. Returns true if new. */
    private fun insert(tx: Db.Tx, table: String, row: Map<String, Any?>): Boolean {
        val cols = columnsOf(table)
        val names = row.keys.filter { it in cols }
        if ("id" !in names) return false
        val sql = "INSERT OR IGNORE INTO $table(${names.joinToString(", ")}) VALUES(${names.joinToString(",") { "?" }})"
        return tx.insert(sql, *names.map { row[it] }.toTypedArray()) != -1L
    }

    /** A delivery: purchase and lines once, and its RECEIVE movements regenerated with the line ids (D-036). */
    private fun purchase(tx: Db.Tx, purchase: Map<String, Any?>, lines: List<Map<String, Any?>>): Boolean {
        if (!insert(tx, "purchase", purchase)) return false
        val id = purchase["id"] as Long
        for (l in lines) {
            insert(tx, "purchase_line", l)
            StockDao.insertMovementRowIfNew(
                tx,
                arrayOf<Any?>(
                    l["id"], l["product_id"], MovementKind.RECEIVE.toLong(), l["qty"], l["unit_cost"], id, null,
                    purchase["staff_id"], purchase["at"], purchase["hlc"],
                ),
            )
        }
        return true
    }

    private fun row(cols: Array<String>, p: Map<String, Any?>): Array<Any?> = Array(cols.size) { p[cols[it]] }

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Map<String, Any?>> = (v as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    companion object {
        /** LWW entity → table (references/database.md §4). */
        val LWW_TABLES: Map<Int, String> = mapOf(
            Entity.ROLE to "role",
            Entity.STAFF to "staff",
            Entity.TAX_RATE to "tax_rate",
            Entity.CATEGORY to "category",
            Entity.PRODUCT to "product",
            Entity.BARCODE to "product_barcode",
            Entity.SUPPLIER to "supplier",
            Entity.CUSTOMER to "customer",
            Entity.PAYMENT_METHOD to "payment_method",
            Entity.SHIFT to "shift",
            Entity.COUNT_SESSION to "count_session",
            Entity.PROMOTION to "promotion",
        )

        private val EVENT_ENTITIES = setOf(
            Entity.SETTING, Entity.SALE, Entity.SALE_VOID, Entity.STOCK_MOVE, Entity.STOCK_COUNT, Entity.PURCHASE,
            Entity.CASH_MOVE, Entity.AUDIT, Entity.CREDIT,
        )

        /** Columns that are versions or local bookkeeping, never fields of a change. */
        val NOT_FIELDS = setOf("id", "ver_hlc", "ver_dev", "fver", "updated_at")

        /** Every entity kind this version applies (the rest waits in `sync_deferred`). */
        fun knownEntities(): Set<Int> = EVENT_ENTITIES + LWW_TABLES.keys

        /** Column order of `stock_movement` rows (as StockDao writes them). */
        val MOVE_COLS = arrayOf("id", "product_id", "kind", "qty", "unit_cost", "ref_id", "reason", "staff_id", "at", "hlc")
    }
}
