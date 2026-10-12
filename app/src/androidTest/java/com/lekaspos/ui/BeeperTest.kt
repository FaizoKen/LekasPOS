package com.lekaspos.ui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.app.LekasApp
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.ui.sell.Beeper
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-076: where the scan beeps play is a setting of the till, kept across a restart; the beeps play (or stay
 * silent) with each choice, on API 21 too, where the speaker cannot be chosen. Where the sound comes out is
 * checked on a real till with a customer screen (docs/PHASES.md).
 */
@RunWith(AndroidJUnit4::class)
class BeeperTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = LekasApp.graph(ctx)

    @After
    fun tearDown() = runBlocking {
        graph.settings.saveDevice(graph.settings.device.value.copy(beep = DeviceSettings.BEEP_TILL))
    }

    @Test
    fun theBeepChoiceIsKeptAndEachOnePlays() = runBlocking {
        assertEquals(DeviceSettings.BEEP_TILL, DeviceSettings().beep) // a till that never chose: its own speaker
        val beeper = Beeper.create(ctx, graph.settings.device)
        try {
            for (beep in listOf(DeviceSettings.BEEP_OFF, DeviceSettings.BEEP_SECOND_SCREEN, DeviceSettings.BEEP_TILL)) {
                graph.settings.saveDevice(graph.settings.device.value.copy(beep = beep))
                assertEquals(beep, graph.db().read { DeviceSettings.load(it) }.beep)
                beeper.ok()
                beeper.error()
                beeper.ok() // again while the last one plays: from the start, no crash
                SystemClock.sleep(300)
            }
        } finally {
            beeper.release()
        }
    }
}
