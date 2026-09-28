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
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.products.ProductListActivity
import com.lekaspos.ui.sales.SalesActivity
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.settings.AuditLogActivity
import com.lekaspos.ui.settings.PrinterSettingsActivity
import com.lekaspos.ui.settings.ScannerSettingsActivity
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.ui.settings.StoreSettingsActivity
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every screen on the emulator (API 21 and 36 in CI): catches layout, style and
 * lifecycle problems that JVM tests cannot see. Uses the app's own database.
 */
@RunWith(AndroidJUnit4::class)
class ScreensSmokeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open(cls: Class<out Activity>, extras: Intent.() -> Unit = {}, check: (Activity) -> Unit = {}) {
        ActivityScenario.launch<Activity>(Intent(ctx, cls).apply(extras)).use { scenario ->
            scenario.onActivity { check(it) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun secondaryScreensOpen() {
        for (cls in listOf(
            ProductListActivity::class.java, CategoriesActivity::class.java, TaxRatesActivity::class.java,
            SalesActivity::class.java, SettingsActivity::class.java, StoreSettingsActivity::class.java,
            PrinterSettingsActivity::class.java, ScannerSettingsActivity::class.java, AuditLogActivity::class.java,
        )) {
            open(cls)
        }
        open(ProductEditActivity::class.java, { putExtra(ProductEditActivity.EXTRA_BARCODE, "9556001234567") })
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
