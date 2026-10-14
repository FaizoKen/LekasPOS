package com.lekaspos.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.TileColor
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.db.Seed
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductLook
import com.lekaspos.data.product.ProductLookDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.inventory.InventoryActivity
import com.lekaspos.ui.inventory.InventoryUi
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.products.ProductListActivity
import com.lekaspos.ui.products.PromotionEditActivity
import com.lekaspos.ui.products.PromotionsActivity
import com.lekaspos.ui.reports.ReportsActivity
import com.lekaspos.ui.sell.PaymentDialog
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.settings.BackupActivity
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.ui.settings.SetupActivity
import com.lekaspos.ui.settings.StoreSettingsActivity
import com.lekaspos.ui.settings.SyncActivity
import com.lekaspos.ui.shift.ShiftActivity
import com.lekaspos.ui.staff.StaffActivity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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

    // The first-run welcome screen would open over the selling screen on a fresh emulator.
    @Before
    fun skipFirstRunSetup() = runBlocking {
        LekasApp.graph(ctx).settings.markSetupDone()
        com.lekaspos.app.ErrorReports.setConsent(ctx, false) // nor the error-reports question (D-057)
        LekasApp.graph(ctx).staff.load() // as the selling screen does at start: nothing is allowed before
    }

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
        "setup" to SetupActivity::class.java,
        "promotions" to PromotionsActivity::class.java,
        "promotion-edit" to PromotionEditActivity::class.java,
    )

    @Test
    fun mainScreensInBothLanguages() {
        val graph = LekasApp.graph(ctx)
        runBlocking {
            val db = graph.db()
            graph.cart.load()
            if (graph.cart.state.value.cart.items.isEmpty()) {
                // A shop's categories: their chips show beside Search (D-078).
                val now = System.currentTimeMillis()
                val drinks = db.write { tx -> CategoryDao.insert(tx, "Minuman", TileColor.TEAL, 0, now) }
                val kitchen = db.write { tx -> CategoryDao.insert(tx, "Barang dapur", TileColor.NONE, 0, now) }
                val breakfast = db.write { tx -> CategoryDao.insert(tx, "Sarapan", TileColor.AMBER, 0, now) }
                val items = listOf(
                    Triple("Milo Tin 1.5kg", 3_890L, drinks), Triple("Roti Gardenia Putih", 420L, breakfast),
                    Triple("Telur Gred A (30 biji)", 1_650L, breakfast),
                )
                for ((name, price, category) in items) {
                    val id = TestDb.product(db, name, price, categoryId = category)
                    // Stock on the shelf and in the storeroom: the product form and Change stock show it (D-080).
                    db.write(reserveIds = 2L) { tx -> StockDao.insertMovement(tx, id, MovementKind.OPENING, 24_000L, price / 2, null, null, null, System.currentTimeMillis()) }
                    graph.cart.addProduct(TestDb.sellable(db, id))
                }
                // More products for the tiles, with colours and a picture (D-066).
                val more = listOf(
                    Triple("Beras Faiza 10kg", TileColor.BROWN, kitchen), Triple("Gula Pasir 1kg", TileColor.NONE, kitchen),
                    Triple("Minyak Masak Saji 5kg", TileColor.AMBER, kitchen), Triple("Teh Boh 250g", TileColor.GREEN, drinks),
                    Triple("Kopi O Cap Kapal 200g", TileColor.BROWN, drinks), Triple("Maggi Kari 5 pek", TileColor.ORANGE, kitchen),
                    Triple("Sardin Ayam 425g", TileColor.RED, kitchen), Triple("Susu Pekat Manis", TileColor.BLUE, drinks),
                    Triple("Air Mineral 1.5L", TileColor.TEAL, drinks),
                )
                val picture = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888).apply {
                    val c = android.graphics.Canvas(this)
                    c.drawColor(0xFF8D6E63.toInt())
                    c.drawCircle(120f, 120f, 80f, android.graphics.Paint().apply { color = 0xFFFFF3CF.toInt() })
                }
                val jpeg = java.io.ByteArrayOutputStream().also { picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                for ((i, entry) in more.withIndex()) {
                    val id = TestDb.product(db, entry.first, 500L + i * 250L, categoryId = entry.third)
                    db.write(reserveIds = 2L) { tx ->
                        val now = System.currentTimeMillis()
                        val image = if (i == 0) ProductLookDao.addImage(tx, id, android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP), null, now) else null
                        ProductLookDao.update(tx, id, ProductLook(), ProductLook(entry.second, image), now)
                    }
                }
            }
        }
        val methods = runBlocking { graph.db().read { PaymentMethodDao.active(it) } }
        // A product with stock: its form (Stock under the price) and Change stock with a count typed (D-080).
        // The test shop's Milo (in Minuman): another test may have made one without a category.
        val milo = runBlocking { graph.db().read { ProductDao.search(it, "Milo", 20, includeInactive = true) } }
            .let { found -> (found.firstOrNull { it.categoryId != null } ?: found.first()).id }
        // 24 in the app whatever ran before (the shop is set up only once).
        runBlocking { graph.db().write(reserveIds = 2L) { tx -> StockDao.insertCount(tx, milo, 24_000L, null, null, null, System.currentTimeMillis()) } }
        val miloStock = runBlocking { InventoryUi.product(graph, milo) } ?: error("no product")
        // The API 21 CI emulator has no external storage: fall back to internal files (CI copies them with run-as).
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "screens").apply { mkdirs() }
        for (lang in listOf(AppLanguage.ENGLISH, AppLanguage.MALAY)) {
            AppLanguage.set(ctx, lang)
            for ((name, cls) in screens) shoot(Intent(ctx, cls), File(dir, "$lang-$name.png"))
            val newProduct = Intent(ctx, ProductEditActivity::class.java).putExtra(ProductEditActivity.EXTRA_BARCODE, "9556001234567")
            shoot(newProduct, File(dir, "$lang-product-new.png"))
            shoot(ProductEditActivity.newIntent(ctx, productId = milo), File(dir, "$lang-product-edit.png")) { a ->
                // Scrolled to the Stock section: In stock now, Change stock, Stock history.
                val form = a.findViewById<android.view.ViewGroup>(R.id.content).getChildAt(0) as android.widget.ScrollView
                val column = form.getChildAt(0) as android.view.ViewGroup
                val section = (0 until column.childCount).map { column.getChildAt(it) }
                    .first { it is android.widget.TextView && it.text.toString() == a.getString(R.string.product_stock_section) }
                form.scrollTo(0, section.top - (8 * a.resources.displayMetrics.density).toInt())
            }
            shoot(ProductEditActivity.newIntent(ctx, productId = milo), File(dir, "$lang-change-stock.png")) { a ->
                val d = InventoryUi.askChange(a, miloStock) {}
                var t = SystemClock.uptimeMillis()
                for (code in listOf(KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_1)) {
                    t += 300 // a person's typing
                    d.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                    d.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
                }
            }
            // The items (on a phone after Items; two panes show them anyway) and the search box open in the chips'
            // place with "mi" typed (D-078).
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-sell-items.png")) {
                it.findViewById<View>(R.id.btn_browse).takeIf { b -> b.visibility == View.VISIBLE }?.performClick()
            }
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-sell-search.png")) {
                it.findViewById<View>(R.id.btn_search).performClick()
                it.findViewById<android.widget.EditText>(R.id.search).setText("mi")
            }
            // The payment over the bill: how the customer pays (D-064).
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-pay.png")) { it.findViewById<View>(R.id.btn_pay).performClick() }
            // The Menu: every job as a tile in its group (D-074).
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-menu.png")) { it.findViewById<View>(R.id.btn_menu).performClick() }
            // A dialog's buttons have a button's shape, the main one filled (D-076; Android drew bare words).
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-dialog.png")) { a ->
                val n = graph.cart.state.value.cart.items.size
                Dialogs.confirm(
                    a, a.getString(R.string.clear_confirm_title),
                    a.resources.getQuantityString(R.plurals.clear_confirm_message, n, n), a.getString(R.string.clear_yes),
                ) {}
            }
            // Its second step: "Other amount", RM100 typed — the change shows.
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-pay-cash.png")) { a ->
                val total = graph.cart.state.value.priced.total
                val d = PaymentDialog(a, total, graph.settings.store.value.currency, methods) { _, _ -> }.show()
                d.findViewById<View>(R.id.pay_other).performClick()
                var t = SystemClock.uptimeMillis()
                for (code in listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_0)) {
                    t += 300 // a person's typing: faster keys are a scanner's, and dropped
                    d.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                    d.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
                }
            }
        }
        // Pay the bill: the empty bill then shows how to start and the last sale's change.
        runBlocking {
            val total = graph.cart.state.value.priced.total
            val given = (total / 10_000L + 1L) * 10_000L
            val s = Settlement.cash(total, given, 5L) as Settlement.Result.Settled
            graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, given, s.change)), s.rounding)
        }
        for (lang in listOf(AppLanguage.ENGLISH, AppLanguage.MALAY)) {
            AppLanguage.set(ctx, lang)
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-sell-empty.png"))
        }
        // A bill held: on the empty bill Hold reads "Held bills (1)". Thrown away after, for the tests that follow.
        runBlocking {
            val db = graph.db()
            val id = db.read { ProductDao.search(it, "Teh Boh", 1) }.first().id
            graph.cart.addProduct(TestDb.sellable(db, id))
            graph.cart.hold("Aminah")
        }
        for (lang in listOf(AppLanguage.ENGLISH, AppLanguage.MALAY)) {
            AppLanguage.set(ctx, lang)
            shoot(Intent(ctx, SellActivity::class.java), File(dir, "$lang-sell-held.png"))
        }
        runBlocking { for (bill in graph.cart.heldBills()) graph.cart.deleteHeld(bill.id) }
    }

    /** The launcher icon as this Android draws it (API 21: the legacy vector; 26+: adaptive + mask). */
    @Test
    fun appIcon() {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "screens").apply { mkdirs() }
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "app-icon.png")).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun shoot(intent: Intent, file: File, action: (Activity) -> Unit = {}) {
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instr.waitForIdleSync()
            Thread.sleep(1_200) // lists load off the main thread
            instr.waitForIdleSync()
            scenario.onActivity(action)
            instr.waitForIdleSync()
            Thread.sleep(600)
            val bitmap: Bitmap? = instr.uiAutomation.takeScreenshot()
            if (bitmap != null) FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
    }
}
