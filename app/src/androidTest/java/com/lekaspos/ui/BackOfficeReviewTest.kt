package com.lekaspos.ui

import android.app.Activity
import android.app.UiAutomation
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.diag.DiagnosticsActivity
import com.lekaspos.ui.settings.SyncActivity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Back-office screen fixes of the 2026-10 review. Uses the app's own database (PIN login off). */
@RunWith(AndroidJUnit4::class)
class BackOfficeReviewTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext

    @Before
    fun setUp() = runBlocking {
        LekasApp.graph(ctx).settings.markSetupDone()
        LekasApp.graph(ctx).staff.load()
    }

    @After
    fun tearDown() = AppLanguage.set(ctx, AppLanguage.PHONE)

    /**
     * A back-office screen rotates in place (D-054). In a chosen language its resources must follow
     * the new orientation: a full copy of the configuration as the language override kept the old
     * one, and dialogs were sized for the old width.
     */
    @Test
    fun aRotatedScreenFollowsTheNewOrientationInItsLanguage() {
        AppLanguage.set(ctx, AppLanguage.MALAY)
        val ui = instr.uiAutomation
        ActivityScenario.launch(CategoriesActivity::class.java).use { scenario ->
            try {
                val first = activityOf(scenario)
                val wide = isWide(scenario)
                ui.setRotation(UiAutomation.ROTATION_FREEZE_90)
                if (!waitFor { isWide(scenario) != wide }) {
                    ui.setRotation(UiAutomation.ROTATION_FREEZE_0) // it was turned already
                    waitFor { isWide(scenario) != wide }
                }
                instr.waitForIdleSync()
                val nowWide = isWide(scenario)
                assertTrue(nowWide != wide, "the screen did not rotate")
                val expected = if (nowWide) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                waitFor { orientationOf(scenario) == expected }
                assertEquals(expected, orientationOf(scenario))
                val a = activityOf(scenario)
                assertSame(first, a, "rotation recreated the screen")
                assertEquals("Bahasa aplikasi", a.getString(R.string.settings_language))
            } finally {
                ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
                ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
            }
        }
    }

    /** What was typed survives a rebuilt form; switches with their own handler are left alone. */
    @Test
    fun aRebuiltFormGetsBackWhatWasTyped() {
        var text = ""
        var on = false
        var picked = -1
        var handled = true
        instr.runOnMainSync {
            val a = Form(ctx)
            a.text("Name", "old").setText("typed")
            a.switch("On", false).isChecked = true
            a.choice("Pick", listOf("a", "b", "c"), 0).setSelection(2)
            a.switch("Live", false) { }.isChecked = true
            val saved = a.save()
            val b = Form(ctx)
            val name = b.text("Name", "old")
            val sw = b.switch("On", false)
            val pick = b.choice("Pick", listOf("a", "b", "c"), 0)
            val live = b.switch("Live", false) { }
            b.restore(saved)
            text = name.text.toString()
            on = sw.isChecked
            picked = pick.selectedItemPosition
            handled = live.isChecked
        }
        assertEquals("typed", text)
        assertTrue(on)
        assertEquals(2, picked)
        assertFalse(handled)
    }

    /** Diagnostics is a guarded back-office screen now; the owner (no PINs) may use it. */
    @Test
    fun diagnosticsOpensForWhoMayUseIt() {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            var enabled = false
            waitFor {
                scenario.onActivity { enabled = it.findViewById<View>(R.id.run_quick).isEnabled }
                enabled
            }
            assertTrue(enabled, "the test buttons stayed off")
        }
    }

    /** Sync progress changes the texts in place: the form is not built again (taps were lost). */
    @Test
    fun syncProgressDoesNotRebuildTheScreen() {
        val sync = LekasApp.graph(ctx).sync
        ActivityScenario.launch(SyncActivity::class.java).use { scenario ->
            fun form(): View? {
                var v: View? = null
                scenario.onActivity { v = it.findViewById<FrameLayout>(R.id.content).getChildAt(0) }
                return v
            }
            waitFor { form() != null }
            val first = assertNotNull(form())
            try {
                sync.starting() // "Connecting…": a new status, the same layout
                instr.waitForIdleSync()
                assertSame(first, form())
            } finally {
                sync.notStarted()
            }
        }
    }

    private fun activityOf(scenario: ActivityScenario<out Activity>): Activity {
        var a: Activity? = null
        scenario.onActivity { a = it }
        return assertNotNull(a)
    }

    private fun isWide(scenario: ActivityScenario<out Activity>): Boolean {
        var wide = false
        scenario.onActivity { wide = it.window.decorView.width > it.window.decorView.height }
        return wide
    }

    private fun orientationOf(scenario: ActivityScenario<out Activity>): Int {
        var o = 0
        scenario.onActivity { o = it.resources.configuration.orientation }
        return o
    }

    /** Polls [done] for up to 10 s; returns its last answer. */
    private fun waitFor(done: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (done()) return true
            SystemClock.sleep(50L)
        }
        return done()
    }
}
