package com.lekaspos.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.BuildConfig
import com.lekaspos.core.id.Ids
import com.lekaspos.data.db.KeepDamagedDatabase
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.sale.ReceiptNumbers
import com.lekaspos.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID

/**
 * Restoring a backup (D-044). The database cannot be swapped while the app uses it, so a restore
 * is unpacked and checked into `files/restore/`, the app restarts, and [applyIfStaged] swaps the
 * files before the database opens — the replaced database is kept as a backup first. A restore
 * either takes over the backed-up till's identity (the old phone is gone) or becomes a new till
 * (new device number, receipt prefix and ID range; the store stays the same).
 */
object Restore {

    enum class Mode { REPLACE, NEW_DEVICE }

    private fun dir(ctx: Context) = File(ctx.filesDir, "restore")

    /** The restore to apply at the next start (written only once the user chose "Restart now"). */
    private fun marker(ctx: Context) = File(dir(ctx), "ready")

    /** A checked restore waiting for the user's "Restart now" ([arm]); never applied on its own. */
    private fun prepared(ctx: Context) = File(dir(ctx), "prepared")

    /** Unpacks and checks [input] and arms it for the next start (tests; the app uses [prepare] + [arm]). */
    fun stage(ctx: Context, input: InputStream, mode: Mode): BackupFiles.Header {
        val h = prepare(ctx, input, mode)
        arm(ctx)
        return h
    }

    /**
     * Unpacks and checks [input]; nothing happens at the next start until [arm]. A restore armed
     * while still being checked was applied at some later start when the user had left the screen
     * (Back while "Checking…", or the idle lock closing the "Restart now" question): the till sold
     * all day, then a restart silently put the old backup back (2026-10 review).
     */
    fun prepare(ctx: Context, input: InputStream, mode: Mode): BackupFiles.Header {
        cancelStaged(ctx)
        val h = BackupFiles.unpack(input, dir(ctx))
        prepared(ctx).writeText(mode.name)
        return h
    }

    /** The user confirmed: the prepared restore is applied at the next start. False if none is prepared. */
    fun arm(ctx: Context): Boolean = prepared(ctx).exists() && prepared(ctx).renameTo(marker(ctx))

    fun cancelStaged(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    /** Written when a restore has been put in place, deleted when [afterOpen] has run for it ([finished]). */
    private fun pending(ctx: Context) = File(ctx.filesDir, "restore-pending")

    /** What this till knew before the restore replaced its data (see [Carry]); deleted by [finished]. */
    private fun carryFile(ctx: Context) = File(ctx.filesDir, "restore-carry")

    private fun failure(ctx: Context) = File(ctx.filesDir, "restore-failed")

    /**
     * Called before the database [name] opens: puts a staged restore in place. Returns the mode
     * when a restore was applied and [afterOpen] has not run for it yet — also when the app was
     * killed in between, so a restored till never starts with its old identity and print jobs
     * (the caller runs [afterOpen], then [finished]). A restore that was never confirmed is
     * thrown away.
     */
    fun applyIfStaged(ctx: Context, name: String): Mode? {
        val marker = marker(ctx)
        if (marker.exists()) {
            val asked = runCatching { Mode.valueOf(marker.readText().trim()) }.getOrDefault(Mode.REPLACE)
            val staged = BackupFiles.unpackedDb(dir(ctx))
            if (staged.exists()) place(ctx, staged, ctx.getDatabasePath(name), asked)
            cancelStaged(ctx)
        } else if (dir(ctx).exists()) {
            cancelStaged(ctx) // checked but never confirmed
        }
        val pending = pending(ctx)
        if (!pending.exists()) return null
        return runCatching { Mode.valueOf(pending.readText().trim()) }.getOrDefault(Mode.REPLACE)
    }

    private fun place(ctx: Context, staged: File, current: File, asked: Mode) {
        var mode = asked
        val pending = pending(ctx)
        if (current.exists()) {
            mode = safeMode(current, staged, asked)
            // Keep what is being replaced: a restore must never lose data for good. If that copy
            // cannot be made (storage full), the restore is not done at all.
            try {
                val backups = backupDir(ctx).apply { mkdirs() }
                val copy = File(backups, "replaced-${System.currentTimeMillis()}${BackupFiles.EXT}")
                try {
                    FileOutputStream(copy).use {
                        BackupFiles.writeClosed(current, it, BuildConfig.VERSION_NAME, REASON_REPLACED)
                        it.fd.sync()
                    }
                } catch (e: Exception) {
                    copy.delete()
                    // Data too damaged to read (often the reason for this restore): its files are kept
                    // exactly as they are instead (2026-10 review). Only if that fails too, no restore.
                    if (!keepFiles(current, File(backups, "replaced-${System.currentTimeMillis()}.db"))) throw e
                }
                (runCatching { Carry.read(current) }.getOrNull() ?: Carry()).save(carryFile(ctx))
            } catch (e: Exception) {
                Log.e("Keeping the replaced database failed: the restore is not done", e)
                runCatching { failure(ctx).writeText(e.message ?: e.javaClass.simpleName) }
                return
            }
        } else if (pending.exists()) {
            // Killed after the current data went and before the restored file took its place: the
            // mode decided then (it could still see the current data) stands.
            mode = runCatching { Mode.valueOf(pending.readText().trim()) }.getOrDefault(mode)
        }
        pending.writeText(mode.name)
        KeepDamagedDatabase.restored(ctx)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(current.path + suffix).delete()
        current.parentFile?.mkdirs()
        if (!staged.renameTo(current)) {
            staged.copyTo(current, overwrite = true)
            staged.delete()
        }
    }

    /** Copies the database file [db] and its WAL as they are to [to] (+ "-wal"); false if it failed. */
    private fun keepFiles(db: File, to: File): Boolean = try {
        for (suffix in listOf("", "-wal")) {
            val f = File(db.path + suffix)
            if (!f.exists()) continue
            val target = File(to.path + suffix)
            FileOutputStream(target).use { out ->
                java.io.FileInputStream(f).use { it.copyTo(out, 64 * 1024) }
                out.fd.sync()
            }
        }
        true
    } catch (e: Exception) {
        Log.e("Keeping the damaged database files failed", e)
        for (suffix in listOf("", "-wal")) File(to.path + suffix).delete()
        false
    }

    /**
     * "The same till" is safe only while the backup knows everything this till has published.
     * When the data being replaced is this very till and it has synced, its IDs, receipt numbers
     * and file numbers after the backup are already in the sync folder: the restored data
     * continues as a new till (as the restore screen says), whatever the backup itself contains.
     * A backup whose till cannot be read counts as this till.
     */
    private fun safeMode(current: File, staged: File, asked: Mode): Mode {
        if (asked != Mode.REPLACE) return asked
        return try {
            val now = identity(current)
            val then = runCatching { identity(staged) }.getOrNull()
            if (now.second && (then == null || now.first == null || now.first == then.first)) Mode.NEW_DEVICE else asked
        } catch (e: Exception) {
            Log.w("Comparing the restored till with this one failed", e)
            asked
        }
    }

    /** A database file's device uuid, and whether it has published to a sync folder. */
    private fun identity(file: File): Pair<String?, Boolean> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, KeepDamagedDatabase).use { db ->
            // Backups older than schema v5 have no sync tables: the count failed, and the restore
            // kept this till's identity although this till had synced (2026-10 review).
            val hasTable = db.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'sync_segment'") > 0L
            val segments = hasTable && db.long("SELECT COUNT(*) FROM sync_segment") > 0L
            Meta.get(db, Meta.DEVICE_UUID) to (segments || Meta.get(db, Meta.SYNC_ENABLED) == "1")
        }

    /** [afterOpen] has run for the restore that [applyIfStaged] reported. */
    fun finished(ctx: Context) {
        pending(ctx).delete()
        carryFile(ctx).delete()
    }

    /** What [place] kept of the replaced data, for [afterOpen]. */
    fun carried(ctx: Context): Carry = Carry.load(carryFile(ctx))

    /** Why the last restore was not done (the current data could not be kept first), once; else null. */
    fun takeFailure(ctx: Context): String? {
        val f = failure(ctx)
        if (!f.exists()) return null
        val why = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        return why
    }

    /**
     * After a restored database opened: stale print jobs go, and nobody is signed in (the
     * backup's sign-in came back with it, e.g. the owner's from last night). A new till gets a new
     * identity. So does a restored till that has published to a sync folder: the original went on
     * selling after the backup, so its IDs and receipt numbers from then on are already taken
     * there. A till that keeps its identity continues after everything it may have used ([Carry]).
     */
    fun afterOpen(db: SQLiteDatabase, mode: Mode, carry: Carry = Carry()) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM print_job")
            db.execSQL("DELETE FROM meta WHERE key LIKE 'session.%'")
            // Wrong-PIN counts and waits belong to this phone, not to the backup: a restore must
            // not give fresh guesses at a PIN.
            db.execSQL("DELETE FROM meta WHERE key LIKE 'pin.%'")
            for ((k, v) in carry.pins) Meta.put(db, k, v)
            val published = db.long("SELECT COUNT(*) FROM sync_segment") > 0L
            if (mode == Mode.NEW_DEVICE || published || Meta.get(db, Meta.SYNC_ENABLED) == "1") {
                newIdentity(db)
            } else {
                continueAfter(db, carry)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The restored data keeps this till's identity. Its next IDs skip a wide gap, so they never
     * meet IDs the till handed out after the backup (they may already be in another till's data,
     * or be published later by the original phone). Restored on the same phone, receipt numbers
     * go on after the highest one used; restored on another phone, receipts get a new prefix,
     * since the original's numbers after the backup are unknown here (2026-10 review).
     */
    private fun continueAfter(db: SQLiteDatabase, carry: Carry) {
        val uuid = Meta.get(db, Meta.DEVICE_UUID)
        val samePhone = uuid != null && uuid == carry.deviceUuid
        val reserved = maxOf(Meta.getLong(db, Meta.ID_RESERVED) ?: 0L, if (samePhone) carry.idReserved else 0L)
        val next = if (reserved < Ids.MAX_SEQ - 2L * ID_GAP) reserved + ID_GAP else reserved
        Meta.put(db, Meta.ID_RESERVED, next.toString())
        if (samePhone) {
            for ((k, v) in carry.docSeqs) {
                if (v > (Meta.getLong(db, k) ?: 0L)) Meta.put(db, k, v.toString())
            }
        } else {
            val no = Meta.getLong(db, Meta.DEVICE_NO)?.toInt()
            val old = Meta.get(db, Meta.RECEIPT_PREFIX) ?: no?.let { ReceiptNumbers.defaultPrefix(it) }
            val random = SecureRandom()
            var p: String
            do {
                p = ReceiptNumbers.defaultPrefix(Ids.randomDeviceNo(random))
            } while (p == old)
            Meta.put(db, Meta.RECEIPT_PREFIX, p)
        }
    }

    /** Device-local facts of the data a restore replaces, carried into the restored data. */
    class Carry(
        val deviceUuid: String? = null,
        val idReserved: Long = 0L,
        val docSeqs: Map<String, Long> = emptyMap(),
        val pins: Map<String, String> = emptyMap(),
    ) {
        fun save(file: File) {
            val p = java.util.Properties()
            deviceUuid?.let { p.setProperty(Meta.DEVICE_UUID, it) }
            p.setProperty(Meta.ID_RESERVED, idReserved.toString())
            for ((k, v) in docSeqs) p.setProperty(k, v.toString())
            for ((k, v) in pins) p.setProperty(k, v)
            FileOutputStream(file).use {
                p.store(it, null)
                it.fd.sync()
            }
        }

        companion object {
            fun read(file: File): Carry =
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, KeepDamagedDatabase).use { db ->
                    val docs = HashMap<String, Long>()
                    val pins = HashMap<String, String>()
                    db.rawQuery("SELECT key, value FROM meta WHERE key LIKE 'doc_seq_%' OR key LIKE 'pin.%'", null).use { c ->
                        while (c.moveToNext()) {
                            if (c.isNull(1)) continue
                            val k = c.getString(0)
                            val v = c.getString(1)
                            if (k.startsWith("pin.")) pins[k] = v else v.toLongOrNull()?.let { docs[k] = it }
                        }
                    }
                    Carry(Meta.get(db, Meta.DEVICE_UUID), Meta.getLong(db, Meta.ID_RESERVED) ?: 0L, docs, pins)
                }

            fun load(file: File): Carry {
                if (!file.exists()) return Carry()
                val p = java.util.Properties()
                try {
                    java.io.FileInputStream(file).use { p.load(it) }
                } catch (e: java.io.IOException) {
                    return Carry()
                }
                val docs = HashMap<String, Long>()
                val pins = HashMap<String, String>()
                for (k in p.stringPropertyNames()) {
                    val v = p.getProperty(k)
                    when {
                        k.startsWith("doc_seq_") -> v.toLongOrNull()?.let { docs[k] = it }
                        k.startsWith("pin.") -> pins[k] = v
                    }
                }
                return Carry(p.getProperty(Meta.DEVICE_UUID), p.getProperty(Meta.ID_RESERVED)?.toLongOrNull() ?: 0L, docs, pins)
            }
        }
    }

    /**
     * Called when the database opens. Turning the Drive backup on found that this phone holds an
     * older copy of a till that has published since (a backup from before it first synced was
     * restored as "the same till"): it becomes a new till here, before anything is written with
     * the old number. Returns true when the identity changed.
     */
    fun renewIfAsked(db: SQLiteDatabase): Boolean {
        if (Meta.get(db, Meta.RENEW_IDENTITY) != "1") return false
        db.beginTransaction()
        try {
            newIdentity(db)
            Meta.put(db, Meta.RENEW_IDENTITY, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /**
     * This database becomes a different till of the same store: new device number (so its new
     * IDs and receipt numbers never collide with the original's), fresh sequences, no pending
     * sync events (they belong to the original till). Sync is off until turned on again (which
     * publishes the data under the new number); the original's segments already contained in
     * this database are marked as read, so only what it did after the backup is imported.
     */
    fun newIdentity(db: SQLiteDatabase, random: SecureRandom = SecureRandom()) {
        val old = Meta.getLong(db, Meta.DEVICE_NO)?.toInt()
        // The new number also gives another receipt prefix than the one this data was numbered
        // with, so the first receipts of the new till never repeat numbers already printed.
        val oldPrefix = Meta.get(db, Meta.RECEIPT_PREFIX) ?: old?.let { ReceiptNumbers.defaultPrefix(it) }
        var no: Int
        do {
            no = Ids.randomDeviceNo(random)
        } while (no == old || ReceiptNumbers.defaultPrefix(no) == oldPrefix)
        Meta.put(db, Meta.DEVICE_UUID, UUID.randomUUID().toString())
        Meta.put(db, Meta.DEVICE_NO, no.toString())
        Meta.put(db, Meta.ID_RESERVED, "0")
        Meta.put(db, Meta.RECEIPT_PREFIX, null)
        db.execSQL("DELETE FROM meta WHERE key >= 'doc_seq_' AND key < 'doc_seq`'")
        db.execSQL("DELETE FROM outbox")
        val last = db.queryOne("SELECT seq, last_hlc FROM sync_segment ORDER BY seq DESC LIMIT 1") { it.getLong(0) to it.getLong(1) }
        if (old != null && last != null) {
            db.execSQL(
                "INSERT OR REPLACE INTO sync_cursor(dev, seq, last_hlc, updated_at) VALUES(?, ?, ?, ?)",
                arrayOf<Any>(old, last.first, last.second, System.currentTimeMillis()),
            )
        }
        db.execSQL("DELETE FROM sync_segment")
        Meta.put(db, Meta.SYNC_ENABLED, "0")
        for (k in listOf(Meta.SYNC_BACKFILLED, Meta.SYNC_LAST_OK, Meta.SYNC_LAST_ERROR, Meta.SYNC_CLEANED_TO)) Meta.put(db, k, null)
    }

    fun backupDir(ctx: Context): File = File(ctx.filesDir, "backups")

    const val REASON_REPLACED = "replaced"

    /** IDs skipped by a restored till that keeps its identity (see [continueAfter]): about a billion. */
    private const val ID_GAP = 1L shl 30
}
