package com.lekaspos.data.sync

import com.lekaspos.core.model.EventOp
import com.lekaspos.core.sync.FieldVersions
import com.lekaspos.core.sync.Lww
import com.lekaspos.core.sync.Version
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull

/**
 * Local writes to LWW tables (references/database.md §4). A new row's base version is the
 * write's HLC; an edit stamps only the changed fields in `fver`, so concurrent edits of
 * different fields on two devices both survive the merge. Both append an outbox event while
 * sync is enabled. Table and column names are code constants, never user input.
 */
object LwwWriter {

    fun insert(tx: Db.Tx, table: String, entity: Int, id: Long, fields: Map<String, Any?>, now: Long) {
        val hlc = tx.hlcNow()
        val cols = ArrayList<String>(fields.size + 6)
        val values = ArrayList<Any?>(fields.size + 6)
        cols.add("id")
        values.add(id)
        for ((k, v) in fields) {
            cols.add(k)
            values.add(sqlValue(v))
        }
        cols.addAll(listOf("deleted", "created_at", "updated_at", "ver_hlc", "ver_dev"))
        values.addAll(listOf(0L, now, now, hlc, tx.deviceNo.toLong()))
        tx.db.execSQL(
            "INSERT INTO $table(${cols.joinToString(", ")}) VALUES(${cols.joinToString(",") { "?" }})",
            values.toTypedArray(),
        )
        if (tx.syncEnabled) {
            val all = LinkedHashMap<String, Any?>(fields)
            all["deleted"] = 0L
            all["created_at"] = now
            Outbox.append(tx, entity, EventOp.LWW, id, hlc, payload(id, hlc, tx.deviceNo, all))
        }
    }

    /** Applies [changes] (column → new value) to row [id]. Returns false if the row does not exist. */
    fun update(tx: Db.Tx, table: String, entity: Int, id: Long, changes: Map<String, Any?>, now: Long): Boolean {
        val current = tx.db.queryOne("SELECT ver_hlc, ver_dev, fver FROM $table WHERE id = ?", args(id)) { c ->
            Version(c.getLong(0), c.getInt(1)) to FieldVersions.decode(c.stringOrNull(2))
        } ?: return false
        if (changes.isEmpty()) return true
        val (base, versions) = current
        // Above what the changed fields hold (2026-10 review): with this till's clock behind the
        // one that wrote them, a plain hlcNow() would be kept here but rejected everywhere else.
        val hlc = Lww.stampAbove(tx.hlcNow(), changes.keys.maxOf { versions.of(it, base).hlc })
        val fver = versions.with(changes.keys, Version(hlc, tx.deviceNo)).encode()
        val bind = ArrayList<Any?>(changes.size + 3)
        for (v in changes.values) bind.add(sqlValue(v))
        bind.add(fver)
        bind.add(now)
        bind.add(id)
        tx.db.execSQL(
            "UPDATE $table SET ${changes.keys.joinToString(", ") { "$it = ?" }}, fver = ?, updated_at = ? WHERE id = ?",
            bind.toTypedArray(),
        )
        if (tx.syncEnabled) Outbox.append(tx, entity, EventOp.LWW, id, hlc, payload(id, hlc, tx.deviceNo, changes))
        return true
    }

    /** Tombstone: deletion is the LWW field `deleted = 1`; the row stays for history. */
    fun delete(tx: Db.Tx, table: String, entity: Int, id: Long, now: Long): Boolean =
        update(tx, table, entity, id, mapOf("deleted" to 1L), now)

    private fun sqlValue(v: Any?): Any? = when (v) {
        is Boolean -> if (v) 1L else 0L
        is Int -> v.toLong()
        else -> v
    }

    private fun payload(id: Long, hlc: Long, dev: Int, fields: Map<String, Any?>): String = Outbox.json { w ->
        w.beginObject()
        w.name("id").value(id)
        w.name("hlc").value(hlc)
        w.name("dev").value(dev.toLong())
        w.name("f")
        Outbox.writeRow(w, fields.keys.toTypedArray(), Array(fields.size) { i -> sqlValue(fields.values.elementAt(i)) })
        w.endObject()
    }
}
