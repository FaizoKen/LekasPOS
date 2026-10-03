package com.lekaspos.util

import android.database.sqlite.SQLiteFullException
import android.os.StatFs
import java.io.File

/**
 * The phone's free storage (2026-10 review): nothing warned before a full phone stopped saving
 * sales, and the daily backup stopped without a word (WorkManager's "storage not low").
 */
object Storage {

    private const val MB = 1024L * 1024L

    /** Below this much free storage the shop is warned, whatever the database's size. */
    private const val LOW_MIN = 300L * MB

    /** Room for a day's work beside the daily backup's copy of the database. */
    private const val MARGIN = 100L * MB

    /** Free bytes where [dir] lives; [Long.MAX_VALUE] when Android cannot say. Cheap. */
    fun freeBytes(dir: File): Long = try {
        StatFs(dir.path).availableBytes
    } catch (e: IllegalArgumentException) {
        Long.MAX_VALUE
    }

    /**
     * Free storage below which the shop is told: the daily backup copies the whole database first
     * ([dbBytes]), and sales stop being saved soon after.
     */
    fun lowBelow(dbBytes: Long): Long = maxOf(LOW_MIN, dbBytes + MARGIN)

    /** What the daily backup needs free: a copy of the database, the compressed backup, a margin. */
    fun backupNeeds(dbBytes: Long): Long = dbBytes + dbBytes / 2 + MARGIN / 2

    /** [t] or one of its causes says the storage is full (SQLite's "database or disk is full", ENOSPC). */
    fun isFull(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is SQLiteFullException) return true
            val m = e.message.orEmpty()
            if ("ENOSPC" in m || "No space left" in m || "SQLITE_FULL" in m) return true
            e = e.cause
        }
        return false
    }

    /** "1.2 GB", "350 MB": how much is free, for the warning. */
    fun text(bytes: Long): String =
        if (bytes >= 1024L * MB) "${bytes / (1024L * MB)}.${bytes % (1024L * MB) * 10 / (1024L * MB)} GB" else "${bytes / MB} MB"
}
