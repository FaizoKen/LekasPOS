package com.lekaspos.domain.backup

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Process
import com.lekaspos.BuildConfig
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.Perm
import com.lekaspos.core.time.DateText
import com.lekaspos.core.time.Days
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.BackupFolder
import com.lekaspos.data.backup.Restore
import com.lekaspos.data.backup.TreeBackupFolder
import com.lekaspos.data.db.KeepDamagedDatabase
import com.lekaspos.data.db.Meta
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backups on this phone and backup files (D-044): an automatic backup a day (the last
 * [KEEP_AUTO] kept), one before every database upgrade and restore, "back up now", saving or
 * sharing a backup file, and restoring one — which restarts the app. Works without Google.
 *
 * Data safety (D-048): each automatic backup is copied to a folder off the app's storage when
 * the owner picked one; the database is checked before it is backed up (a damaged one never
 * rotates good backups away); [protection] says whether a copy of the data exists off this phone.
 */
class BackupService(private val graph: AppGraph, private val app: Application) {

    data class Entry(val file: File, val header: BackupFiles.Header?, val size: Long = 0L) {
        val auto: Boolean get() = file.name.startsWith(AUTO)
    }

    /** Whether the shop's data also exists somewhere other than this phone. */
    data class Protection(
        val state: State = State.NO_DATA,
        /** Last copy off this phone (Google Drive sync, folder copy or a saved/shared backup file). */
        val lastOffPhone: Long? = null,
        val driveOn: Boolean = false,
        val folderName: String? = null,
        val folderError: String? = null,
        val damage: String? = null,
    ) {
        enum class State { NO_DATA, PROTECTED, AT_RISK, DAMAGED }
    }

    private val lock = Mutex()
    private val _protection = MutableStateFlow(Protection())
    val protection: StateFlow<Protection> = _protection

    /** Tests replace the picked folder with a plain directory. */
    @Volatile
    var folderForTests: BackupFolder? = null

    /** Tests pretend the database check found damage. */
    @Volatile
    var integrityForTests: String? = null

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
            prune(keep = file)
            file
        }
    }

    /**
     * The automatic daily backup: only when the last one is older than [DUE_MS]. The database is
     * checked first; the new backup is then copied to the owner's folder, if one is set.
     */
    suspend fun backupIfDue(): Boolean {
        val now = System.currentTimeMillis()
        // Backups dated in the future were made before the clock was set back: they do not count
        // as recent (waiting for the clock to catch up stopped every backup for as long, 2026-10
        // review), and they are the first to go when pruning.
        val latest = withContext(Dispatchers.IO) { autoFiles().filter { it.lastModified() <= now + CLOCK_SLACK_MS }.maxByOrNull { it.lastModified() } }
        val due = latest == null || !recent(latest.lastModified(), now, DUE_MS)
        // Copying the database pauses every sale while it runs (seconds for a large store on a slow
        // phone): while a bill is being rung up or the till has just sold, the backup waits for a
        // quiet moment — but never past OVERDUE_MS since the last one (2026-10 review: it ran right
        // when the till was switched on in the morning, and Pay hung with the first customer).
        if (due && latest != null && recent(latest.lastModified(), now, OVERDUE_MS) && busy(now)) throw Postponed()
        val made = if (due) {
            val db = graph.db()
            val check = integrityForTests ?: KeepDamagedDatabase.problem ?: try {
                db.read { BackupFiles.integrity(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // the job was stopped (battery low): that is no damage (2026-10 review)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (check != "ok") {
                // Keep every backup as it is: rotating would replace good copies by damaged ones.
                val why = check.take(200)
                Log.e("Database check failed: $why")
                _protection.value = _protection.value.copy(state = Protection.State.DAMAGED, damage = why)
                runCatching { db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, Meta.DB_PROBLEM, why) } }
                return false
            }
            db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, Meta.DB_PROBLEM, null) }
            backupNow(auto = true)
        } else {
            null
        }
        val folderDue = folderSet() && lastFolderCopy().let { it == null || !recent(it, now, DUE_MS) }
        val toCopy = made ?: if (folderDue) latest else null
        if (toCopy != null && folderSet()) copyToFolder(toCopy)
        refreshProtection()
        return made != null
    }

    // ------------------------------------------------------------------ folder copies (D-048)

    private suspend fun folder(): BackupFolder? {
        folderForTests?.let { return it }
        val uri = graph.db().read { Meta.get(it, Meta.BACKUP_FOLDER) } ?: return null
        return TreeBackupFolder(app.contentResolver, Uri.parse(uri))
    }

    private suspend fun folderSet(): Boolean = folder() != null

    private suspend fun lastFolderCopy(): Long? = graph.db().read { Meta.getLong(it, Meta.BACKUP_FOLDER_OK) }

    /** Keeps the folder the owner picked ([uri] from the system folder picker; null = stop copying). */
    suspend fun setFolder(uri: Uri?, name: String?) {
        graph.permissions.actor(Perm.SETTINGS)
        val db = graph.db()
        val old = db.read { Meta.get(it, Meta.BACKUP_FOLDER) }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        withContext(Dispatchers.IO) {
            if (uri != null) app.contentResolver.takePersistableUriPermission(uri, flags)
            if (old != null && old != uri?.toString()) {
                runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
            }
        }
        db.write(reserveIds = 0L) { tx ->
            Meta.put(tx.db, Meta.BACKUP_FOLDER, uri?.toString())
            Meta.put(tx.db, Meta.BACKUP_FOLDER_NAME, name)
            Meta.put(tx.db, Meta.BACKUP_FOLDER_OK, null)
            Meta.put(tx.db, Meta.BACKUP_FOLDER_ERROR, null)
        }
        refreshProtection()
    }

    /** Copies the newest backup to the folder now (a fresh one when there is none yet). */
    suspend fun copyToFolderNow(): Boolean {
        graph.permissions.actor(Perm.SETTINGS)
        val latest = withContext(Dispatchers.IO) { autoFiles().maxByOrNull { it.lastModified() } }
            ?: backupNow(auto = true)
        return copyToFolder(latest).also { refreshProtection() }
    }

    /** Copies [file] into the folder as `lekaspos-<date>-<time>.lekasbak` and keeps the newest [KEEP_FOLDER]. */
    private suspend fun copyToFolder(file: File): Boolean {
        val target = folder() ?: return false
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val stamp = DateText.isoDate(Days.epochDay(now, tz)) + "-" + DateText.time(now, tz).replace(":", "")
        val name = "${BackupFolder.PREFIX}$stamp${BackupFiles.EXT}"
        val db = graph.db()
        return try {
            withContext(Dispatchers.IO) {
                target.write(name) { out -> FileInputStream(file).use { it.copyTo(out, 64 * 1024) } }
                // The copy just written always stays (named by a clock set back, it sorted last).
                val old = target.list().filter { it.name != name }.sortedByDescending { it.name }.drop(KEEP_FOLDER - 1)
                for (item in old) runCatching { target.delete(item) }
            }
            db.write(reserveIds = 0L) { tx ->
                // As old as the backup copied (the newest may be from yesterday), not the copy time.
                Meta.put(tx.db, Meta.BACKUP_FOLDER_OK, minOf(now, file.lastModified()).toString())
                Meta.put(tx.db, Meta.BACKUP_FOLDER_ERROR, null)
            }
            true
        } catch (e: Exception) {
            Log.w("Copying the backup to the folder failed", e)
            val why = e.message ?: e.javaClass.simpleName
            db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, Meta.BACKUP_FOLDER_ERROR, why) }
            false
        }
    }

    // ------------------------------------------------------------------ protection status (D-048)

    /** Re-reads whether a recent copy of the data exists off this phone. */
    suspend fun refreshProtection(now: Long = System.currentTimeMillis()): Protection {
        val db = graph.db()
        val p = db.read { r ->
            val hasData = ProductDao.any(r) || SaleDao.any(r)
            val drive = if (db.syncEnabled) Meta.getLong(r, Meta.SYNC_LAST_OK) else null
            val folder = Meta.getLong(r, Meta.BACKUP_FOLDER_OK)
            val last = listOfNotNull(drive, folder, Meta.getLong(r, Meta.BACKUP_EXPORT_OK)).maxOrNull()
            val damage = Meta.get(r, Meta.DB_PROBLEM) ?: KeepDamagedDatabase.problem
            val state = when {
                damage != null -> Protection.State.DAMAGED
                !hasData -> Protection.State.NO_DATA
                last != null && recent(last, now, FRESH_MS) -> Protection.State.PROTECTED
                else -> Protection.State.AT_RISK
            }
            Protection(
                state = state,
                lastOffPhone = last,
                driveOn = db.syncEnabled,
                folderName = Meta.get(r, Meta.BACKUP_FOLDER)?.let { Meta.get(r, Meta.BACKUP_FOLDER_NAME) ?: "" },
                folderError = Meta.get(r, Meta.BACKUP_FOLDER_ERROR),
                damage = damage,
            )
        }
        _protection.value = p
        return p
    }

    // ------------------------------------------------------------------ files and restore

    /** Backups on this phone, newest first. */
    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.name.endsWith(BackupFiles.EXT) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f -> Entry(f, runCatching { FileInputStream(f).use { BackupFiles.readHeader(it) } }.getOrNull(), f.length()) }
    }

    /**
     * Writes a fresh backup to the stream [open] gives (a file the user picked, or one to share):
     * a copy off this phone. The stream is opened, written and closed off the main thread; one
     * backup at a time (they share a work file).
     */
    suspend fun export(open: () -> OutputStream) = lock.withLock {
        val actor = graph.permissions.actor(Perm.SETTINGS)
        val db = graph.db()
        withContext(Dispatchers.IO) {
            open().use { out -> BackupFiles.write(db, out, File(app.cacheDir, "backup-tmp"), BuildConfig.VERSION_NAME, "export") }
        }
        // A copy of all the shop's data left the app (saved or shared): on record (2026-10 review).
        db.write(reserveIds = 1L) { tx ->
            val now = System.currentTimeMillis()
            Meta.put(tx.db, Meta.BACKUP_EXPORT_OK, now.toString())
            com.lekaspos.data.audit.AuditDao.log(
                tx, com.lekaspos.core.model.AuditAction.SETTINGS_CHANGE, actor.staffId, now, detail = "backup copy saved or shared", approvedBy = actor.approvedBy,
            )
        }
        refreshProtection()
    }

    /** Why the last restore was not done, told once (see [Restore.takeFailure]). */
    suspend fun restoreFailure(): String? = withContext(Dispatchers.IO) { Restore.takeFailure(app) }

    suspend fun header(open: () -> InputStream): BackupFiles.Header? = withContext(Dispatchers.IO) {
        runCatching { open().use { BackupFiles.readHeader(it) } }.getOrNull()
    }

    /**
     * Checks and stages [open]'s backup for the next start (then [restart]). The current data is
     * backed up again right before it is replaced.
     */
    suspend fun stageRestore(open: () -> InputStream, mode: Restore.Mode): BackupFiles.Header {
        val actor = graph.permissions.actor(Perm.SETTINGS)
        val who = Restore.Who(actor.staffId, actor.approvedBy)
        return try {
            withContext(Dispatchers.IO) { open().use { Restore.prepare(app, it, mode, who) } }
        } catch (e: Exception) {
            // Also when the screen closed while checking: nothing stays waiting to be applied.
            withContext(NonCancellable + Dispatchers.IO) { Restore.cancelStaged(app) }
            throw e
        }
    }

    /** The checked restore is not wanted (Cancel, or the question closed without "Restart now"). */
    suspend fun cancelRestore() = withContext(Dispatchers.IO) { Restore.cancelStaged(app) }

    /**
     * The user chose "Restart now" for a checked restore: it is applied at the next start, which is
     * now. Only this makes a checked restore count (see [Restore.prepare]). False if none is checked.
     */
    suspend fun restartIntoRestore(activity: Activity): Boolean {
        if (!withContext(Dispatchers.IO) { Restore.arm(app) }) return false
        restart(activity)
        return true
    }

    /** Restarts the app (a staged restore, or a new till identity, applies before the database opens). */
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

    private fun autoFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith(AUTO) && f.name.endsWith(BackupFiles.EXT) }.orEmpty().toList()

    /** Keeps the newest backups of each kind, and always [keep] (just written, whatever the clock says). */
    private fun prune(keep: File? = null) {
        val files = dir.listFiles { f -> f.name.endsWith(BackupFiles.EXT) && f != keep } ?: return
        val autoLeft = if (keep?.name?.startsWith(AUTO) == true) KEEP_AUTO - 1 else KEEP_AUTO
        val future = System.currentTimeMillis() + CLOCK_SLACK_MS
        // Newest first; files dated in the future (made before the clock was set back) count as oldest.
        val order = compareBy<File> { it.lastModified() > future }.thenByDescending { it.lastModified() }
        files.filter { it.name.startsWith(AUTO) }.sortedWith(order).drop(autoLeft).forEach { it.delete() }
        files.filter { it.name.startsWith(Restore.REASON_REPLACED) }.sortedWith(order).drop(KEEP_REPLACED).forEach { it.delete() }
    }

    /** [at] is less than [window] ago — and not in the future (a clock set back since). */
    private fun recent(at: Long, now: Long, window: Long): Boolean = at <= now + CLOCK_SLACK_MS && now - at < window

    /** A bill is being rung up, or a sale was made in the last [QUIET_MS]. */
    private suspend fun busy(now: Long): Boolean {
        if (!graph.cart.state.value.cart.isEmpty) return true
        val last = graph.db().read { SaleDao.lastSoldAt(it) } ?: return false
        return now - last in 0L until QUIET_MS
    }

    /** The automatic backup waits for a quiet moment; the worker tries again a little later. */
    class Postponed : Exception("the till is busy: the automatic backup waits")

    companion object {
        const val AUTO = "auto-"
        const val MANUAL = "manual-"
        const val KEEP_AUTO = 7
        const val KEEP_REPLACED = 3
        const val KEEP_FOLDER = 7
        const val DUE_MS = 20L * 60L * 60L * 1000L
        private const val OVERDUE_MS = 36L * 60L * 60L * 1000L
        private const val QUIET_MS = 3L * 60L * 1000L
        private const val CLOCK_SLACK_MS = 5L * 60L * 1000L

        /** A copy off this phone counts as recent for this long (then "not backed up" shows). */
        const val FRESH_MS = 3L * 24L * 60L * 60L * 1000L
    }
}
