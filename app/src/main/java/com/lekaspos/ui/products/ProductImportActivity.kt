package com.lekaspos.ui.products

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Switch
import com.lekaspos.R
import com.lekaspos.core.csv.ProductCsv.Column
import com.lekaspos.core.csv.ProductCsv.Problem
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.domain.products.ProductCsvService
import com.lekaspos.ui.common.CsvFiles
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Importing products from a CSV file (D-042): a preview first — how many products are new,
 * how many change, which lines have problems and why — then the import, which keeps running
 * if the screen is rotated or left.
 */
class ProductImportActivity : ScreenActivity() {

    private lateinit var uri: Uri
    private var preview: ProductCsvService.Preview? = null
    private var setStock: Switch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: return finish()
        setScreen(getString(R.string.import_title))
        guard(Perm.MANAGE_PRODUCTS)
    }

    override fun onStarted(scope: CoroutineScope) {
        scope.launch {
            graph.productCsv.state.collect { st ->
                // The screen stays on while importing: with it off the phone sleeps and a long import
                // stopped half-way (2026-10 review).
                keepScreenOn(st is ProductCsvService.State.Running)
                // A result left by an earlier import of another file is not this file's result.
                val mine = graph.productCsv.source == uri.toString()
                when (st) {
                    ProductCsvService.State.Idle -> if (preview == null) loadPreview() else showPreview()
                    is ProductCsvService.State.Running -> showMessage(getString(R.string.import_running, st.rows))
                    is ProductCsvService.State.Done -> if (mine) showDone(st.result) else graph.productCsv.acknowledge()
                    is ProductCsvService.State.Failed ->
                        if (mine) showMessage(getString(R.string.error_generic, st.error), done = true) else graph.productCsv.acknowledge()
                }
            }
        }
    }

    private fun loadPreview() {
        showMessage(getString(R.string.import_reading))
        val app = applicationContext
        launchUi {
            preview = graph.productCsv.preview { CsvFiles.reader(app, uri) }
            showPreview()
        }
    }

    private fun showMessage(text: String, done: Boolean = false) {
        val f = Form(this)
        f.info(text)
        if (done) {
            f.button(getString(R.string.ok), primary = true) {
                graph.productCsv.acknowledge()
                finish()
            }
        }
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun showPreview() {
        val p = preview ?: return
        val currency = graph.settings.store.value.currency
        val f = Form(this)
        f.section(getString(R.string.import_file))
        if (p.malformed != null) f.info(getString(R.string.import_malformed, p.malformed))
        if (p.missing.isNotEmpty()) f.info(getString(R.string.import_missing, p.missing.joinToString(", ") { it.header }))
        f.row(getString(R.string.import_rows), p.rows.toString())
        f.row(getString(R.string.import_new), p.newProducts.toString(), bold = true)
        f.row(getString(R.string.import_updates), p.updates.toString(), bold = true)
        if (p.badRows > 0) f.row(getString(R.string.import_bad), p.badRows.toString(), bold = true)
        if (p.unknown.isNotEmpty()) f.info(getString(R.string.import_unknown_columns, p.unknown.joinToString(", ")))
        if (p.newCategories.isNotEmpty()) f.info(getString(R.string.import_new_categories, p.newCategories.joinToString(", ")))
        if (p.issues.isNotEmpty()) {
            f.section(getString(R.string.import_problems))
            for (i in p.issues) f.row(getString(R.string.import_line, i.line), problemText(i.problem, i.column))
            if (p.issues.size >= ProductCsvService.MAX_ISSUES) f.info(getString(R.string.import_more_problems))
        }
        if (p.sample.isNotEmpty()) {
            f.section(getString(R.string.import_sample))
            for ((row, update) in p.sample) {
                val tag = getString(if (update) R.string.import_tag_update else R.string.import_tag_new)
                f.row("${row.name} · $tag", MoneyFormat.format(row.price, currency))
            }
        }
        // Stock changes need "Manage stock": without it the file's stock column is ignored.
        val mayStock = graph.productCsv.mayImportStock()
        setStock = if (p.hasStock && p.updates > 0 && mayStock) f.switch(getString(R.string.import_set_stock), false) else null
        if (p.hasStock) f.info(getString(if (mayStock) R.string.import_stock_help else R.string.import_stock_no_permission))
        if (p.importable) {
            f.button(getString(R.string.import_go, p.newProducts + p.updates), primary = true) { start() }
        }
        f.button(getString(R.string.cancel)) { finish() }
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun start() {
        val app = applicationContext
        val stock = setStock?.isChecked == true
        val source = uri.toString()
        launchUi { graph.productCsv.startImport({ CsvFiles.reader(app, uri) }, stock, source) }
    }

    private fun showDone(r: ProductCsvService.Result) {
        val f = Form(this)
        f.section(getString(R.string.import_done))
        f.row(getString(R.string.import_created), r.created.toString(), bold = true)
        f.row(getString(R.string.import_updated), r.updated.toString(), bold = true)
        if (r.skipped > 0) f.row(getString(R.string.import_skipped), r.skipped.toString())
        if (r.categoriesCreated > 0) f.row(getString(R.string.import_categories_created), r.categoriesCreated.toString())
        if (r.stockSet > 0) f.row(getString(R.string.import_stock_set), r.stockSet.toString())
        f.button(getString(R.string.ok), primary = true) {
            graph.productCsv.acknowledge()
            finish()
        }
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun problemText(p: Problem, c: Column?): String {
        val what = getString(
            when (p) {
                Problem.NAME_MISSING -> R.string.problem_name_missing
                Problem.NAME_TOO_LONG -> R.string.problem_name_long
                Problem.PRICE_MISSING -> R.string.problem_price_missing
                Problem.PRICE_BAD, Problem.COST_BAD -> R.string.problem_amount
                Problem.STOCK_BAD, Problem.LOW_STOCK_BAD -> R.string.problem_quantity
                Problem.SOLD_BY_BAD -> R.string.problem_sold_by
                Problem.YES_NO_BAD -> R.string.problem_yes_no
                Problem.BARCODE_BAD -> R.string.problem_barcode
                Problem.TAX_BAD, Problem.TAX_UNKNOWN -> R.string.problem_tax
                Problem.BARCODE_TWICE -> R.string.problem_barcode_twice
                Problem.BARCODE_TAKEN -> R.string.problem_barcode_taken
                Problem.BARCODES_SPLIT -> R.string.problem_barcodes_split
                Problem.SKU_TWICE -> R.string.problem_sku_twice
            },
        )
        return if (c == null) what else "${c.header}: $what"
    }

    companion object {
        fun intent(ctx: Context, uri: Uri): Intent =
            Intent(ctx, ProductImportActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
