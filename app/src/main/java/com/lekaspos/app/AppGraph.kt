package com.lekaspos.app

import android.app.Application
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Manual dependency injection (references/architecture.md §2): app-wide singletons, created
 * lazily so Application.onCreate does no I/O.
 */
class AppGraph(private val app: Application, private val dbName: String = Schema.FILE_NAME) {

    /** Lives as long as the process; for work that must outlive a screen (e.g. saving a sale). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dbOpening: Deferred<Db> = appScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        Db.open(app, dbName, seedNames()).also { db ->
            // Changes queued for upload are sent soon (D-053) — only the app's own database, not test graphs.
            if (dbName == Schema.FILE_NAME) db.onOutboxCommit = { syncSoon() }
        }
    }

    /** The store database, opened on first use off the main thread. */
    suspend fun db(): Db = dbOpening.await()

    val settings: SettingsRepo by lazy { SettingsRepo(this, defaultLanguage()) }
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

    private fun defaultLanguage(): String {
        val locale = if (Build.VERSION.SDK_INT >= 24) {
            app.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            app.resources.configuration.locale
        }
        return if (locale?.language == "ms") "ms" else "en"
    }

    private fun seedNames() = SeedNames(
        owner = app.getString(R.string.seed_role_owner),
        manager = app.getString(R.string.seed_role_manager),
        cashier = app.getString(R.string.seed_role_cashier),
        cash = app.getString(R.string.seed_pm_cash),
        card = app.getString(R.string.seed_pm_card),
        ewallet = app.getString(R.string.seed_pm_ewallet),
        credit = app.getString(R.string.seed_pm_credit),
    )

    private companion object {
        const val SYNC_SOON_GAP_MS = 60_000L
    }
}
