package com.lekaspos.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.BuildConfig
import com.lekaspos.core.id.Ids
import com.lekaspos.data.db.Meta
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

    private fun marker(ctx: Context) = File(dir(ctx), "ready")

    /** Unpacks and checks [input]; it is used at the next start. */
    fun stage(ctx: Context, input: InputStream, mode: Mode): BackupFiles.Header {
        val h = BackupFiles.unpack(input, dir(ctx))
        marker(ctx).writeText(mode.name)
        return h
    }

    fun cancelStaged(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    /**
     * Called before the database [name] opens: puts a staged restore in place. Returns the mode
     * when a restore was applied (the caller then runs [afterOpen]).
     */
    fun applyIfStaged(ctx: Context, name: String): Mode? {
        val marker = marker(ctx)
        if (!marker.exists()) return null
        val mode = runCatching { Mode.valueOf(marker.readText().trim()) }.getOrDefault(Mode.REPLACE)
        val staged = BackupFiles.unpackedDb(dir(ctx))
        if (!staged.exists()) {
            cancelStaged(ctx)
            return null
        }
        val current = ctx.getDatabasePath(name)
        if (current.exists()) {
            // Keep what is being replaced: a restore must never lose data for good.
            try {
                val backups = backupDir(ctx).apply { mkdirs() }
                FileOutputStream(File(backups, "replaced-${System.currentTimeMillis()}${BackupFiles.EXT}")).use {
                    BackupFiles.writeClosed(current, it, BuildConfig.VERSION_NAME, REASON_REPLACED)
                }
            } catch (e: Exception) {
                Log.e("Keeping the replaced database failed", e)
            }
        }
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(current.path + suffix).delete()
        current.parentFile?.mkdirs()
        if (!staged.renameTo(current)) {
            staged.copyTo(current, overwrite = true)
            staged.delete()
        }
        cancelStaged(ctx)
        return mode
    }

    /** After a restored database opened: stale print jobs go; a new till gets a new identity. */
    fun afterOpen(db: SQLiteDatabase, mode: Mode) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM print_job")
            if (mode == Mode.NEW_DEVICE) newIdentity(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * This database becomes a different till of the same store: new device number (so its new
     * IDs and receipt numbers never collide with the original's), fresh sequences, no pending
     * sync events (they belong to the original till).
     */
    fun newIdentity(db: SQLiteDatabase, random: SecureRandom = SecureRandom()) {
        val old = Meta.getLong(db, Meta.DEVICE_NO)?.toInt()
        var no: Int
        do {
            no = Ids.randomDeviceNo(random)
        } while (no == old)
        Meta.put(db, Meta.DEVICE_UUID, UUID.randomUUID().toString())
        Meta.put(db, Meta.DEVICE_NO, no.toString())
        Meta.put(db, Meta.ID_RESERVED, "0")
        Meta.put(db, Meta.RECEIPT_PREFIX, null)
        db.execSQL("DELETE FROM meta WHERE key >= 'doc_seq_' AND key < 'doc_seq`'")
        db.execSQL("DELETE FROM outbox")
    }

    fun backupDir(ctx: Context): File = File(ctx.filesDir, "backups")

    const val REASON_REPLACED = "replaced"
}
