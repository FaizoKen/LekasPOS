package com.lekaspos.app

import android.app.Application
import android.os.Build
import android.os.SystemClock
import com.lekaspos.BuildConfig
import com.lekaspos.R
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Schema
import com.lekaspos.data.db.SeedNames
import com.lekaspos.domain.PermissionGate
import com.lekaspos.domain.SettingsRepo
import com.lekaspos.domain.StaffSession
import com.lekaspos.domain.backup.BackupService
import com.lekaspos.domain.customer.CustomerService
import com.lekaspos.domain.inventory.InventoryService
import com.lekaspos.domain.products.ProductCsvService
import com.lekaspos.domain.promo.PromotionService
import com.lekaspos.domain.report.DailyReportUpload
import com.lekaspos.domain.report.ReportService
import com.lekaspos.domain.sale.SaleActions
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.sell.PopularItems
import com.lekaspos.domain.sell.PriceCheck
import com.lekaspos.domain.shift.ShiftService
import com.lekaspos.domain.staff.StaffService
import com.lekaspos.hw.printer.PrinterService
import com.lekaspos.hw.scanner.SppScanner
import com.lekaspos.perf.PerfRunner
import com.lekaspos.sync.AutoSync
import com.lekaspos.sync.SyncEngine
import com.lekaspos.ui.common.PictureCache
import com.lekaspos.ui.display.CustomerDisplay
import com.lekaspos.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manual dependency injection (references/architecture.md §2): app-wide singletons, created
 * lazily so Application.onCreate does no I/O.
 */
class AppGraph(private val app: Application, private val dbName: String = Schema.FILE_NAME) {

    /** Lives as long as the process; for work that must outlive a screen (e.g. saving a sale). */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Background work that fails (a full disk while parking a bill, say) is logged; it never
            // takes the till down with it. Debug builds still crash, so tests see every failure.
            CoroutineExceptionHandler { _, e ->
                Log.e("Background work failed", e)
                if (BuildConfig.DEBUG) throw e
            },
    )

    @Volatile
    private var dbOpening: Deferred<Db> = opening()

    private fun opening(): Deferred<Db> = appScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        Db.open(app, dbName, seedNames()).also { db ->
            // Changes queued for upload are sent soon (D-053) — only the app's own database, not test graphs.
            if (dbName == Schema.FILE_NAME) db.onOutboxCommit = { syncSoon() }
        }
    }

    /**
     * The store database, opened on first use off the main thread. A failed open is tried again by
     * the next caller: it was kept failed for the life of the process, so one full disk at start
     * (since freed) left every screen failing until Android ended the app (2026-10 review).
     */
    suspend fun db(): Db {
        val opening = dbOpening
        return try {
            opening.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(this) { if (dbOpening === opening) dbOpening = opening() }
            throw e
        }
    }

    /** How many times this phone has started (Android 7+), or null: tells waits apart across restarts. */
    fun bootCount(): Int? = if (Build.VERSION.SDK_INT >= 24) {
        android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            .takeIf { it >= 0 }
    } else {
        null
    }

    val settings: SettingsRepo by lazy { SettingsRepo(this) { AppLanguage.screens(app) } }
    val staff: StaffSession by lazy { StaffSession(this) }
    val permissions: PermissionGate by lazy { PermissionGate(staff) }
    val staffAdmin: StaffService by lazy { StaffService(this) }
    val shifts: ShiftService by lazy { ShiftService(this) }
    val customers: CustomerService by lazy { CustomerService(this) }
    val reports: ReportService by lazy { ReportService(this) }
    val productCsv: ProductCsvService by lazy { ProductCsvService(this) }
    val cart: CartSession by lazy { CartSession(this) }
    val priceCheck: PriceCheck by lazy { PriceCheck(this) }
    val popular: PopularItems by lazy { PopularItems(this) }
    val promotions: PromotionService by lazy { PromotionService(this) }
    val checkout: CheckoutService by lazy { CheckoutService(this) }
    val sales: SaleActions by lazy { SaleActions(this) }
    val inventory: InventoryService by lazy { InventoryService(this) }
    val printer: PrinterService by lazy { PrinterService(app, this) }
    val sppScanner: SppScanner by lazy { SppScanner(app, this) }
    val perfRunner: PerfRunner by lazy { PerfRunner(app, appScope) }
    val backups: BackupService by lazy { BackupService(this, app) }
    val sync: SyncEngine by lazy { SyncEngine(this, app) }

    val autoSync: AutoSync by lazy { AutoSync(this, app) }

    /** New versions from the app's GitHub releases (D-059). */
    val updates: AppUpdates by lazy { AppUpdates(app) }

    /** The daily sales report to the owner's Google Drive (D-065). */
    val dailyReport: DailyReportUpload by lazy { DailyReportUpload(this, app) }

    /** Product pictures ready to draw (D-066). */
    val pictures: PictureCache by lazy { PictureCache(this) }

    /** The customer screen on a second display (D-069); LekasApp registers it for the activities. */
    val customerDisplay: CustomerDisplay by lazy { CustomerDisplay(app, this) }

    private val lastSyncSoon = AtomicLong(-SYNC_SOON_GAP_MS)

    /**
     * After any change queued for upload (a sale, a price, a count …; called by the database right
     * after the commit): a sync in the app within seconds ([AutoSync], D-053), and a WorkManager job
     * a couple of minutes later in case the app is closed first (at most once a minute). Only for
     * the app's own database (not test graphs).
     */
    fun syncSoon() {
        if (dbName != Schema.FILE_NAME) return
        autoSync.changed()
        val now = SystemClock.elapsedRealtime()
        val last = lastSyncSoon.get()
        if (now - last < SYNC_SOON_GAP_MS || !lastSyncSoon.compareAndSet(last, now)) return
        appScope.launch(Dispatchers.IO) { if (db().syncEnabled) Work.syncSoon(app) }
    }

    /** Everything was sent: the next change schedules the fallback job again at once. */
    fun syncSoonDone() {
        lastSyncSoon.set(-SYNC_SOON_GAP_MS)
    }

    private val _catalogChanges = MutableStateFlow(0)

    /**
     * Counts changes to products, categories or stock made away from the selling screen (another
     * till's, an import): its tiles and chips are then read again ([catalogChanged]).
     */
    val catalogChanges: StateFlow<Int> = _catalogChanges

    fun catalogChanged() {
        pictures.catalogChanged() // pictures from another till may have arrived
        _catalogChanges.update { it + 1 }
    }

    /** The built-in rows' names in the app's language as chosen now (the Application keeps the one it started with). */
    fun seedNames(): SeedNames {
        // Built in the language the screens use now: the Application keeps the one it started with,
        // and following the phone [AppLanguage.wrap] leaves that as it is.
        val ctx = AppLanguage.inLanguage(app, AppLanguage.screens(app))
        return SeedNames(
            owner = ctx.getString(R.string.seed_role_owner),
            manager = ctx.getString(R.string.seed_role_manager),
            cashier = ctx.getString(R.string.seed_role_cashier),
            cash = ctx.getString(R.string.seed_pm_cash),
            card = ctx.getString(R.string.seed_pm_card),
            ewallet = ctx.getString(R.string.seed_pm_ewallet),
            credit = ctx.getString(R.string.seed_pm_credit),
        )
    }

    private companion object {
        const val SYNC_SOON_GAP_MS = 60_000L
    }
}
