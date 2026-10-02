package com.lekaspos.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lekaspos.util.Log
import java.util.concurrent.TimeUnit

/**
 * Background jobs (references/architecture.md §3): WorkManager keeps them alive across
 * restarts and holds a wake lock while they run. Initialised on demand (not at app start),
 * and scheduled a few seconds after the selling screen is usable.
 */
object Work {

    private const val BACKUP = "backup-daily"
    private const val SYNC = "sync-periodic"
    private const val SYNC_SOON = "sync-soon"

    /**
     * Never throws: a till without background jobs still sells (backups and sync then run only
     * by hand). A release build once crashed here because R8 had removed a class WorkManager
     * needs (see proguard-rules.pro).
     */
    fun schedule(context: Context) = safely("Scheduling background jobs failed") {
        val wm = WorkManager.getInstance(context)
        val backup = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.KEEP, backup)
        val sync = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.KEEP, sync)
    }

    /** A sync a couple of minutes after a sale (KEEP: a busy till is not postponed forever). Off the main thread. */
    fun syncSoon(context: Context) = safely("Scheduling a sync failed") {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_SOON, ExistingWorkPolicy.KEEP, req)
    }

    /** The app already synced (auto sync, D-053): the fallback "sync soon" job is not needed. Off the main thread. */
    fun cancelSyncSoon(context: Context) = safely("Cancelling a sync failed") {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_SOON)
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(what, e)
        } catch (e: LinkageError) {
            Log.e(what, e)
        }
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
}

/** The automatic daily backup on this phone (D-044). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        LekasApp.graph(applicationContext).backups.backupIfDue()
        Result.success()
    } catch (e: com.lekaspos.domain.backup.BackupService.Postponed) {
        Result.retry() // a till in use: again after the back-off (30 s, doubling)
    } catch (e: Exception) {
        Log.e("Automatic backup failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

/** Background sync (references/sync.md §10): retried with backoff; a needed sign-in stops it until the user acts. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = LekasApp.graph(applicationContext)
        val provider = graph.sync.provider() ?: return Result.success()
        return try {
            graph.sync.sync(provider)
            Result.success()
        } catch (e: com.lekaspos.sync.AuthNeeded) {
            Result.failure() // the sync screen shows "sign-in needed"; the periodic job tries again later
        } catch (e: com.lekaspos.sync.SyncEngine.Problem) {
            Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
