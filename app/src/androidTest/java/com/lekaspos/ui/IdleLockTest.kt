package com.lekaspos.ui

import android.app.Activity
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lekaspos.app.LekasApp
import com.lekaspos.data.db.Seed
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.staff.LockActivity
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The idle auto-lock across the phone's screen turning off and on, for real (power key events on
 * the emulator): when the phone wakes after the idle time, the sign-in shows by itself — no tap
 * needed (reported on 1.3.0: the till stayed signed in after the screen had been off).
 */
@RunWith(AndroidJUnit4::class)
class IdleLockTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext

    @Test
    fun theTillLocksWhenThePhoneWakesAfterTheIdleTime() {
        val graph = LekasApp.graph(ctx)
        val owner = Seed.Ids.STAFF_OWNER
        runBlocking {
            graph.settings.markSetupDone()
            graph.staff.load()
            graph.staffAdmin.setPin(owner, "2468") // the store's first PIN signs the owner in
            graph.settings.saveDevice(graph.settings.device.value.copy(autoLockMinutes = 1))
        }
        try {
            assertTrue(graph.staff.state.value.current != null)
            ActivityScenario.launch(SellActivity::class.java).use {
                instrumentation.waitForIdleSync()
                key(KeyEvent.KEYCODE_SLEEP)
                SystemClock.sleep(2_000L) // the phone's screen is off
                graph.staff.idleFor(2 * 60_000L) // ... for longer than the idle time
                key(KeyEvent.KEYCODE_WAKEUP)
                dismissKeyguard()
                // No tap: the sign-in shows by itself as the phone wakes.
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (!graph.staff.state.value.locked && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50L)
                assertTrue(graph.staff.state.value.locked, "still signed in after waking the phone")
                var lockShown = false
                while (!lockShown && SystemClock.uptimeMillis() < deadline + 5_000L) {
                    instrumentation.runOnMainSync { lockShown = resumed().any { it is LockActivity } }
                    if (!lockShown) SystemClock.sleep(50L)
                }
                assertTrue(lockShown, "the sign-in screen did not show")
            }
        } finally {
            key(KeyEvent.KEYCODE_WAKEUP)
            dismissKeyguard()
            // PIN login off again: the other screen tests share this database.
            runBlocking {
                if (graph.staff.state.value.locked) graph.staff.signIn(owner, "2468")
                graph.staffAdmin.setPin(owner, null)
                graph.settings.saveDevice(graph.settings.device.value.copy(autoLockMinutes = 0))
            }
            instrumentation.runOnMainSync { resumed().filterIsInstance<LockActivity>().forEach { it.finish() } }
            instrumentation.waitForIdleSync()
        }
    }

    private fun resumed(): Collection<Activity> =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)

    /** A key press as the phone's own keys send it (sleep, wake), through the test's UiAutomation. */
    private fun key(code: Int) {
        val ua = instrumentation.uiAutomation
        val now = SystemClock.uptimeMillis()
        ua.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true)
        ua.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true)
        SystemClock.sleep(300L)
    }

    /**
     * The emulator's swipe lock screen goes away. Android 6+: `wm dismiss-keyguard`. Below, the menu
     * key does it (reading a shell command there hung this test on the Android 5 emulator).
     */
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= 23) {
            val out = instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard")
            ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        } else {
            key(KeyEvent.KEYCODE_MENU)
        }
        SystemClock.sleep(500L)
    }
}
