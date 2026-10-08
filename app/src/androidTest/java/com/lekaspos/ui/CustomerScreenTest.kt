package com.lekaspos.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.app.LekasApp
import com.lekaspos.core.display.CustomerView
import com.lekaspos.testing.TestDb
import com.lekaspos.ui.display.CustomerScreen
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.settings.SettingsActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-069: with a second display (here Android's simulated one, the same kind of display as Miracast or HDMI), the
 * customer screen shows the bill there while the till shows the selling screen, and stays when another screen of
 * the app opens over it. Saves pictures of it with the screenshots (CI artifact `screens-api*`).
 */
@RunWith(AndroidJUnit4::class)
class CustomerScreenTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val graph get() = LekasApp.graph(ctx)

    private fun shell(cmd: String) {
        instr.uiAutomation.executeShellCommand(cmd).use { pfd -> FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }
    }

    private fun waitFor(what: String, ms: Long = 15_000L, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            instr.runOnMainSync { ok = check() }
            if (ok) return
            SystemClock.sleep(200)
        }
        fail("timed out waiting for $what")
    }

    @Before
    fun setUp() = runBlocking {
        graph.settings.markSetupDone()
        com.lekaspos.app.ErrorReports.setConsent(ctx, false)
        graph.staff.load()
        shell("settings put global overlay_display_devices 1280x720/160")
        val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE) as DisplayManager
        waitFor("a second display") { dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty() }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync { graph.cart.clear() }
        shell("settings delete global overlay_display_devices")
    }

    private fun screen(): CustomerScreen? {
        var s: CustomerScreen? = null
        instr.runOnMainSync { s = graph.customerDisplay.showing }
        return s
    }

    private fun save(s: CustomerScreen, name: String) {
        instr.runOnMainSync {
            val root = s.window?.decorView ?: return@runOnMainSync
            if (root.width <= 0 || root.height <= 0) return@runOnMainSync
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "screens").apply { mkdirs() }
            FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun theCustomerSeesTheBillOnTheSecondScreen() {
        val db = runBlocking { graph.db() }
        val tea = TestDb.sellable(db, TestDb.product(db, "Teh Boh 250g", 1_250L))
        val milo = TestDb.sellable(db, TestDb.product(db, "Milo Tin 1.5kg", 3_890L))
        ActivityScenario.launch(SellActivity::class.java).use { scenario ->
            waitFor("the customer screen") { graph.customerDisplay.showing != null }
            val s = assertNotNull(screen())
            waitFor("the welcome") { s.shown == CustomerView.Welcome }
            save(s, "en-customer-welcome.png")

            instr.runOnMainSync {
                graph.cart.addProduct(milo)
                graph.cart.addProduct(tea)
            }
            waitFor("the bill") { (s.shown as? CustomerView.Bill)?.last?.name == "Teh Boh 250g" }
            val bill = s.shown as CustomerView.Bill
            assertEquals(listOf("Milo Tin 1.5kg", "Teh Boh 250g"), bill.lines.map { it.name })
            assertEquals(5_140L, bill.total)
            SystemClock.sleep(500)
            save(s, "en-customer-bill.png")

            // Another screen of the app over the selling screen: the customers still see the customer screen,
            // never a copy of the till's screen.
            scenario.onActivity { it.startActivity(Intent(it, SettingsActivity::class.java)) }
            waitFor("the settings screen holding the customer screen") { graph.customerDisplay.hostedBy is SettingsActivity && graph.customerDisplay.showing != null }
            SystemClock.sleep(1_500)
            assertTrue(screen() != null, "the customer screen went away under another screen")
        }
    }
}
