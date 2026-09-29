package com.lekaspos.data.sync

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.StringReader
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** An outbox row waiting to be sealed into a segment. */
class OutboxRow(val seq: Long, val hlc: Long, val entity: Int, val op: Int, val rowId: Long?, val payload: String)

/** One of this till's sealed segments. */
data class SegmentRow(
    val seq: Long,
    val count: Int,
    val firstHlc: Long,
    val lastHlc: Long,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val uploadedAt: Long?,
)

/** LOCAL sync bookkeeping (D-045): the outbox, this till's segments, import cursors. */
object SyncDao {

    fun outboxBatch(db: SQLiteDatabase, limit: Int): List<OutboxRow> = db.queryList(
        "SELECT seq, hlc, entity, op, row_id, payload FROM outbox ORDER BY seq LIMIT ?", args(limit),
    ) { OutboxRow(it.getLong(0), it.getLong(1), it.getInt(2), it.getInt(3), it.longOrNull(4), it.getString(5)) }

    fun outboxCount(db: SQLiteDatabase): Long = db.long("SELECT COUNT(*) FROM outbox")

    fun deleteOutboxUpTo(tx: Db.Tx, seq: Long) {
        tx.update("DELETE FROM outbox WHERE seq <= ?", seq)
    }

    fun nextSegmentSeq(db: SQLiteDatabase): Long = db.long("SELECT COALESCE(MAX(seq), 0) FROM sync_segment") + 1L

    fun insertSegment(tx: Db.Tx, s: SegmentRow) {
        tx.insert(
            "INSERT INTO sync_segment(seq, count, first_hlc, last_hlc, size, sha256, created_at, uploaded_at) VALUES(?,?,?,?,?,?,?,?)",
            s.seq, s.count, s.firstHlc, s.lastHlc, s.size, s.sha256, s.createdAt, s.uploadedAt,
        )
    }

    fun unsent(db: SQLiteDatabase): List<SegmentRow> = db.queryList(
        "SELECT seq, count, first_hlc, last_hlc, size, sha256, created_at, uploaded_at FROM sync_segment " +
            "WHERE uploaded_at IS NULL ORDER BY seq",
        null,
        ::segment,
    )

    fun segments(db: SQLiteDatabase): List<SegmentRow> = db.queryList(
        "SELECT seq, count, first_hlc, last_hlc, size, sha256, created_at, uploaded_at FROM sync_segment ORDER BY seq",
        null,
        ::segment,
    )

    fun lastUploaded(db: SQLiteDatabase): Long = db.long("SELECT COALESCE(MAX(seq), 0) FROM sync_segment WHERE uploaded_at IS NOT NULL")

    fun markUploaded(tx: Db.Tx, seq: Long, at: Long) {
        tx.update("UPDATE sync_segment SET uploaded_at = ? WHERE seq = ?", at, seq)
    }

    fun unsentEvents(db: SQLiteDatabase): Long =
        outboxCount(db) + db.long("SELECT COALESCE(SUM(count), 0) FROM sync_segment WHERE uploaded_at IS NULL")

    fun cursors(db: SQLiteDatabase): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        db.queryList("SELECT dev, seq FROM sync_cursor", null) { it.getInt(0) to it.getLong(1) }.forEach { out[it.first] = it.second }
        return out
    }

    fun setCursor(tx: Db.Tx, dev: Int, seq: Long, lastHlc: Long, now: Long) {
        val v = arrayOf<Any?>(seq, lastHlc, now, dev)
        tx.updateOrInsert(
            "UPDATE sync_cursor SET seq = ?, last_hlc = ?, updated_at = ? WHERE dev = ?", v,
            "INSERT INTO sync_cursor(seq, last_hlc, updated_at, dev) VALUES(?, ?, ?, ?)", v,
        )
    }

    private fun segment(c: android.database.Cursor) = SegmentRow(
        c.getLong(0), c.getInt(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getString(5), c.getLong(6), c.longOrNull(7),
    )
}

/**
 * Segment files (references/sync.md §5): gzip NDJSON, a header line then one event per line,
 * each carrying the outbox row as it was written (`p` is that row's JSON payload, embedded as is).
 */
object SegmentCodec {

    data class Header(val v: Int, val store: String, val dev: Int, val seq: Long, val count: Int, val firstHlc: Long, val lastHlc: Long)

    class Corrupt(message: String) : Exception(message)

    /** Writes [rows] to [file] (fsynced); returns (size, sha256 hex). */
    fun write(file: File, h: Header, rows: List<OutboxRow>): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(file).use { fos ->
            val gz = GZIPOutputStream(DigestOutputStream(fos.buffered(64 * 1024), digest), 64 * 1024)
            val w = OutputStreamWriter(gz, Charsets.UTF_8)
            val head = Outbox.json { j ->
                j.beginObject()
                j.name("v").value(h.v.toLong())
                j.name("store").value(h.store)
                j.name("dev").value(h.dev.toLong())
                j.name("seq").value(h.seq)
                j.name("count").value(h.count.toLong())
                j.name("firstHlc").value(h.firstHlc)
                j.name("lastHlc").value(h.lastHlc)
                j.endObject()
            }
            w.write(head)
            w.write("\n")
            for (r in rows) {
                w.write("{\"e\":")
                w.write(r.entity.toString())
                w.write(",\"o\":")
                w.write(r.op.toString())
                w.write(",\"r\":")
                w.write(r.rowId?.toString() ?: "null")
                w.write(",\"h\":")
                w.write(r.hlc.toString())
                w.write(",\"p\":")
                w.write(r.payload) // JSON written by JsonWriter: safe to embed
                w.write("}\n")
            }
            w.flush()
            gz.finish()
            gz.flush()
            fos.flush()
            fos.fd.sync()
        }
        return file.length() to hex(digest.digest())
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    /** Reads [file]: the header is checked by [check] before any event; [each] gets every event. */
    fun read(file: File, check: (Header) -> Unit, each: (SyncEvent) -> Unit): Header {
        BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(file), 64 * 1024), Charsets.UTF_8), 64 * 1024).use { r ->
            val first = r.readLine() ?: throw Corrupt("empty segment")
            val h = header(first)
            check(h)
            var n = 0
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                each(event(line))
                n++
            }
            if (n != h.count) throw Corrupt("segment ${h.dev}/${h.seq}: $n events, header says ${h.count}")
            return h
        }
    }

    private fun header(line: String): Header {
        val m = parse(line) as? Map<*, *> ?: throw Corrupt("bad header")
        fun l(k: String) = (m[k] as? Long) ?: throw Corrupt("header without $k")
        return Header(l("v").toInt(), m["store"] as? String ?: throw Corrupt("header without store"), l("dev").toInt(), l("seq"), l("count").toInt(), l("firstHlc"), l("lastHlc"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun event(line: String): SyncEvent {
        val m = parse(line) as? Map<String, Any?> ?: throw Corrupt("bad event")
        return SyncEvent(
            (m["e"] as? Long)?.toInt() ?: throw Corrupt("event without entity"),
            (m["o"] as? Long)?.toInt() ?: 0,
            m["r"] as? Long,
            m["h"] as? Long ?: throw Corrupt("event without hlc"),
            m["p"] as? Map<String, Any?> ?: throw Corrupt("event without payload"),
        )
    }

    /** JSON text → Map / List / Long / Double / String / Boolean / null. */
    fun parse(text: String): Any? = JsonReader(StringReader(text)).use { value(it) }

    private fun value(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = value(r)
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l.add(value(r))
            r.endArray()
            l
        }
        JsonToken.NUMBER -> {
            val s = r.nextString()
            s.toLongOrNull() ?: s.toDouble()
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> {
            r.nextNull()
            null
        }
        else -> throw Corrupt("unexpected ${r.peek()}")
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    /** Writes a generic value (as [parse] returns it) with [w]. */
    fun writeValue(w: JsonWriter, v: Any?) {
        when (v) {
            null -> w.nullValue()
            is Map<*, *> -> {
                w.beginObject()
                for ((k, x) in v) {
                    w.name(k.toString())
                    writeValue(w, x)
                }
                w.endObject()
            }
            is List<*> -> {
                w.beginArray()
                for (x in v) writeValue(w, x)
                w.endArray()
            }
            is Long -> w.value(v)
            is Int -> w.value(v.toLong())
            is Boolean -> w.value(if (v) 1L else 0L)
            is Double -> w.value(v)
            else -> w.value(v.toString())
        }
    }
}
