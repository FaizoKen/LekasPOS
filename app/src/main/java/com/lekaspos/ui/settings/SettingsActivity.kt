package com.lekaspos.ui.settings

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.BuildConfig
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.ErrorReports
import com.lekaspos.core.model.Perm
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.PaymentMethodsActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.customers.CustomersActivity
import com.lekaspos.ui.diag.DiagnosticsActivity
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.shift.ShiftActivity
import com.lekaspos.ui.staff.StaffActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings hub. */
class SettingsActivity : ScreenActivity() {

    private class Entry(
        val title: Int,
        val subtitle: Int?,
        val target: Class<*>?,
        val sub: (() -> String?)? = null,
        val action: (() -> Unit)? = null,
    )

    private var adapter: RowAdapter<Entry>? = null

    /** This phone's answer about error reports (D-057), once read. */
    private var reports: Int? = null
    private var reportsRow = 0
    private var updatesRow = 0
    private var driveReportRow = 0
    private var tilesRow = 0
    private var customerRow = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.settings_title), R.layout.list_plain) ?: return
        val entries = listOf(
            Entry(R.string.settings_language, null, null, sub = { languageName(AppLanguage.get(this)) }) { chooseLanguage() },
            Entry(R.string.settings_tiles, null, null, sub = { tileName(graph.settings.device.value.tileSize) }) { chooseTiles() },
            Entry(R.string.settings_customer_screen, null, null, sub = { customerScreenLine() }) { customerScreen() },
            Entry(R.string.settings_store, R.string.settings_store_sub, StoreSettingsActivity::class.java),
            Entry(R.string.settings_printer, R.string.settings_printer_sub, PrinterSettingsActivity::class.java),
            Entry(R.string.settings_scanner, R.string.settings_scanner_sub, ScannerSettingsActivity::class.java),
            Entry(R.string.staff_title, R.string.settings_staff_sub, StaffActivity::class.java),
            Entry(R.string.shift_title, R.string.settings_shift_sub, ShiftActivity::class.java),
            Entry(R.string.customers_title, R.string.settings_customers_sub, CustomersActivity::class.java),
            Entry(R.string.menu_tax_rates, null, TaxRatesActivity::class.java),
            Entry(R.string.pm_title, R.string.settings_pm_sub, PaymentMethodsActivity::class.java),
            Entry(R.string.menu_categories, null, CategoriesActivity::class.java),
            Entry(R.string.settings_audit, R.string.settings_audit_sub, AuditLogActivity::class.java),
            Entry(R.string.sync_title, R.string.settings_sync_sub, SyncActivity::class.java),
            Entry(R.string.backup_title, R.string.settings_backup_sub, BackupActivity::class.java),
            Entry(R.string.drive_report_title, null, null, sub = { DriveReportUi.subtitle(this, graph.dailyReport.status.value) }) {
                DriveReportUi.open(this)
            },
            Entry(R.string.error_reports_title, null, null, sub = { reportsLine() }) { chooseReports() },
            // Both are the shop's choices like the other settings: a cashier turned the daily update
            // check or the error reports off (2026-10 review).
            Entry(R.string.update_settings_title, null, null, sub = { UpdateUi.subtitle(this, graph.updates.status.value) }) {
                // For this one change: a manager's PIN here no longer opened the rest of Settings too.
                withApproval(Perm.SETTINGS) { UpdateUi.settings(this, scope) }
            },
            Entry(R.string.menu_diagnostics, null, DiagnosticsActivity::class.java),
            Entry(R.string.settings_about, null, null, sub = { aboutLine() }) { about() },
        )
        val adapter = RowAdapter<Entry>(
            bind = { h, e -> h.set(getString(e.title), e.sub?.invoke() ?: e.subtitle?.let { getString(it) }) },
            onClick = { e ->
                val action = e.action
                if (action != null) action() else e.target?.let { startActivity(Intent(this, it)) }
            },
        )
        adapter.submit(entries)
        this.adapter = adapter
        reportsRow = entries.indexOfFirst { it.title == R.string.error_reports_title }
        updatesRow = entries.indexOfFirst { it.title == R.string.update_settings_title }
        driveReportRow = entries.indexOfFirst { it.title == R.string.drive_report_title }
        tilesRow = entries.indexOfFirst { it.title == R.string.settings_tiles }
        customerRow = entries.indexOfFirst { it.title == R.string.settings_customer_screen }
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val app = applicationContext
        launchUi {
            reports = withContext(Dispatchers.IO) { ErrorReports.consent(app) }
            adapter.notifyItemChanged(reportsRow)
        }
    }

    /** App updates (D-059): the row follows checks and downloads while the screen is open. */
    override fun onStarted(scope: CoroutineScope) {
        adapter?.notifyItemChanged(customerRow) // a screen connected meanwhile (the cast settings)
        val updates = graph.updates
        scope.launch {
            updates.load()
            updates.status.collect { adapter?.notifyItemChanged(updatesRow) }
        }
        // The daily report to Google Drive (D-065): on, off, its last upload.
        val report = graph.dailyReport
        scope.launch {
            report.load()
            report.status.collect { adapter?.notifyItemChanged(driveReportRow) }
        }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (DriveReportUi.onResult(this, requestCode, resultCode, data)) return // Google's consent (D-065)
        UpdateUi.onResult(this, scope, requestCode) // allowed to install the update (or not)
    }

    private fun reportsLine(): String? = when (reports) {
        null -> null
        ErrorReports.ON -> getString(R.string.error_reports_on)
        ErrorReports.OFF -> getString(R.string.error_reports_off)
        else -> getString(R.string.error_reports_unasked)
    }

    /** Error reports to the developer, on or off for this phone (D-057). */
    private fun chooseReports() = withApproval(Perm.SETTINGS) {
        ReportsChoice.ask(this) { on ->
            reports = if (on) ErrorReports.ON else ErrorReports.OFF
            adapter?.notifyItemChanged(reportsRow)
        }
    }

    /** "LekasPOS 1.0.0 (build 60) · GPL-3.0"; a build not signed with the release key says so. */
    private fun aboutLine(): String {
        val line = getString(R.string.settings_about_sub, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        return if (BuildConfig.SIGNING_KEY == "release") line else "$line · ${BuildConfig.SIGNING_KEY}"
    }

    /**
     * Version, licence, the website (downloads — the app also updates itself, D-059; the source code
     * is linked there) and the notices of the bundled libraries.
     */
    private fun about() {
        val text = listOf(getString(R.string.about_text, BuildConfig.VERSION_NAME), getString(R.string.about_notices))
            .joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(text)
            .setPositiveButton(R.string.about_website) { _, _ -> open(WEBSITE_URL) }
            .setNeutralButton(R.string.about_privacy) { _, _ -> open(getString(R.string.privacy_url)) } // privasi.html in Malay
            .setNegativeButton(R.string.ok, null)
            .show()
            .trackedBy(this)
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(url)
        }
    }

    /** "On · showing on <display>", "On · no second screen connected" or "Off" (D-069). */
    private fun customerScreenLine(): String {
        if (!graph.settings.device.value.customerScreen) return getString(R.string.cs_off)
        val d = graph.customerDisplay.display()
        return if (d != null) getString(R.string.cs_on_connected, d.name) else getString(R.string.cs_on_none)
    }

    /**
     * The customer screen on a second display (D-069), per till: what it needs (a cable, or Miracast — not Google
     * Cast's "Cast screen"), on or off, and a way to the phone's cast settings. Only how the till shows: no permission.
     */
    private fun customerScreen() {
        val on = graph.settings.device.value.customerScreen
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_customer_screen)
            .setMessage(getString(R.string.cs_help) + "\n\n" + customerScreenLine())
            .setPositiveButton(if (on) R.string.cs_turn_off else R.string.cs_turn_on) { _, _ ->
                launchUi {
                    graph.settings.saveDevice(graph.settings.device.value.copy(customerScreen = !on))
                    graph.customerDisplay.refresh()
                    adapter?.notifyItemChanged(customerRow)
                }
            }
            .setNeutralButton(R.string.cs_connect) { _, _ -> openCastSettings() }
            .setNegativeButton(R.string.close, null)
            .show()
            .trackedBy(this)
    }

    /** Android's cast (screen mirroring) settings, where this phone has them; else its display settings. */
    private fun openCastSettings() {
        for (action in listOf(android.provider.Settings.ACTION_CAST_SETTINGS, WIFI_DISPLAY_SETTINGS, android.provider.Settings.ACTION_DISPLAY_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: ActivityNotFoundException) {
                continue // not on this phone: the next
            } catch (e: SecurityException) {
                continue
            }
        }
        toast(R.string.cs_no_cast)
    }

    private fun tileName(size: Int): String = getString(
        when (size) {
            DeviceSettings.TILES_LARGE -> R.string.tiles_large
            DeviceSettings.TILES_SMALL -> R.string.tiles_small
            else -> R.string.tiles_medium
        },
    )

    /**
     * How big the selling screen's tiles are on this till (D-066): smaller ones show more products at a
     * time. Only how the screen looks: no permission.
     */
    private fun chooseTiles() {
        val sizes = listOf(DeviceSettings.TILES_LARGE, DeviceSettings.TILES_MEDIUM, DeviceSettings.TILES_SMALL)
        val current = graph.settings.device.value
        Dialogs.choose(this, getString(R.string.settings_tiles), sizes.map { tileName(it) }, sizes.indexOf(current.tileSize)) { i ->
            launchUi {
                graph.settings.saveDevice(graph.settings.device.value.copy(tileSize = sizes[i]))
                adapter?.notifyItemChanged(tilesRow)
            }
        }
    }

    private fun languageName(code: String): String = when (code) {
        AppLanguage.ENGLISH -> getString(R.string.lang_en)
        AppLanguage.MALAY -> getString(R.string.lang_ms)
        else -> getString(R.string.language_phone)
    }

    /** Screens restart in the new language; the open bill and everything else stay (app-scoped state). */
    private fun chooseLanguage() {
        val codes = AppLanguage.ALL
        val current = codes.indexOf(AppLanguage.get(this))
        Dialogs.choose(this, getString(R.string.settings_language), codes.map { languageName(it) }, current) { i ->
            if (i != current) {
                AppLanguage.set(this, codes[i])
                graph.settings.languageChanged() // receipts in the new language too, unless the store chose one
                val restart = Intent(this, SellActivity::class.java)
                startActivity(restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    private companion object {
        const val WEBSITE_URL = "https://faizoken.github.io/LekasPOS/"

        /** Older Android and some makers: "Wireless display" (Miracast) settings. */
        const val WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"
    }
}
