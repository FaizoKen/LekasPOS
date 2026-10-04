package com.lekaspos.data.sync

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.SeedNames
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.settings.SettingKeys
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

    /**
     * Events waiting in the outbox. It only grows at its end and is only emptied from its start (by
     * seq), so the first and last seq say it: COUNT(*) read every page of it, and after weeks without
     * internet (a busy till's 50 MB of sale events) that ran after every sale (2026-10 review). Two
     * subqueries: SQLite reads a lone MIN or MAX from the end of the key, but `MAX - MIN` scans.
     */
    fun outboxCount(db: SQLiteDatabase): Long =
        db.long("SELECT COALESCE((SELECT MAX(seq) FROM outbox) - (SELECT MIN(seq) FROM outbox) + 1, 0)")

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

    /**
     * Segments not uploaded yet. Uploads go in order and stop at the first failure, so these are
     * exactly the ones after the last uploaded: a range of the primary key, however long the
     * history (a year is some 70,000 segments).
     */
    fun unsent(db: SQLiteDatabase): List<SegmentRow> = db.queryList(
        "SELECT seq, count, first_hlc, last_hlc, size, sha256, created_at, uploaded_at FROM sync_segment WHERE seq > ? ORDER BY seq",
        args(lastUploaded(db)),
        ::segment,
    )

    /** Up to [limit] segments after [afterSeq], in order. */
    fun segmentsAfter(db: SQLiteDatabase, afterSeq: Long, limit: Int): List<SegmentRow> = db.queryList(
        "SELECT seq, count, first_hlc, last_hlc, size, sha256, created_at, uploaded_at FROM sync_segment WHERE seq > ? ORDER BY seq LIMIT ?",
        args(afterSeq, limit),
        ::segment,
    )

    /** The newest uploaded segment: read backwards from the newest, it stops at the first uploaded one. */
    fun lastUploaded(db: SQLiteDatabase): Long =
        db.long("SELECT COALESCE((SELECT seq FROM sync_segment WHERE uploaded_at IS NOT NULL ORDER BY seq DESC LIMIT 1), 0)")

    fun markUploaded(tx: Db.Tx, seq: Long, at: Long) {
        tx.update("UPDATE sync_segment SET uploaded_at = ? WHERE seq = ?", at, seq)
    }

    fun unsentEvents(db: SQLiteDatabase): Long =
        outboxCount(db) + db.long("SELECT COALESCE(SUM(count), 0) FROM sync_segment WHERE seq > ?", lastUploaded(db))

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

    fun dropCursor(tx: Db.Tx, dev: Int) {
        tx.update("DELETE FROM sync_cursor WHERE dev = ?", dev)
    }

    /**
     * This till publishes into a folder that has none of its files: its file numbers start again
     * at 1, nothing is waiting from the old folder, and what it read of other tills there no
     * longer counts. (Its data is published again in full by the backfill.)
     */
    fun restartPublishing(tx: Db.Tx) {
        tx.update("DELETE FROM sync_segment")
        tx.update("DELETE FROM sync_cursor")
        tx.update("DELETE FROM outbox")
    }

    /**
     * This till joins a store that already exists: its own settings lose against the store's
     * (2026-10 review). A new till that ran the first-run setup holds default store settings with
     * newer versions than the store's real ones, and published them over every till's receipt
     * header, BRN, SST number and tax switch. Base 0 puts them below every real edit; the device
     * number keeps the order total, so the tills still agree on keys nobody else has set.
     */
    fun yieldSettings(tx: Db.Tx) {
        tx.update("UPDATE setting SET ver_hlc = 0 WHERE ver_hlc > 0")
    }

    /**
     * A new till — no products, no sales — joins a store that already exists: the store's staff and
     * PINs apply here (2026-10 review). Its first-run setup offers "Staff and PINs" before "Join", and
     * an owner PIN set there was newer than the store's, so it became the owner's PIN on every till,
     * while the recovery code shown with it never worked (the store's stayed). So the PINs set here
     * before joining are dropped, with that recovery code, and the built-in rows (the owner, the roles,
     * the payment methods) are made again as every till first makes them, in [names]: at base (0, 0)
     * they lose to every real edit of the store. (Kept with this till's own values at (0, 0) — a method
     * switched off at setup — they differed from a store that never edited them, for good.) Returns
     * true when this till had a PIN of its own. Before the backfill: nothing of this till's own goes out.
     */
    fun yieldStaff(tx: Db.Tx, names: SeedNames): Boolean {
        val hadPins = tx.db.long("SELECT COUNT(*) FROM staff WHERE pin_hash IS NOT NULL") > 0L
        tx.update("UPDATE staff SET pin_hash = NULL WHERE pin_hash IS NOT NULL")
        tx.update("DELETE FROM setting WHERE key = ?", SettingKeys.OWNER_RECOVERY)
        for (table in SEED_TABLES) tx.update("DELETE FROM $table WHERE id < ?", SEED_ID_END)
        Seed.insert(tx.db, names, System.currentTimeMillis())
        return hadPins
    }

    private val SEED_TABLES = listOf("staff", "role", "payment_method")

    /** IDs below this are seed rows (device number 0), the same on every till. */
    private const val SEED_ID_END = 1L shl 41

    // Events of kinds this version cannot apply yet (D-047): kept, applied after an update.

    fun defer(tx: Db.Tx, e: SyncEvent) {
        val payload = Outbox.json { w -> SegmentCodec.writeValue(w, e.payload) }
        tx.insert(
            "INSERT INTO sync_deferred(entity, op, row_id, hlc, payload) VALUES(?, ?, ?, ?, ?)",
            e.entity, e.op, e.rowId, e.hlc, payload,
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun deferred(db: SQLiteDatabase): List<Pair<Long, SyncEvent>> = db.queryList(
        "SELECT seq, entity, op, row_id, hlc, payload FROM sync_deferred ORDER BY seq", null,
    ) { c ->
        val payload = SegmentCodec.parse(c.getString(5)) as? Map<String, Any?> ?: emptyMap()
        c.getLong(0) to SyncEvent(c.getInt(1), c.getInt(2), c.longOrNull(3), c.getLong(4), payload)
    }

    /**
     * Waiting events of the kinds in [entities] (codes this version applies), after [afterSeq], at most
     * [limit]: the whole table was read and parsed at once every round, tens of MB once a newer till's
     * unknown events piled up (2026-10 review).
     */
    @Suppress("UNCHECKED_CAST")
    fun deferredPage(db: SQLiteDatabase, entities: Set<Int>, afterSeq: Long, limit: Int): List<Pair<Long, SyncEvent>> {
        if (entities.isEmpty()) return emptyList()
        val kinds = entities.sorted().joinToString(",") // codes defined in :core, never user input
        return db.queryList(
            "SELECT seq, entity, op, row_id, hlc, payload FROM sync_deferred WHERE seq > ? AND entity IN ($kinds) ORDER BY seq LIMIT ?",
            args(afterSeq, limit),
        ) { c ->
            val payload = SegmentCodec.parse(c.getString(5)) as? Map<String, Any?> ?: emptyMap()
            c.getLong(0) to SyncEvent(c.getInt(1), c.getInt(2), c.longOrNull(3), c.getLong(4), payload)
        }
    }

    fun dropDeferred(tx: Db.Tx, seq: Long) {
        tx.update("DELETE FROM sync_deferred WHERE seq = ?", seq)
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
            w.close() // ends the Deflater; the file is already complete and synced
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
