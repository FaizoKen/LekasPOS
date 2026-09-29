package com.lekaspos.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val backup = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.KEEP, backup)
    }
}

/** The automatic daily backup on this phone (D-044). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        LekasApp.graph(applicationContext).backups.backupIfDue()
        Result.success()
    } catch (e: Exception) {
        Log.e("Automatic backup failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
