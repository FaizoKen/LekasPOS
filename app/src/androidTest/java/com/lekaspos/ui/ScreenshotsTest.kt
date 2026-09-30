package com.lekaspos.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.testing.TestDb
import com.lekaspos.ui.inventory.InventoryActivity
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.products.ProductListActivity
import com.lekaspos.ui.reports.ReportsActivity
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.settings.BackupActivity
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.ui.settings.StoreSettingsActivity
import com.lekaspos.ui.settings.SyncActivity
import com.lekaspos.ui.shift.ShiftActivity
import com.lekaspos.ui.staff.StaffActivity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of the main screens in English and Malay (Phase 7 layout review: Malay is often
 * longer). Saved to the app's external files dir `screens/`; CI pulls and uploads them.
 * Not a pass/fail check beyond "every screen opens and draws".
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotsTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext

    @After
    fun tearDown() = AppLanguage.set(ctx, AppLanguage.PHONE)

    private val screens = listOf(
        "sell" to SellActivity::class.java,
        "settings" to SettingsActivity::class.java,
        "store" to StoreSettingsActivity::class.java,
        "products" to ProductListActivity::class.java,
        "inventory" to InventoryActivity::class.java,
        "reports" to ReportsActivity::class.java,
        "staff" to StaffActivity::class.java,
        "shift" to ShiftActivity::class.java,
        "sync" to SyncActivity::class.java,
        "backup" to BackupActivity::class.java,
    )

    @Test
    fun mainScreensInBothLanguages() {
        val graph = LekasApp.graph(ctx)
        runBlocking {
            val db = graph.db()
            graph.cart.load()
            if (graph.cart.state.value.cart.items.isEmpty()) {
                val items = listOf("Milo Tin 1.5kg" to 3_890L, "Roti Gardenia Putih" to 420L, "Telur Gred A (30 biji)" to 1_650L)
                for ((name, price) in items) {
                    graph.cart.addProduct(TestDb.sellable(db, TestDb.product(db, name, price)))
                }
            }
        }
        // The API 21 CI emulator has no external storage: fall back to internal files (CI copies them with run-as).
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "screens").apply { mkdirs() }
        for (lang in listOf(AppLanguage.ENGLISH, AppLanguage.MALAY)) {
            AppLanguage.set(ctx, lang)
            for ((name, cls) in screens) shoot(Intent(ctx, cls), File(dir, "$lang-$name.png"))
            val newProduct = Intent(ctx, ProductEditActivity::class.java).putExtra(ProductEditActivity.EXTRA_BARCODE, "9556001234567")
            shoot(newProduct, File(dir, "$lang-product-new.png"))
        }
    }

    private fun shoot(intent: Intent, file: File) {
        ActivityScenario.launch<Activity>(intent).use {
            instr.waitForIdleSync()
            Thread.sleep(1_200) // lists load off the main thread
            instr.waitForIdleSync()
            val bitmap: Bitmap? = instr.uiAutomation.takeScreenshot()
            if (bitmap != null) FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
    }
}
