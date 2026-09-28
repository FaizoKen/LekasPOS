package com.lekaspos.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.BuildConfig
import com.lekaspos.R
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.diag.DiagnosticsActivity

/** Settings hub. */
class SettingsActivity : ScreenActivity() {

    private class Entry(val title: Int, val subtitle: Int?, val target: Class<*>?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.settings_title), R.layout.list_plain) ?: return
        val entries = listOf(
            Entry(R.string.settings_store, R.string.settings_store_sub, StoreSettingsActivity::class.java),
            Entry(R.string.settings_printer, R.string.settings_printer_sub, PrinterSettingsActivity::class.java),
            Entry(R.string.settings_scanner, R.string.settings_scanner_sub, ScannerSettingsActivity::class.java),
            Entry(R.string.menu_tax_rates, null, TaxRatesActivity::class.java),
            Entry(R.string.menu_categories, null, CategoriesActivity::class.java),
            Entry(R.string.settings_audit, R.string.settings_audit_sub, AuditLogActivity::class.java),
            Entry(R.string.menu_diagnostics, null, DiagnosticsActivity::class.java),
            Entry(R.string.settings_about, null, null),
        )
        val adapter = RowAdapter<Entry>(
            bind = { h, e ->
                val sub = if (e.target == null) {
                    getString(R.string.settings_about_sub, BuildConfig.VERSION_NAME, BuildConfig.SIGNING_KEY)
                } else {
                    e.subtitle?.let { getString(it) }
                }
                h.set(getString(e.title), sub)
            },
            onClick = { e -> e.target?.let { startActivity(Intent(this, it)) } },
        )
        adapter.submit(entries)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }
}
