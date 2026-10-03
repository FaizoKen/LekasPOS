package com.lekaspos.ui.products

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.ui.common.CsvFiles
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** All products by name (keyset-paged), with search; tap to edit, + to add. */
class ProductListActivity : ScreenActivity() {

    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false

    private val adapter = RowAdapter<ProductListItem>(
        bind = { h, p ->
            val currency = graph.settings.store.value.currency
            val price = MoneyFormat.format(p.price, currency)
            h.set(
                title = p.name,
                subtitle = p.stockQty?.let { getString(R.string.product_row_stock, MoneyFormat.formatQty(it)) },
                value = if (p.sellMode == SellMode.WEIGHT) "$price/${p.unit}" else price,
                tag = if (p.active) null else getString(R.string.product_hidden),
            )
        },
        onClick = { startActivity(ProductEditActivity.newIntent(this, productId = it.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.products_title), R.layout.list_with_search) ?: return
        addAction(R.drawable.ic_add, R.string.products_add) { startActivity(ProductEditActivity.newIntent(this)) }
        lateinit var more: ImageButton
        more = addAction(R.drawable.ic_more, R.string.sell_menu) { csvMenu(more) }
        search = v.findViewById(R.id.list_search)
        search.setHint(R.string.products_search_hint)
        empty = v.findViewById(R.id.list_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = reload(debounce = true)
        })
    }

    override fun onStarted(scope: CoroutineScope) = reload(debounce = false) // refresh after edits

    private fun reload(debounce: Boolean) {
        job?.cancel()
        val q = search.text.toString()
        job = scope.launch {
            if (debounce) delay(200L)
            val items = graph.db().read { r -> if (q.isBlank()) ProductDao.managePage(r, null, PAGE) else ProductDao.search(r, q, 100, includeInactive = true) }
            adapter.submit(items)
            end = q.isNotBlank() || items.size < PAGE
            // A barcode scanned or typed that no product has: one tap adds it (it was "No product
            // matches." only; scanning to add went through a bill that had to be cancelled — 2026-10 review).
            val code = q.trim().takeIf { items.isEmpty() && it.length >= 6 && it.all(Char::isDigit) }
            when {
                q.isBlank() -> empty.setText(R.string.products_empty)
                code != null -> empty.text = getString(R.string.products_add_barcode, code)
                else -> empty.setText(R.string.products_none_found)
            }
            empty.setOnClickListener(if (code == null) null else View.OnClickListener {
                requireAccess(Perm.MANAGE_PRODUCTS) { startActivity(ProductEditActivity.newIntent(this@ProductListActivity, barcode = code)) }
            })
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { ProductDao.managePage(it, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    /** Products to and from CSV (D-042): import (with preview), export, an example file. */
    private fun csvMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = listOf(R.string.csv_import, R.string.csv_export, R.string.csv_template)
        for ((i, res) in items.withIndex()) m.menu.add(0, res, i, res)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.csv_import -> requireAccess(Perm.MANAGE_PRODUCTS) {
                    startPicker(CsvFiles.openDocumentIntent(), REQ_IMPORT)
                }
                R.string.csv_export -> requireAccess(Perm.MANAGE_PRODUCTS) {
                    exportCsv("lekaspos-products.csv") { out -> graph.productCsv.export(out).toLong() }
                }
                R.string.csv_template -> exportCsv("lekaspos-products-template.csv") { out ->
                    graph.productCsv.template(out)
                    2L
                }
            }
            true
        }
        m.show()
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && uri != null) startActivity(ProductImportActivity.intent(this, uri))
    }

    companion object {
        private const val REQ_IMPORT = 7
        private const val PAGE = 60
    }
}
