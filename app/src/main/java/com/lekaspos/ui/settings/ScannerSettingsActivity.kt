package com.lekaspos.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.hw.bt.Bluetooth
import com.lekaspos.hw.scanner.SppScanner
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Barcode input on this till: keyboard-mode (HID) scanners need no setup; a serial (SPP)
 * scanner is chosen from paired devices; camera scanning can be switched off. A test field
 * shows exactly what the scanner sends.
 */
class ScannerSettingsActivity : ScreenActivity() {

    private var address: String? = null
    private var scannerName: String? = null
    private lateinit var sppLabel: TextView
    private lateinit var sppState: TextView
    private lateinit var camera: Switch
    private lateinit var test: EditText
    private lateinit var testResult: TextView
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen(getString(R.string.settings_scanner))
        launchUi {
            graph.settings.load()
            build()
        }
    }

    override fun onStarted(scope: CoroutineScope) {
        graph.sppScanner.start()
        scope.launch {
            graph.sppScanner.status.collect {
                if (built) sppState.text = getString(R.string.scanner_state, stateText(it))
            }
        }
        scope.launch {
            graph.sppScanner.codes.collect { if (built) testResult.text = getString(R.string.scanner_test_result, it, it.length) }
        }
    }

    override fun onStop() {
        graph.sppScanner.stop()
        super.onStop()
    }

    private fun stateText(s: SppScanner.Status) = getString(
        when (s) {
            SppScanner.Status.OFF -> R.string.scanner_status_off
            SppScanner.Status.CONNECTING -> R.string.status_connecting
            SppScanner.Status.CONNECTED -> R.string.status_ready
            SppScanner.Status.ERROR -> R.string.scanner_status_error
        },
    )

    private fun build() {
        val d = graph.settings.device.value
        address = d.scannerAddress
        scannerName = d.scannerName
        val f = Form(this)
        f.info(getString(R.string.scanner_hid_info))
        f.section(getString(R.string.scanner_test))
        test = f.text(getString(R.string.scanner_test_hint), "", InputType.TYPE_CLASS_TEXT)
        testResult = f.info("")
        test.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) {
                val code = test.text.toString()
                testResult.text = getString(R.string.scanner_test_result, code, code.length)
                test.setText("")
            }
            true
        }
        f.section(getString(R.string.scanner_spp))
        sppLabel = f.info("")
        sppState = f.info("")
        renderSpp()
        f.button(getString(R.string.scanner_spp_choose)) { chooseScanner() }
        f.button(getString(R.string.scanner_spp_forget)) {
            address = null
            scannerName = null
            renderSpp()
        }
        f.section(getString(R.string.scanner_camera_section))
        camera = f.switch(getString(R.string.scanner_camera), d.cameraScan)
        f.button(getString(R.string.save), primary = true) { save() }
        content.removeAllViews()
        content.addView(f.view)
        built = true
        sppState.text = getString(R.string.scanner_state, stateText(graph.sppScanner.status.value))
    }

    private fun renderSpp() {
        sppLabel.text = if (address == null) getString(R.string.scanner_spp_none) else getString(R.string.printer_selected, scannerName ?: address, address)
    }

    private fun chooseScanner() {
        if (!Bluetooth.hasPermission(this)) {
            val perms = Bluetooth.runtimePermissions()
            if (perms.isNotEmpty()) requestPermissions(perms, REQ_BT) // only reached on API 31+
            return
        }
        launchUi {
            val paired = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Bluetooth.paired(this@ScannerSettingsActivity) }
            if (paired.isEmpty()) {
                Dialogs.message(this@ScannerSettingsActivity, getString(R.string.scanner_spp_choose), getString(R.string.printer_no_paired))
                return@launchUi
            }
            Dialogs.choose(this@ScannerSettingsActivity, getString(R.string.scanner_spp_choose), paired.map { "${it.name}\n${it.address}" }) { i ->
                address = paired[i].address
                scannerName = paired[i].name
                renderSpp()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        // Refused: it did nothing at all, and after two refusals Android no longer asks (2026-10 review).
        if (Bluetooth.hasPermission(this)) chooseScanner() else bluetoothRefused(this, getString(R.string.scanner_permission_needed))
    }

    private fun save() {
        if (!graph.permissions.allowed(Perm.SETTINGS)) {
            requireAccess(Perm.SETTINGS) { save() }
            return
        }
        val next = graph.settings.device.value.copy(scannerAddress = address, scannerName = scannerName, cameraScan = camera.isChecked)
        launchUi {
            graph.settings.saveDevice(next)
            graph.sppScanner.restart()
            toast(R.string.saved)
            finish()
        }
    }

    companion object {
        private const val REQ_BT = 22
    }
}
