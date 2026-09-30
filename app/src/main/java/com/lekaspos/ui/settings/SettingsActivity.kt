package com.lekaspos.ui.settings

import android.content.Intent
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
            Entry(R.string.settings_about, null, null),
        )
        val adapter = RowAdapter<Entry>(
            bind = { h, e ->
                val sub = if (e.action != null) {
                    languageName(AppLanguage.get(this))
                } else if (e.target == null) {
                    getString(R.string.settings_about_sub, BuildConfig.VERSION_NAME, BuildConfig.SIGNING_KEY)
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
}
