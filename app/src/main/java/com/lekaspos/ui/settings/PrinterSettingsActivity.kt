package com.lekaspos.ui.settings

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.hw.bt.Bluetooth
import com.lekaspos.hw.printer.PrinterService
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This till's receipt printer and cash drawer (device-local settings): pick a paired Bluetooth
 * printer, paper width, text/image printing, cutter, drawer pin; test page, drawer test and the
 * print queue.
 */
class PrinterSettingsActivity : ScreenActivity() {

    private var address: String? = null
    private var printerName: String? = null
    private lateinit var status: TextView
    private lateinit var printerLabel: TextView
    private lateinit var retry: Button
    private lateinit var clearQueue: Button
    private lateinit var paper: Spinner
    private lateinit var mode: Spinner
    private lateinit var chinese: Switch
    private lateinit var codePage: EditText
    private lateinit var cut: Switch
    private lateinit var feed: Spinner
    private lateinit var autoPrint: Switch
    private lateinit var nativeQr: Switch
    private lateinit var drawer: Switch
    private lateinit var drawerPin: Spinner
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen(getString(R.string.settings_printer))
        launchUi {
            graph.settings.load()
            build(graph.settings.device.value)
        }
    }

    override fun onStarted(scope: CoroutineScope) {
        scope.launch {
            graph.settings.load()
            graph.printer.start()
            combine(graph.printer.status, graph.printer.pending) { s, n -> s to n }.collect { (s, n) -> renderStatus(s, n) }
        }
    }

    private fun statusText(s: PrinterService.Status): String = when (s) {
        PrinterService.Status.NotConfigured -> getString(R.string.status_not_configured)
        PrinterService.Status.NoBluetooth -> getString(R.string.status_no_bt)
        PrinterService.Status.NoPermission -> getString(R.string.status_no_permission)
        PrinterService.Status.BluetoothOff -> getString(R.string.status_bt_off)
        PrinterService.Status.Idle -> getString(R.string.status_idle)
        PrinterService.Status.Connecting -> getString(R.string.status_connecting)
        PrinterService.Status.Printing -> getString(R.string.status_printing)
        PrinterService.Status.Ready -> getString(R.string.status_ready)
        is PrinterService.Status.Offline -> if (s.gaveUp) getString(R.string.status_offline_gave_up) else getString(R.string.status_offline, s.error)
    }

    private fun build(d: DeviceSettings) {
        address = d.printerAddress
        printerName = d.printerName
        val f = Form(this)
        status = f.info("")
        retry = f.button(getString(R.string.printer_retry)) {
            if (!Bluetooth.hasPermission(this)) requestBluetooth() else graph.printer.reconnect()
        }
        f.section(getString(R.string.printer_section_device))
        printerLabel = f.info("")
        renderPrinter()
        f.button(getString(R.string.printer_choose)) { choosePrinter() }
        f.button(getString(R.string.printer_bt_settings)) {
            try {
                startActivity(Bluetooth.settingsIntent())
            } catch (e: android.content.ActivityNotFoundException) {
                toast(R.string.status_no_bt)
            }
        }

        f.section(getString(R.string.printer_section_paper))
        paper = f.choice(
            getString(R.string.printer_paper),
            listOf(getString(R.string.paper_58), getString(R.string.paper_80), getString(R.string.paper_80_42)),
            when (d.paper) { 80 -> 1; 81 -> 2; else -> 0 },
        )
        mode = f.choice(
            getString(R.string.printer_mode),
            listOf(getString(R.string.mode_auto), getString(R.string.mode_text), getString(R.string.mode_image)),
            d.printMode,
        )
        chinese = f.switch(getString(R.string.printer_chinese), d.chinesePrinter)
        codePage = f.text(getString(R.string.printer_codepage), d.codePage.toString(), InputType.TYPE_CLASS_NUMBER)
        cut = f.switch(getString(R.string.printer_cut), d.cut)
        feed = f.choice(getString(R.string.printer_feed), (0..8).map { it.toString() }, d.feedLines.coerceIn(0, 8))
        autoPrint = f.switch(getString(R.string.printer_auto), d.autoPrint)
        nativeQr = f.switch(getString(R.string.printer_native_qr), d.nativeQr)

        f.section(getString(R.string.printer_section_drawer))
        drawer = f.switch(getString(R.string.drawer_enabled), d.drawerEnabled)
        drawerPin = f.choice(getString(R.string.drawer_pin), listOf(getString(R.string.drawer_pin2), getString(R.string.drawer_pin5)), d.drawerPin)

        f.button(getString(R.string.save), primary = true) { save(finishAfter = true) }
        f.button(getString(R.string.printer_test)) { testPrint() }
        f.button(getString(R.string.printer_open_drawer)) { openDrawer() }
        clearQueue = f.button(getString(R.string.printer_clear_queue, 0)) { clearQueue() }
        clearQueue.visible(false)
        content.removeAllViews()
        content.addView(f.view)
        built = true
        renderStatus(graph.printer.status.value, graph.printer.pending.value)
    }

    private fun renderStatus(s: PrinterService.Status, n: Int) {
        if (!built) return
        status.text = getString(R.string.printer_status, statusText(s))
        retry.visible(s is PrinterService.Status.Offline || s == PrinterService.Status.BluetoothOff || s == PrinterService.Status.NoPermission)
        clearQueue.text = getString(R.string.printer_clear_queue, n)
        clearQueue.visible(n > 0)
    }

    private fun renderPrinter() {
        printerLabel.text = if (address == null) getString(R.string.printer_none) else getString(R.string.printer_selected, printerName ?: address, address)
    }

    private fun current(): DeviceSettings = graph.settings.device.value.copy(
        printerAddress = address,
        printerName = printerName,
        paper = when (paper.selectedItemPosition) { 1 -> 80; 2 -> 81; else -> 58 },
        printMode = mode.selectedItemPosition,
        chinesePrinter = chinese.isChecked,
        codePage = codePage.text.toString().toIntOrNull()?.coerceIn(0, 255) ?: 0,
        cut = cut.isChecked,
        feedLines = feed.selectedItemPosition,
        autoPrint = autoPrint.isChecked,
        nativeQr = nativeQr.isChecked,
        drawerEnabled = drawer.isChecked,
        drawerPin = drawerPin.selectedItemPosition,
    )

    private fun save(finishAfter: Boolean, then: (() -> Unit)? = null) {
        if (!graph.permissions.allowed(Perm.SETTINGS)) {
            requireAccess(Perm.SETTINGS) { save(finishAfter, then) }
            return
        }
        val next = current()
        launchUi {
            graph.settings.saveDevice(next) { graph.printer.reconnect() }
            if (finishAfter) {
                toast(R.string.saved)
                finish()
            }
            then?.invoke()
        }
    }

    private fun choosePrinter() {
        if (!Bluetooth.hasPermission(this)) {
            requestBluetooth()
            return
        }
        launchUi {
            // Each device's name is a call to the Bluetooth service: off the main thread (2026-10 review).
            val paired = withContext(Dispatchers.IO) { Bluetooth.paired(this@PrinterSettingsActivity) }
            if (paired.isEmpty()) {
                Dialogs.message(this@PrinterSettingsActivity, getString(R.string.printer_choose), getString(R.string.printer_no_paired))
                return@launchUi
            }
            Dialogs.choose(this@PrinterSettingsActivity, getString(R.string.printer_choose), paired.map { "${it.name}\n${it.address}" }) { i ->
                address = paired[i].address
                printerName = paired[i].name
                renderPrinter()
                // Kept at once: Save sits below ten settings, and after Back the first sale said
                // "No printer set up" (2026-10 review).
                save(finishAfter = false)
            }
        }
    }

    private fun requestBluetooth() {
        val perms = Bluetooth.runtimePermissions()
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(perms, REQ_BT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        if (Bluetooth.hasPermission(this)) {
            graph.printer.reconnect()
            choosePrinter()
        } else {
            bluetoothRefused(this, getString(R.string.printer_permission_needed))
        }
    }

    private fun testPrint() {
        if (address == null) {
            Dialogs.message(this, null, getString(R.string.printer_none))
            return
        }
        save(finishAfter = false) {
            launchUi {
                graph.db().write(reserveIds = 0L) { tx -> PrintJobDao.enqueue(tx, PrintJobKind.TEST, null, 1, System.currentTimeMillis()) }
                graph.printer.wake()
                toast(R.string.printer_test_queued)
            }
        }
    }

    private fun openDrawer() {
        save(finishAfter = false) {
            withApproval(Perm.OPEN_DRAWER) { approval ->
                launchUi {
                    graph.sales.openDrawer(approval)
                    toast(R.string.drawer_opened)
                }
            }
        }
    }

    private fun clearQueue() {
        Dialogs.confirm(this, getString(R.string.printer_clear_title), getString(R.string.printer_clear_message), getString(R.string.printer_clear_yes)) {
            launchUi {
                graph.db().write(reserveIds = 0L) { tx -> PrintJobDao.cancelPending(tx, System.currentTimeMillis()) }
                graph.printer.reconnect()
                toast(R.string.printer_queue_cleared)
            }
        }
    }

    companion object {
        private const val REQ_BT = 21
    }
}

/**
 * The Bluetooth ("Nearby devices") permission was refused. After two refusals Android no longer
 * asks, and a printer or scanner could never be chosen: the way to App info is offered (2026-10 review).
 */
internal fun bluetoothRefused(a: android.app.Activity, message: String) {
    android.app.AlertDialog.Builder(a)
        .setMessage(message)
        .setPositiveButton(R.string.open_app_settings) { _, _ ->
            try {
                a.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", a.packageName, null)),
                )
            } catch (e: android.content.ActivityNotFoundException) {
                com.lekaspos.util.Log.w("No app settings screen", e)
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
        .trackedBy(a)
}
