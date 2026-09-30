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
import com.lekaspos.ui.catalog.CategoriesActivity
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

/** Settings hub. */
class SettingsActivity : ScreenActivity() {

    private class Entry(val title: Int, val subtitle: Int?, val target: Class<*>?, val action: (() -> Unit)? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.settings_title), R.layout.list_plain) ?: return
        val entries = listOf(
            Entry(R.string.settings_language, null, null) { chooseLanguage() },
            Entry(R.string.settings_store, R.string.settings_store_sub, StoreSettingsActivity::class.java),
            Entry(R.string.settings_printer, R.string.settings_printer_sub, PrinterSettingsActivity::class.java),
            Entry(R.string.settings_scanner, R.string.settings_scanner_sub, ScannerSettingsActivity::class.java),
            Entry(R.string.staff_title, R.string.settings_staff_sub, StaffActivity::class.java),
            Entry(R.string.shift_title, R.string.settings_shift_sub, ShiftActivity::class.java),
            Entry(R.string.customers_title, R.string.settings_customers_sub, CustomersActivity::class.java),
            Entry(R.string.menu_tax_rates, null, TaxRatesActivity::class.java),
            Entry(R.string.menu_categories, null, CategoriesActivity::class.java),
            Entry(R.string.settings_audit, R.string.settings_audit_sub, AuditLogActivity::class.java),
            Entry(R.string.sync_title, R.string.settings_sync_sub, SyncActivity::class.java),
            Entry(R.string.backup_title, R.string.settings_backup_sub, BackupActivity::class.java),
            Entry(R.string.menu_diagnostics, null, DiagnosticsActivity::class.java),
            Entry(R.string.settings_about, null, null) { about() },
        )
        val adapter = RowAdapter<Entry>(
            bind = { h, e ->
                val sub = if (e.title == R.string.settings_about) {
                    aboutLine()
                } else if (e.action != null) {
                    languageName(AppLanguage.get(this))
                } else {
                    e.subtitle?.let { getString(it) }
                }
                h.set(getString(e.title), sub)
            },
            onClick = { e ->
                val action = e.action
                if (action != null) action() else e.target?.let { startActivity(Intent(this, it)) }
            },
        )
        adapter.submit(entries)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    /** "LekasPOS 1.0.0 (build 60) · GPL-3.0"; a build not signed with the release key says so. */
    private fun aboutLine(): String {
        val line = getString(R.string.settings_about_sub, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        return if (BuildConfig.SIGNING_KEY == "release") line else "$line · ${BuildConfig.SIGNING_KEY}"
    }

    /**
     * Version, licence, the website (downloads and updates — there is no app store to update from,
     * D-051; the source code is linked there) and the notices of the bundled libraries.
     */
    private fun about() {
        val text = listOf(getString(R.string.about_text, BuildConfig.VERSION_NAME), getString(R.string.about_notices))
            .joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(text)
            .setPositiveButton(R.string.about_website) { _, _ -> open(WEBSITE_URL) }
            .setNeutralButton(R.string.about_privacy) { _, _ -> open(PRIVACY_URL) }
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
                val restart = Intent(this, SellActivity::class.java)
                startActivity(restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    private companion object {
        const val PRIVACY_URL = "https://faizoken.github.io/LekasPOS/privacy.html"
        const val WEBSITE_URL = "https://faizoken.github.io/LekasPOS/"
    }
}
