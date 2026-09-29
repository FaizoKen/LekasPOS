package com.lekaspos.data.backup

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonWriter
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.Schema
import com.lekaspos.data.db.pragma
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The `.lekasbak` backup file (D-044): a ZIP with `backup.json` (what it is) and the SQLite
 * database file(s), taken consistently by checkpointing the WAL and copying the file on the
 * writer thread — nothing can write meanwhile, reads and the UI carry on. Everything is kept,
 * including this till's identity, so a restore can take over this device or become a new one.
 */
object BackupFiles {

    const val EXT = ".lekasbak"
    private const val FORMAT = 1
    private const val HEADER = "backup.json"
    private const val DB = "lekaspos.db"
    private const val WAL = "lekaspos.db-wal"

    data class Header(
        val format: Int,
        val createdAt: Long,
        val reason: String,
        val storeUuid: String,
        val deviceUuid: String,
        val deviceNo: Int,
        val schema: Int,
        val appVersion: String,
        val storeName: String?,
        val sales: Long,
        val products: Long,
    )

    class Invalid(message: String) : Exception(message)

    /** Writes a backup of the open [db] to [out]; [temp] is a scratch directory. */
    fun write(db: Db, out: OutputStream, temp: File, appVersion: String, reason: String) {
        temp.mkdirs()
        val dbCopy = File(temp, "copy.db")
        val walCopy = File(temp, "copy.db-wal")
        walCopy.delete()
        // Under the writer: fold the WAL into the file, then copy it (and the WAL if readers
        // kept part of it from being folded in). Only file copies happen while writes wait.
        val header = db.onWriterThread { sqlite ->
            val complete = checkpoint(sqlite)
            copy(db.file, dbCopy)
            val wal = File(db.file.path + "-wal")
            if (!complete && wal.exists() && wal.length() > 0L) copy(wal, walCopy)
            header(sqlite, System.currentTimeMillis(), appVersion, reason)
        }
        try {
            zip(out, header, dbCopy, walCopy.takeIf { it.exists() })
        } finally {
            dbCopy.delete()
            walCopy.delete()
        }
    }

    /** Backs up database files that are not open (before an upgrade or a restore replaces them). */
    fun writeClosed(dbFile: File, out: OutputStream, appVersion: String, reason: String) {
        val wal = File(dbFile.path + "-wal").takeIf { it.exists() && it.length() > 0L }
        val header = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { r ->
            header(r, System.currentTimeMillis(), appVersion, reason)
        }
        zip(out, header, dbFile, wal)
    }

    /** The header of a backup file, or null when it is not one. */
    fun readHeader(input: InputStream): Header? = try {
        ZipInputStream(input.buffered()).use { z ->
            val first = z.nextEntry ?: return null
            if (first.name != HEADER) return null
            parseHeader(z)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Unpacks and checks a backup into [dir] (the database comes out as `dir/lekaspos.db`,
     * self-contained). Throws [Invalid] for anything that is not a sound backup this app can open.
     */
    fun unpack(input: InputStream, dir: File): Header {
        dir.deleteRecursively()
        dir.mkdirs()
        var header: Header? = null
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name) {
                    HEADER -> header = parseHeader(z)
                    DB -> FileOutputStream(File(dir, DB)).use { z.copyTo(it, 64 * 1024) }
                    WAL -> FileOutputStream(File(dir, WAL)).use { z.copyTo(it, 64 * 1024) }
                    else -> Unit // unknown parts of a newer format are ignored
                }
            }
        }
        val h = header ?: throw Invalid("not a LekasPOS backup")
        val file = File(dir, DB)
        if (!file.exists()) throw Invalid("the backup has no database")
        if (h.schema > Schema.VERSION) throw Invalid("made by a newer version of the app (schema ${h.schema})")
        // Opening read-write folds the WAL into the file; closing leaves one self-contained file.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { r ->
            val check = r.pragma("PRAGMA quick_check")
            if (check != "ok") throw Invalid("the database in the backup is damaged ($check)")
            if (r.version > Schema.VERSION) throw Invalid("made by a newer version of the app")
            if (Meta.get(r, Meta.DEVICE_UUID) == null || Meta.get(r, Meta.STORE_UUID) == null) throw Invalid("the backup has no store identity")
        }
        File(dir, WAL).delete()
        File(dir, "$DB-shm").delete()
        return h
    }

    /** The database file [unpack] produced in [dir]. */
    fun unpackedDb(dir: File): File = File(dir, DB)

    // ------------------------------------------------------------------ internals

    /** True when every WAL frame is now in the database file. */
    private fun checkpoint(sqlite: SQLiteDatabase): Boolean {
        repeat(5) { attempt ->
            val r = sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c ->
                if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2)) else Triple(0, 0, 0)
            }
            if (r.first == 0 && r.second == r.third) return true
            Thread.sleep(50L * (attempt + 1)) // a long read is still using old frames
        }
        return false
    }

    private fun header(r: SQLiteDatabase, now: Long, appVersion: String, reason: String): Header {
        fun count(table: String) = r.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val storeName = r.rawQuery("SELECT value FROM setting WHERE key = ?", arrayOf("store.name")).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
        return Header(
            format = FORMAT,
            createdAt = now,
            reason = reason,
            storeUuid = Meta.get(r, Meta.STORE_UUID) ?: "",
            deviceUuid = Meta.get(r, Meta.DEVICE_UUID) ?: "",
            deviceNo = Meta.getLong(r, Meta.DEVICE_NO)?.toInt() ?: 0,
            schema = r.version,
            appVersion = appVersion,
            storeName = storeName?.takeIf { it.isNotBlank() },
            sales = count("sale"),
            products = count("product"),
        )
    }

    private fun zip(out: OutputStream, h: Header, db: File, wal: File?) {
        val z = ZipOutputStream(out.buffered(64 * 1024))
        z.putNextEntry(ZipEntry(HEADER))
        val w = JsonWriter(OutputStreamWriter(NonClosing(z), Charsets.UTF_8))
        w.beginObject()
        w.name("format").value(h.format.toLong())
        w.name("createdAt").value(h.createdAt)
        w.name("reason").value(h.reason)
        w.name("storeUuid").value(h.storeUuid)
        w.name("deviceUuid").value(h.deviceUuid)
        w.name("deviceNo").value(h.deviceNo.toLong())
        w.name("schema").value(h.schema.toLong())
        w.name("appVersion").value(h.appVersion)
        w.name("storeName").value(h.storeName)
        w.name("sales").value(h.sales)
        w.name("products").value(h.products)
        w.endObject()
        w.flush()
        z.closeEntry()
        z.putNextEntry(ZipEntry(DB))
        FileInputStream(db).use { it.copyTo(z, 64 * 1024) }
        z.closeEntry()
        if (wal != null) {
            z.putNextEntry(ZipEntry(WAL))
            FileInputStream(wal).use { it.copyTo(z, 64 * 1024) }
            z.closeEntry()
        }
        z.finish()
        z.flush()
    }

    private fun parseHeader(input: InputStream): Header {
        val r = JsonReader(InputStreamReader(NonClosingIn(input), Charsets.UTF_8))
        var format = 0
        var createdAt = 0L
        var reason = ""
        var store = ""
        var device = ""
        var deviceNo = 0
        var schema = 0
        var app = ""
        var name: String? = null
        var sales = 0L
        var products = 0L
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            if (r.peek() == android.util.JsonToken.NULL) {
                r.nextNull()
                continue
            }
            when (key) {
                "format" -> format = r.nextInt()
                "createdAt" -> createdAt = r.nextLong()
                "reason" -> reason = r.nextString()
                "storeUuid" -> store = r.nextString()
                "deviceUuid" -> device = r.nextString()
                "deviceNo" -> deviceNo = r.nextInt()
                "schema" -> schema = r.nextInt()
                "appVersion" -> app = r.nextString()
                "storeName" -> name = r.nextString()
                "sales" -> sales = r.nextLong()
                "products" -> products = r.nextLong()
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (format < 1) throw Invalid("unknown backup format")
        return Header(format, createdAt, reason, store, device, deviceNo, schema, app, name, sales, products)
    }

    private fun copy(from: File, to: File) {
        FileInputStream(from).use { i -> FileOutputStream(to).use { o -> i.copyTo(o, 256 * 1024); o.fd.sync() } }
    }

    /** Lets a JsonWriter/Reader be closed-over without closing the ZIP stream underneath. */
    private class NonClosing(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    private class NonClosingIn(private val input: InputStream) : InputStream() {
        override fun read(): Int = input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
        override fun close() = Unit
    }
}
