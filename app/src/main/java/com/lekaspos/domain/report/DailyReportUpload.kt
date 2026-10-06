package com.lekaspos.domain.report

import android.content.Context
import com.lekaspos.app.AppGraph
import com.lekaspos.app.Work
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Perm
import com.lekaspos.core.report.MonthFiles
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Meta
import com.lekaspos.domain.Approval
import com.lekaspos.sync.AuthNeeded
import com.lekaspos.sync.ReportFolder
import com.lekaspos.sync.SyncEngine
import com.lekaspos.sync.SyncProviders
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The daily sales report in the shop's Google Drive (D-065). Once a day has ended, its month's daily
 * sales (the "Daily sales" CSV of Reports, a row a day) are written to "LekasPOS/2026-10 daily-sales.csv"
 * in the owner's Drive, replacing that month's file ([MonthFiles]). Off until the owner turns it on, and
 * for this till only: one till of a shop is enough (synced tills report every till's sales).
 *
 * It goes straight to Google's Drive service, not through the Drive app, so it works on a phone whose
 * Drive app cannot be a folder (an old tablet); it needs Google Play services and internet.
 */
class DailyReportUpload(
    private val graph: AppGraph,
    private val app: Context,
    private val folderOf: (account: String) -> ReportFolder = { SyncProviders.reportFolder(app, it) },
    /** The background job on or off ([Work.dailyReport]); blocking. */
    private val schedule: (on: Boolean, soon: Boolean) -> Unit = { on, soon -> Work.dailyReport(app, on, soon) },
) {

    data class Status(
        /** The Google account the report goes to; null: off. */
        val account: String? = null,
        val lastOk: Long? = null,
        /** The last finished day written (local epoch day). */
        val lastDay: Long? = null,
        /** Why the last try failed ([SyncEngine.errorCode]: offline, sign-in … or a short message); null when it worked. */
        val error: String? = null,
    ) {
        val on: Boolean get() = account != null
    }

    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state

    /** One upload at a time (the daily job and "Upload now" share the work files). */
    private val lock = Mutex()

    suspend fun load(): Status {
        val s = graph.db().read { r ->
            Status(
                Meta.get(r, Meta.DRIVE_REPORT_ACCOUNT),
                Meta.getLong(r, Meta.DRIVE_REPORT_OK),
                Meta.getLong(r, Meta.DRIVE_REPORT_DAY),
                Meta.get(r, Meta.DRIVE_REPORT_ERROR),
            )
        }
        state.value = s
        return s
    }

    /** Turned on for [account] (Google's access granted); the first upload is the caller's (it waits for it). */
    suspend fun turnOn(account: String, approval: Approval? = null) {
        val actor = graph.permissions.actor(Perm.SETTINGS, approval)
        graph.db().write(reserveIds = 1L) { tx ->
            Meta.put(tx.db, Meta.DRIVE_REPORT_ACCOUNT, account)
            Meta.put(tx.db, Meta.DRIVE_REPORT_DAY, null)
            Meta.put(tx.db, Meta.DRIVE_REPORT_ERROR, null)
            // The shop's sales leave the phone every day from now on: on record.
            AuditDao.log(
                tx, AuditAction.SETTINGS_CHANGE, actor.staffId, System.currentTimeMillis(),
                detail = "daily sales report to Google Drive on", approvedBy = actor.approvedBy,
            )
        }
        load()
        withContext(Dispatchers.IO) { schedule(true, false) }
    }

    /** No more uploads from this till; the files already in Drive stay. */
    suspend fun turnOff(approval: Approval? = null) {
        val actor = graph.permissions.actor(Perm.SETTINGS, approval)
        withContext(Dispatchers.IO) { schedule(false, false) }
        lock.withLock {
            graph.db().write(reserveIds = 1L) { tx ->
                for (k in listOf(Meta.DRIVE_REPORT_ACCOUNT, Meta.DRIVE_REPORT_DAY, Meta.DRIVE_REPORT_OK, Meta.DRIVE_REPORT_ERROR)) {
                    Meta.put(tx.db, k, null)
                }
                AuditDao.log(
                    tx, AuditAction.SETTINGS_CHANGE, actor.staffId, System.currentTimeMillis(),
                    detail = "daily sales report to Google Drive off", approvedBy = actor.approvedBy,
                )
            }
        }
        load()
    }

    /** At each start (off the main thread): the daily job again, and a run soon when a finished day waits. */
    suspend fun atStart(now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()) {
        val s = load()
        if (!s.on) return
        val due = MonthFiles.due(s.lastDay, Days.epochDay(now, tz) - 1).isNotEmpty()
        withContext(Dispatchers.IO) { schedule(true, due) }
    }

    /**
     * Writes the month files that are due ([MonthFiles.due]); [again] ("Upload now"): yesterday's month
     * and the one before, even when written already. Returns the names written (none: off, or nothing
     * due). Throws when Drive cannot be reached or refuses; [AuthNeeded]: the owner must sign in again.
     */
    suspend fun upload(again: Boolean = false, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): List<String> =
        lock.withLock {
            val (account, last) = graph.db().read { r -> Meta.get(r, Meta.DRIVE_REPORT_ACCOUNT) to Meta.getLong(r, Meta.DRIVE_REPORT_DAY) }
            if (account == null) return@withLock emptyList()
            val yesterday = Days.epochDay(now, tz) - 1
            val months = MonthFiles.due(if (again) null else last, yesterday)
            if (months.isEmpty()) return@withLock emptyList()
            val written = try {
                graph.settings.load() // the store's currency, also when the job started the app
                val folder = folderOf(account)
                val dir = File(app.cacheDir, WORK_DIR)
                months.map { month ->
                    val name = MonthFiles.name(month)
                    val file = File(dir, name)
                    try {
                        withContext(Dispatchers.IO) {
                            dir.mkdirs()
                            BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 64 * 1024).use { out ->
                                graph.reports.write(ReportService.Export.DAILY, MonthFiles.period(month, yesterday), out, tz)
                            }
                        }
                        folder.put(name, file, CSV)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                    }
                    name
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    // Said like sync's failures (offline, sign-in, a full or busy Drive, or the message).
                    val why = SyncEngine.errorCode(e).take(MAX_ERROR)
                    withContext(NonCancellable) { graph.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, Meta.DRIVE_REPORT_ERROR, why) } }
                    load()
                }
                throw e
            }
            graph.db().write(reserveIds = 0L) { tx ->
                Meta.put(tx.db, Meta.DRIVE_REPORT_DAY, yesterday.toString())
                Meta.put(tx.db, Meta.DRIVE_REPORT_OK, now.toString())
                Meta.put(tx.db, Meta.DRIVE_REPORT_ERROR, null)
            }
            load()
            written
        }

    companion object {
        private const val CSV = "text/csv"
        private const val WORK_DIR = "drive-report"
        private const val MAX_ERROR = 200
    }
}
