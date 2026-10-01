package com.lekaspos.ui

import android.app.Activity
import android.os.SystemClock
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
                shell("input keyevent KEYCODE_SLEEP")
                SystemClock.sleep(2_000L) // the phone's screen is off
                graph.staff.idleFor(2 * 60_000L) // ... for longer than the idle time
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard") // Android 6+; below, the menu key dismisses a swipe lock
                shell("input keyevent KEYCODE_MENU")
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
            shell("input keyevent KEYCODE_WAKEUP")
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

    /** Runs [cmd] in the device shell and waits for it to finish. */
    private fun shell(cmd: String) {
        val out = instrumentation.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }
}
