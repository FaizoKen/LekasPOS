package com.lekaspos.domain.backup

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Process
import com.lekaspos.BuildConfig
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.Perm
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.Restore
import com.lekaspos.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backups on this phone and backup files (D-044): an automatic backup a day (the last
 * [KEEP_AUTO] kept), one before every database upgrade and restore, "back up now", saving or
 * sharing a backup file, and restoring one — which restarts the app. Works without Google.
 */
class BackupService(private val graph: AppGraph, private val app: Application) {

    data class Entry(val file: File, val header: BackupFiles.Header?) {
        val auto: Boolean get() = file.name.startsWith(AUTO)
    }

    private val lock = Mutex()

    val dir: File get() = Restore.backupDir(app)

    /** Makes a backup on this phone. */
    suspend fun backupNow(auto: Boolean = false): File = lock.withLock {
        if (!auto) graph.permissions.actor(Perm.SETTINGS)
        val db = graph.db()
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val file = File(dir, "${if (auto) AUTO else MANUAL}${System.currentTimeMillis()}${BackupFiles.EXT}")
            val part = File(file.path + ".part")
            try {
                FileOutputStream(part).use { out ->
                    BackupFiles.write(db, out, File(app.cacheDir, "backup-tmp"), BuildConfig.VERSION_NAME, if (auto) "auto" else "manual")
                    out.fd.sync()
                }
                if (!part.renameTo(file)) throw IllegalStateException("cannot finish ${file.name}")
            } finally {
                part.delete()
            }
            prune()
            file
        }
    }

    /** The automatic daily backup: only when the last one is older than [DUE_MS]. */
    suspend fun backupIfDue(): Boolean {
        val last = withContext(Dispatchers.IO) { dir.listFiles { f -> f.name.startsWith(AUTO) && f.name.endsWith(BackupFiles.EXT) }?.maxOfOrNull { it.lastModified() } }
        if (last != null && System.currentTimeMillis() - last < DUE_MS) return false
        backupNow(auto = true)
        return true
    }

    /** Backups on this phone, newest first. */
    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.name.endsWith(BackupFiles.EXT) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f -> Entry(f, runCatching { FileInputStream(f).use { BackupFiles.readHeader(it) } }.getOrNull()) }
    }

    /** Writes a fresh backup to [out] (a file the user picked, or one to share). */
    suspend fun export(out: OutputStream) {
        graph.permissions.actor(Perm.SETTINGS)
        val db = graph.db()
        withContext(Dispatchers.IO) { BackupFiles.write(db, out, File(app.cacheDir, "backup-tmp"), BuildConfig.VERSION_NAME, "export") }
    }

    suspend fun header(open: () -> InputStream): BackupFiles.Header? = withContext(Dispatchers.IO) {
        runCatching { open().use { BackupFiles.readHeader(it) } }.getOrNull()
    }

    /**
     * Checks and stages [open]'s backup for the next start (then [restart]). The current data is
     * backed up again right before it is replaced.
     */
    suspend fun stageRestore(open: () -> InputStream, mode: Restore.Mode): BackupFiles.Header {
        graph.permissions.actor(Perm.SETTINGS)
        return withContext(Dispatchers.IO) { open().use { Restore.stage(app, it, mode) } }
    }

    fun cancelRestore() = Restore.cancelStaged(app)

    /** Restarts the app so the staged restore is applied before the database opens. */
    fun restart(activity: Activity) {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch == null) {
            Log.w("No launch intent; the restore applies at the next start")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(launch)
        Process.killProcess(Process.myPid())
    }

    suspend fun delete(file: File) {
        graph.permissions.actor(Perm.SETTINGS)
        withContext(Dispatchers.IO) { if (file.parentFile == dir) file.delete() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(BackupFiles.EXT) } ?: return
        files.filter { it.name.startsWith(AUTO) }.sortedByDescending { it.lastModified() }.drop(KEEP_AUTO).forEach { it.delete() }
        files.filter { it.name.startsWith(Restore.REASON_REPLACED) }.sortedByDescending { it.lastModified() }.drop(KEEP_REPLACED).forEach { it.delete() }
    }

    companion object {
        const val AUTO = "auto-"
        const val MANUAL = "manual-"
        const val KEEP_AUTO = 7
        const val KEEP_REPLACED = 3
        const val DUE_MS = 20L * 60L * 60L * 1000L
    }
}
