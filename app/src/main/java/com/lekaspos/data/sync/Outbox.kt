package com.lekaspos.data.sync

import android.util.JsonWriter
import com.lekaspos.data.db.Db
import java.io.StringWriter

/**
 * Local queue of this device's sync events (LOCAL table `outbox`, references/sync.md §4).
 * Appended in the same transaction as the change, and only while sync is enabled (D-016).
 */
object Outbox {
    private const val INSERT = "INSERT INTO outbox(hlc, entity, op, row_id, payload) VALUES(?,?,?,?,?)"

    fun append(tx: Db.Tx, entity: Int, op: Int, rowId: Long?, hlc: Long, payload: String) {
        tx.insert(INSERT, hlc, entity, op, rowId, payload)
        tx.outboxQueued()
    }

    /** Builds a JSON payload with the android.util streaming writer (no JSON library). */
    inline fun json(build: (JsonWriter) -> Unit): String {
        val out = StringWriter(256)
        JsonWriter(out).use { build(it) }
        return out.toString()
    }

    /** Writes one table row as a JSON object: column name → value (booleans as 0/1 like SQLite). */
    fun writeRow(w: JsonWriter, columns: Array<String>, values: Array<Any?>) {
        require(columns.size == values.size) { "columns/values mismatch" }
        w.beginObject()
        for (i in columns.indices) {
            w.name(columns[i])
            when (val v = values[i]) {
                null -> w.nullValue()
                is Long -> w.value(v)
                is Int -> w.value(v.toLong())
                is Boolean -> w.value(if (v) 1L else 0L)
                is String -> w.value(v)
                else -> w.value(v.toString())
            }
        }
        w.endObject()
    }
}
