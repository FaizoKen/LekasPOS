package com.lekaspos.ui.products

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
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
            val items = graph.db().read { r -> if (q.isBlank()) ProductDao.managePage(r, null, PAGE) else ProductDao.search(r, q, 100) }
            adapter.submit(items)
            end = q.isNotBlank() || items.size < PAGE
            empty.setText(if (q.isBlank()) R.string.products_empty else R.string.products_none_found)
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

    companion object {
        private const val PAGE = 60
    }
}
