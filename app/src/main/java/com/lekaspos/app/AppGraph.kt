package com.lekaspos.app

import android.app.Application
import android.os.Build
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
import com.lekaspos.domain.report.ReportService
import com.lekaspos.domain.sale.SaleActions
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.shift.ShiftService
import com.lekaspos.domain.staff.StaffService
import com.lekaspos.hw.printer.PrinterService
import com.lekaspos.hw.scanner.SppScanner
import com.lekaspos.perf.PerfRunner
import com.lekaspos.sync.SyncEngine
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
class AppGraph(private val app: Application, private val dbName: String = Schema.FILE_NAME) {

    /** Lives as long as the process; for work that must outlive a screen (e.g. saving a sale). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dbOpening: Deferred<Db> = appScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        Db.open(app, dbName, seedNames())
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
    val checkout: CheckoutService by lazy { CheckoutService(this) }
    val sales: SaleActions by lazy { SaleActions(this) }
    val inventory: InventoryService by lazy { InventoryService(this) }
    val printer: PrinterService by lazy { PrinterService(app, this) }
    val sppScanner: SppScanner by lazy { SppScanner(app, this) }
    val perfRunner: PerfRunner by lazy { PerfRunner(app, appScope) }
    val backups: BackupService by lazy { BackupService(this, app) }
    val sync: SyncEngine by lazy { SyncEngine(this, app) }

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
}
