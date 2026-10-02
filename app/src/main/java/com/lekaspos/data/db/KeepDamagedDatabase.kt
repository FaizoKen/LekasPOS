package com.lekaspos.data.db

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.util.Log
import java.io.File

/**
 * What SQLite does when a query meets a damaged page: nothing to the file. Android's default
 * handler (used by every open without a handler) closes the database and DELETES it with its WAL
 * on the first SQLITE_CORRUPT — one bad page after a power cut wiped the shop's only copy of its
 * data, the next start created an empty store, and the daily backups of that empty store then
 * rotated the good backups away (2026-10 review). Here the file stays where it is, the damage is
 * remembered and shown as "Data problem" (D-048), and the owner restores a backup.
 */
object KeepDamagedDatabase : DatabaseErrorHandler {

    /** The damage reported in this process or set aside at open (shown by the backup status), or null. */
    @Volatile
    var problem: String? = null
        private set

    /** The store's database file (set when it opens); damage of other files is not the store's. */
    @Volatile
    var storePath: String? = null

    override fun onCorruption(db: SQLiteDatabase) {
        Log.e("SQLite reported a damaged database: ${db.path} (kept)")
        // A damaged backup file being checked or restored says nothing about the shop's data.
        if (db.path != storePath) return
        if (problem == null) problem = "SQLite reported damage (SQLITE_CORRUPT)"
    }

    /**
     * The database [name] is too damaged to open: its files move to `files/backups/damaged-*`
     * (kept for the owner, never deleted by the app) so a new, empty database can open and the
     * owner can restore a backup from the app. For [HOLD_MS] the status says so and automatic
     * backups pause, so the good backups are not rotated away by backups of the empty store.
     */
    fun setAside(ctx: Context, name: String, cause: Exception) {
        val db = ctx.getDatabasePath(name)
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        // The marker first: killed while moving the files, the next start must still know that an
        // empty store is not the shop's data (no "Data problem", automatic backups rotating).
        marker(ctx).writeText(stamp.toString())
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val f = File(db.path + suffix)
            if (!f.exists()) continue
            val to = File(dir, "damaged-$stamp.db$suffix")
            if (!f.renameTo(to)) {
                f.copyTo(to, overwrite = true)
                f.delete()
            }
        }
        Log.e("The database could not be opened and was set aside as damaged-$stamp.db", cause)
    }

    /** At open: a database set aside less than [HOLD_MS] ago is reported as the problem. */
    fun checkSetAside(ctx: Context) {
        val m = marker(ctx)
        if (!m.exists()) return
        val at = runCatching { m.readText().trim().toLong() }.getOrNull()
        val now = System.currentTimeMillis()
        if (at == null || now - at !in 0L until HOLD_MS) {
            m.delete()
            return
        }
        problem = "The data file was damaged and kept aside (damaged-$at.db). Restore a backup."
    }

    /** A restore replaced the data: the problem of the set-aside file is over. */
    fun restored(ctx: Context) {
        marker(ctx).delete()
    }

    /** Tests: forget the damage this process saw (other tests share the process). */
    @androidx.annotation.VisibleForTesting
    fun clearForTests(ctx: Context) {
        problem = null
        marker(ctx).delete()
    }

    private fun marker(ctx: Context) = File(ctx.filesDir, "db-damaged")

    private const val HOLD_MS = 7L * 24L * 60L * 60L * 1000L
}
