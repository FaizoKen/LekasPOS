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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The idle auto-lock when the phone's screen was off: as the phone wakes after the idle time, the
 * sign-in shows by itself — no tap needed (reported on 1.3.0: the till stayed signed in after the
 * screen had been off). Phones differ: some stop the app while their screen is off (it starts
 * again on waking), many only pause it (it only resumes) — both are covered.
 */
@RunWith(AndroidJUnit4::class)
class IdleLockTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val graph get() = LekasApp.graph(ctx)

    /** A phone that only pauses the app while its screen is off: waking it only resumes the screen. */
    @Test
    fun aScreenThatWasOnlyPausedLocksWhenItResumes() = withPinAndAutoLock {
        ActivityScenario.launch(SellActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            var screen: Activity? = null
            scenario.onActivity { screen = it }
            val a = screen ?: throw AssertionError("no selling screen")
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a) } // the screen goes off
            graph.staff.idleFor(2 * 60_000L) // ... for longer than the idle time
            instrumentation.runOnMainSync { instrumentation.callActivityOnResume(a) } // and on again
            assertTrue(graph.staff.state.value.locked, "still signed in after the screen resumed")
            assertLockShows()
        }
    }

    /**
     * The phone really sleeps and wakes (power key events). Android 6+ only: there the emulator's
     * lock screen goes away cleanly; on Android 5 it stayed and the next screen tests never ran.
     */
    @Test
    fun theTillLocksWhenThePhoneWakesAfterTheIdleTime() = withPinAndAutoLock {
        assumeTrue(Build.VERSION.SDK_INT >= 23)
        try {
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
                assertLockShows()
            }
        } finally {
            key(KeyEvent.KEYCODE_WAKEUP)
            dismissKeyguard()
        }
    }

    /** Owner with a PIN (signed in), auto-lock after 1 minute; PIN login off again afterwards. */
    private fun withPinAndAutoLock(test: () -> Unit) {
        val owner = Seed.Ids.STAFF_OWNER
        runBlocking {
            graph.settings.markSetupDone()
            com.lekaspos.app.ErrorReports.setConsent(ctx, false) // no error-reports question on top (D-057)
            graph.staff.load()
            graph.staffAdmin.setPin(owner, "2468") // the store's first PIN signs the owner in
            graph.settings.saveDevice(graph.settings.device.value.copy(autoLockMinutes = 1))
        }
        try {
            assertTrue(graph.staff.state.value.current != null)
            test()
        } finally {
            // The other screen tests share this database.
            runBlocking {
                if (graph.staff.state.value.locked) graph.staff.signIn(owner, "2468")
                graph.staffAdmin.setPin(owner, null)
                graph.settings.saveDevice(graph.settings.device.value.copy(autoLockMinutes = 0))
            }
            instrumentation.runOnMainSync { resumed().filterIsInstance<LockActivity>().forEach { it.finish() } }
            instrumentation.waitForIdleSync()
        }
    }

    private fun assertLockShows() {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var shown = false
        while (!shown && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { shown = resumed().any { it is LockActivity } }
            if (!shown) SystemClock.sleep(50L)
        }
        assertTrue(shown, "the sign-in screen did not show")
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

    /** The emulator's swipe lock screen goes away (Android 6+). */
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT < 23) return
        val out = instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard")
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        SystemClock.sleep(500L)
    }
}
