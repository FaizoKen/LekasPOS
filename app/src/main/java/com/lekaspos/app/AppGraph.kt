package com.lekaspos.app

import android.app.Application
import com.lekaspos.R
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.SeedNames
import com.lekaspos.perf.PerfRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Manual dependency injection (references/architecture.md §2): app-wide singletons, created
 * lazily so Application.onCreate does no I/O.
 */
class AppGraph(private val app: Application) {

    /** Lives as long as the process; for work that must outlive a screen (e.g. saving a sale). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dbOpening: Deferred<Db> = appScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        Db.open(app, seedNames = seedNames())
    }

    /** The store database, opened on first use off the main thread. */
    suspend fun db(): Db = dbOpening.await()

    val perfRunner: PerfRunner by lazy { PerfRunner(app, appScope) }

    private fun seedNames() = SeedNames(
        owner = app.getString(R.string.seed_role_owner),
        manager = app.getString(R.string.seed_role_manager),
        cashier = app.getString(R.string.seed_role_cashier),
        cash = app.getString(R.string.seed_pm_cash),
        card = app.getString(R.string.seed_pm_card),
        ewallet = app.getString(R.string.seed_pm_ewallet),
        credit = app.getString(R.string.seed_pm_credit),
    )
}
