package com.lekaspos.ui.inventory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.scan.CameraScanActivity
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Picks one product for stock work: search, scan with a keyboard-wedge scanner (no tap needed),
 * or the camera. An unknown barcode can be added as a new product on the spot.
 * Result: [EXTRA_PRODUCT_ID] and [EXTRA_SCANNED_QTY] (pack size of a scanned carton barcode).
 */
class ProductPickActivity : ScreenActivity() {

    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val scanInput = ScanInput(onScan = { onCode(it) }, onTyped = { text, submit -> typed(text, submit) })

    private val adapter = RowAdapter<ProductListItem>(
        bind = { h, p -> h.set(p.name, p.stockQty?.let { getString(R.string.inv_stock_now, InventoryUi.qty(it, p.unit)) }) },
        onClick = { finishWith(it.id, 0L) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.inv_pick_title), R.layout.list_with_search) ?: return
        addAction(R.drawable.ic_camera, R.string.sell_camera) {
            @Suppress("DEPRECATION")
            startActivityForResult(CameraScanActivity.pickIntent(this), REQ_CAMERA)
        }
        search = v.findViewById(R.id.list_search)
        search.setHint(R.string.inv_pick_hint)
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
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                val text = search.text.toString().trim()
                if (text.isNotEmpty()) onCode(text, fromSearch = true)
                true
            } else {
                false
            }
        }
    }

    override fun onStarted(scope: CoroutineScope) = reload(debounce = false)

    override fun onStop() {
        scanInput.clear()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (!search.hasFocus() && scanInput.onKey(event)) || super.dispatchKeyEvent(event)

    private fun typed(text: String, submit: Boolean) {
        search.setText(text)
        search.setSelection(text.length)
        search.requestFocus()
        if (submit) onCode(text, fromSearch = true)
    }

    private fun reload(debounce: Boolean) {
        job?.cancel()
        val q = search.text.toString()
        job = scope.launch {
            if (debounce) delay(200L)
            val items = graph.db().read { r -> if (q.isBlank()) ProductDao.managePage(r, null, PAGE) else ProductDao.search(r, q, 100, includeInactive = true) }
            adapter.submit(items)
            end = q.isNotBlank() || items.size < PAGE
            empty.setText(R.string.products_none_found)
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

    /** A scanned (or typed) code: pick its product, or offer to add a new one. */
    private fun onCode(code: String, fromSearch: Boolean = false) {
        launchUi {
            val p = InventoryUi.resolve(graph, code)
            when {
                p != null -> finishWith(p.id, p.scannedQty)
                fromSearch && code.any { !it.isDigit() } -> reload(debounce = false) // a name: show the matches
                else -> Dialogs.confirm(
                    this@ProductPickActivity, getString(R.string.sell_not_found_title), getString(R.string.sell_not_found_message, code),
                    getString(R.string.sell_add_product),
                ) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(ProductEditActivity.newIntent(this@ProductPickActivity, barcode = code), REQ_NEW)
                }
            }
        }
    }

    private fun finishWith(productId: Long, scannedQty: Long) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PRODUCT_ID, productId).putExtra(EXTRA_SCANNED_QTY, scannedQty))
        finish()
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_CAMERA -> data.getStringExtra(CameraScanActivity.EXTRA_CODE)?.let { onCode(it) }
            REQ_NEW -> data.getLongExtra(ProductEditActivity.EXTRA_PRODUCT_ID, 0L).takeIf { it != 0L }?.let { finishWith(it, 0L) }
        }
    }

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
        const val EXTRA_SCANNED_QTY = "scanned_qty"
        private const val EXTRA_TITLE = "title"
        private const val REQ_CAMERA = 51
        private const val REQ_NEW = 52
        private const val PAGE = 60

        fun intent(ctx: Context, title: String): Intent = Intent(ctx, ProductPickActivity::class.java).putExtra(EXTRA_TITLE, title)
    }
}
