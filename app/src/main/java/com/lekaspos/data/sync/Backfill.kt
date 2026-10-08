package com.lekaspos.data.sync

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.sync.FieldVersions
import com.lekaspos.core.sync.Version
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryList
import com.lekaspos.data.sale.SaleDao

/**
 * Publishing the data a till had before it joined sync (D-045): every existing row becomes an
 * ordinary outbox event, exactly as if it had just been written — LWW rows keep each field's own
 * version (one event per distinct version), sales travel with their lines and payments. Other
 * tills merge them with the same rules as any change, so two tills that were used on their own
 * can be joined. Duplicates are harmless (idempotent import). Runs in small transactions.
 */
object Backfill {

    private const val CHUNK = 300

    /**
     * Writes all events; [progress] gets (table, rows done) after each committed chunk (the caller
     * seals the outbox there now and then). Sync must already be enabled.
     */
    suspend fun run(db: Db, progress: suspend (String, Long) -> Unit = { _, _ -> }) {
        check(db.syncEnabled) { "enable sync before the backfill, or new changes would be missed" }
        settings(db)
        for ((entity, table) in Importer.LWW_TABLES.entries.sortedBy { order(it.value) }) {
            lwwTable(db, entity, table, progress)
        }
        sales(db, progress)
        rows(db, "sale_void", Entity.SALE_VOID, "", progress)
        rows(db, "stock_movement", Entity.STOCK_MOVE, " AND kind != ${MovementKind.RECEIVE}", progress) // RECEIVE rows travel with their PURCHASE
        rows(db, "stock_count", Entity.STOCK_COUNT, "", progress)
        purchases(db, progress)
        rows(db, "cash_movement", Entity.CASH_MOVE, "", progress)
        rows(db, "credit_entry", Entity.CREDIT, "", progress)
        rows(db, "audit_log", Entity.AUDIT, "", progress)
        // Pictures are ~13 KB each: a few per transaction, not a 4 MB page in memory (D-066).
        rows(db, "product_image", Entity.PRODUCT_IMAGE, "", progress, chunk = 20)
    }

    /** Parents before children, so a single importer sees roles before staff and products before barcodes. */
    private fun order(table: String) = listOf(
        "role", "staff", "tax_rate", "category", "payment_method", "supplier", "customer", "product", "product_barcode",
        "shift", "count_session", "promotion", "product_look",
    ).indexOf(table)

    private suspend fun settings(db: Db) {
        val rows = db.read { r -> r.queryList("SELECT key, value, ver_hlc, ver_dev FROM setting", null) { c -> listOf<Any?>(c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getLong(2), c.getLong(3)) } }
        db.write(reserveIds = 0L) { tx ->
            for (r in rows) {
                val hlc = r[2] as Long
                val payload = Outbox.json { w ->
                    w.beginObject()
                    w.name("key").value(r[0] as String)
                    w.name("value").value(r[1] as String?)
                    w.name("hlc").value(hlc)
                    w.name("dev").value(r[3] as Long)
                    w.endObject()
                }
                Outbox.append(tx, Entity.SETTING, EventOp.LWW, null, hlc, payload)
            }
        }
    }

    private suspend fun lwwTable(db: Db, entity: Int, table: String, progress: suspend (String, Long) -> Unit) {
        var after = Long.MIN_VALUE
        var done = 0L
        while (true) {
            val page = db.read { r -> page(r, "SELECT * FROM $table WHERE id > ? ORDER BY id LIMIT $CHUNK", after) }
            if (page.isEmpty()) break
            db.write(reserveIds = 0L) { tx ->
                for (row in page) {
                    val id = row["id"] as Long
                    val base = Version((row["ver_hlc"] as Long?) ?: 0L, ((row["ver_dev"] as Long?) ?: 0L).toInt())
                    val versions = FieldVersions.decode(row["fver"] as String?)
                    val groups = LinkedHashMap<Version, LinkedHashMap<String, Any?>>()
                    for ((k, v) in row) {
                        if (k in Importer.NOT_FIELDS) continue
                        groups.getOrPut(versions.of(k, base)) { LinkedHashMap() }[k] = v
                    }
                    for ((ver, fields) in groups.entries.sortedBy { it.key }) {
                        val payload = Outbox.json { w ->
                            w.beginObject()
                            w.name("id").value(id)
                            w.name("hlc").value(ver.hlc)
                            w.name("dev").value(ver.dev.toLong())
                            w.name("f")
                            SegmentCodec.writeValue(w, fields)
                            w.endObject()
                        }
                        Outbox.append(tx, entity, EventOp.LWW, id, ver.hlc, payload)
                    }
                }
            }
            done += page.size
            progress(table, done)
            after = page.last()["id"] as Long
        }
    }

    private suspend fun sales(db: Db, progress: suspend (String, Long) -> Unit) {
        var after = Long.MIN_VALUE
        var done = 0L
        while (true) {
            val ids = db.read { r -> r.queryList("SELECT id FROM sale WHERE id > ? ORDER BY id LIMIT $CHUNK", args(after)) { it.getLong(0) } }
            if (ids.isEmpty()) break
            val sales = db.read { r -> ids.mapNotNull { SaleDao.exportRows(r, it) } }
            db.write(reserveIds = 0L) { tx ->
                for ((sale, lines, pays) in sales) {
                    val payload = Outbox.json { w ->
                        w.beginObject()
                        w.name("sale")
                        SegmentCodec.writeValue(w, sale)
                        w.name("lines")
                        SegmentCodec.writeValue(w, lines)
                        w.name("pays")
                        SegmentCodec.writeValue(w, pays)
                        w.endObject()
                    }
                    Outbox.append(tx, Entity.SALE, EventOp.INSERT, sale["id"] as Long, sale["hlc"] as Long, payload)
                }
            }
            done += ids.size
            progress("sale", done)
            after = ids.last()
        }
    }

    private suspend fun purchases(db: Db, progress: suspend (String, Long) -> Unit) {
        var after = Long.MIN_VALUE
        var done = 0L
        while (true) {
            val page = db.read { r -> page(r, "SELECT * FROM purchase WHERE id > ? ORDER BY id LIMIT $CHUNK", after) }
            if (page.isEmpty()) break
            val lines = db.read { r -> page.associate { p -> (p["id"] as Long) to page(r, "SELECT * FROM purchase_line WHERE purchase_id = ? ORDER BY id", p["id"] as Long) } }
            db.write(reserveIds = 0L) { tx ->
                for (p in page) {
                    val payload = Outbox.json { w ->
                        w.beginObject()
                        w.name("purchase")
                        SegmentCodec.writeValue(w, p)
                        w.name("lines")
                        SegmentCodec.writeValue(w, lines[p["id"] as Long].orEmpty())
                        w.endObject()
                    }
                    Outbox.append(tx, Entity.PURCHASE, EventOp.INSERT, p["id"] as Long, p["hlc"] as Long, payload)
                }
            }
            done += page.size
            progress("purchase", done)
            after = page.last()["id"] as Long
        }
    }

    private suspend fun rows(db: Db, table: String, entity: Int, filter: String, progress: suspend (String, Long) -> Unit, chunk: Int = CHUNK) {
        var after = Long.MIN_VALUE
        var done = 0L
        while (true) {
            val page = db.read { r -> page(r, "SELECT * FROM $table WHERE id > ?$filter ORDER BY id LIMIT $chunk", after) }
            if (page.isEmpty()) break
            db.write(reserveIds = 0L) { tx ->
                for (row in page) {
                    Outbox.append(tx, entity, EventOp.INSERT, row["id"] as Long, row["hlc"] as Long, Outbox.json { w -> SegmentCodec.writeValue(w, row) })
                }
            }
            done += page.size
            progress(table, done)
            after = page.last()["id"] as Long
        }
    }

    private fun page(r: SQLiteDatabase, sql: String, arg: Long): List<Map<String, Any?>> = r.queryList(sql, args(arg)) { c -> map(c) }

    private fun map(c: Cursor): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(c.columnCount)
        for (i in 0 until c.columnCount) {
            m[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                else -> c.getString(i)
            }
        }
        return m
    }
}
