package com.lekaspos.ui

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.R
import com.lekaspos.app.LekasApp
import com.lekaspos.core.inventory.ReceiveDraft
import com.lekaspos.testing.TestDb
import kotlinx.coroutines.runBlocking
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.inventory.CountActivity
import com.lekaspos.ui.inventory.CountReportActivity
import com.lekaspos.ui.inventory.CountSessionsActivity
import com.lekaspos.ui.inventory.InventoryActivity
import com.lekaspos.ui.inventory.LowStockActivity
import com.lekaspos.ui.inventory.MovementsActivity
import com.lekaspos.ui.inventory.ProductPickActivity
import com.lekaspos.ui.inventory.PurchaseDetailActivity
import com.lekaspos.ui.inventory.PurchasesActivity
import com.lekaspos.ui.inventory.ReceiveActivity
import com.lekaspos.ui.inventory.StockHistoryActivity
import com.lekaspos.ui.inventory.SuppliersActivity
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.products.ProductListActivity
import com.lekaspos.ui.products.PromotionEditActivity
import com.lekaspos.ui.products.PromotionsActivity
import com.lekaspos.ui.sales.SalesActivity
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.settings.AuditLogActivity
import com.lekaspos.ui.settings.BackupActivity
import com.lekaspos.ui.settings.PrinterSettingsActivity
import com.lekaspos.ui.settings.ScannerSettingsActivity
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.ui.settings.SetupActivity
import com.lekaspos.ui.settings.StoreSettingsActivity
import com.lekaspos.ui.settings.SyncActivity
import com.lekaspos.ui.products.ProductImportActivity
import com.lekaspos.ui.reports.ReportsActivity
import com.lekaspos.ui.reports.SlowMoversActivity
import com.lekaspos.ui.shift.ShiftActivity
import com.lekaspos.ui.shift.ShiftReportActivity
import com.lekaspos.ui.shift.ShiftsActivity
import com.lekaspos.ui.staff.LockActivity
import com.lekaspos.ui.staff.RoleEditActivity
import com.lekaspos.ui.staff.RolesActivity
import com.lekaspos.ui.staff.StaffActivity
import com.lekaspos.ui.staff.StaffEditActivity
import com.lekaspos.ui.customers.CustomerActivity
import com.lekaspos.ui.customers.CustomersActivity
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.db.Seed
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every screen on the emulator (API 21 and 36 in CI): catches layout, style and
 * lifecycle problems that JVM tests cannot see. Uses the app's own database.
 */
@RunWith(AndroidJUnit4::class)
class ScreensSmokeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // The first-run welcome screen would open over the selling screen on a fresh emulator.
    @Before
    fun skipFirstRunSetup() = runBlocking { LekasApp.graph(ctx).settings.markSetupDone() }

    private fun open(cls: Class<out Activity>, extras: Intent.() -> Unit = {}, check: (Activity) -> Unit = {}) =
        open(Intent(ctx, cls).apply(extras), check)

    private fun open(intent: Intent, check: (Activity) -> Unit = {}) {
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            scenario.onActivity { check(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun secondaryScreensOpen() {
        for (cls in listOf(
            ProductListActivity::class.java, CategoriesActivity::class.java, TaxRatesActivity::class.java,
            SalesActivity::class.java, SettingsActivity::class.java, StoreSettingsActivity::class.java,
            PrinterSettingsActivity::class.java, ScannerSettingsActivity::class.java, AuditLogActivity::class.java, BackupActivity::class.java, SyncActivity::class.java, SetupActivity::class.java,
            PromotionsActivity::class.java, PromotionEditActivity::class.java,
            InventoryActivity::class.java, ReceiveActivity::class.java, SuppliersActivity::class.java, PurchasesActivity::class.java,
            CountSessionsActivity::class.java, LowStockActivity::class.java, MovementsActivity::class.java, ProductPickActivity::class.java,
        )) {
            open(cls)
        }
        open(ProductEditActivity::class.java, { putExtra(ProductEditActivity.EXTRA_BARCODE, "9556001234567") })
    }

    @Test
    fun stockScreensOpenWithTheirData() {
        val graph = LekasApp.graph(ctx)
        val db = runBlocking { graph.db() }
        val product = TestDb.product(db, "Smoke test item", 100L)
        val session = runBlocking { graph.inventory.startCount("Smoke test", null) }
        runBlocking { graph.inventory.count(session, product, 3_000L) }
        val draft = ReceiveDraft().add(1L, product, "Smoke test item", "pcs", 2_000L, 50L).first
        val purchase = runBlocking { graph.inventory.receive(draft) }
        open(StockHistoryActivity.intent(ctx, product))
        open(CountActivity.intent(ctx, session))
        open(CountReportActivity.intent(ctx, session))
        open(PurchaseDetailActivity.intent(ctx, purchase))
    }

    @Test
    fun staffShiftAndCustomerScreensOpen() {
        // PIN login stays off here: the app database is shared with the other screen tests.
        val graph = LekasApp.graph(ctx)
        val customer = runBlocking { graph.customers.save(null, Customer(0L, "Smoke test customer", creditLimit = 10_000L)) }
        val shift = runBlocking {
            graph.shifts.load()
            graph.shifts.current.value ?: graph.shifts.open(1_000L)
        }
        for (cls in listOf(
            StaffActivity::class.java, RolesActivity::class.java, ShiftActivity::class.java, ShiftsActivity::class.java,
            CustomersActivity::class.java,
        )) {
            open(cls)
        }
        open(StaffEditActivity.intent(ctx, 0L))
        open(StaffEditActivity.intent(ctx, Seed.Ids.STAFF_OWNER))
        open(RoleEditActivity.intent(ctx, Seed.Ids.ROLE_MANAGER))
        open(ShiftReportActivity.intent(ctx, shift.id))
        open(CustomerActivity.intent(ctx, customer.id))
        open(CustomersActivity.pickIntent(ctx))
        runBlocking { graph.shifts.close(1_000L, null) }
    }

    @Test
    fun reportAndImportScreensOpen() {
        val today = com.lekaspos.core.time.Days.epochDay(System.currentTimeMillis(), java.util.TimeZone.getDefault())
        open(ReportsActivity::class.java)
        open(SlowMoversActivity.intent(ctx, com.lekaspos.core.report.Period(today - 29, today + 1)))
        val file = java.io.File(ctx.cacheDir, "smoke-import.csv")
        file.writeText("name,price,barcodes\nSmoke test import,1.00,2999000000017\n")
        open(ProductImportActivity.intent(ctx, android.net.Uri.fromFile(file)))
        file.delete()
    }

    @Test
    fun lockScreenAsksForAPinWhileLocked() {
        val graph = LekasApp.graph(ctx)
        val owner = Seed.Ids.STAFF_OWNER
        runBlocking {
            graph.staff.load()
            graph.staffAdmin.setPin(owner, "2468")
            graph.staff.lock()
        }
        try {
            assertTrue(graph.staff.state.value.locked)
            open(LockActivity::class.java) { a -> assertFalse(a.isFinishing) }
        } finally {
            // PIN login off again: the other screen tests share this database.
            runBlocking {
                graph.staff.signIn(owner, "2468")
                graph.staffAdmin.setPin(owner, null)
            }
        }
        assertFalse(graph.staff.state.value.loginRequired)
    }

    @Test
    fun sellingScreenTakesKeyboardWedgeScans() {
        open(SellActivity::class.java) { activity ->
            val search = activity.findViewById<EditText>(R.id.search)
            search.setText("milo")
            search.setText("")
            // A HID scanner "types" digits a few milliseconds apart and ends with Enter.
            val t0 = SystemClock.uptimeMillis()
            val keys = listOf(KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2)
            for ((i, k) in keys.withIndex()) {
                val at = t0 + i * 8L
                activity.dispatchKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, k, 0))
                activity.dispatchKeyEvent(KeyEvent(at, at + 2, KeyEvent.ACTION_UP, k, 0))
            }
            val end = t0 + 60L
            activity.dispatchKeyEvent(KeyEvent(end, end, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            // The scan never landed in the search field.
            assertEquals("", search.text.toString())
        }
    }
}
