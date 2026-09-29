package com.lekaspos.ui.inventory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.inventory.StockEvent
import com.lekaspos.core.inventory.StockTimeline
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.stock.HistoryEntry
import com.lekaspos.data.stock.LowStockItem
import com.lekaspos.data.stock.MovementRow
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.stock.StockHistoryDao
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sales.SaleDetailActivity
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One product's stock history: sales, refunds, deliveries, adjustments and counts, newest first,
 * each with the stock level right after it.
 */
class StockHistoryActivity : ScreenActivity() {

    private class Row(val e: HistoryEntry, val after: Long?)

    private var productId = 0L
    private var product: StockProduct? = null
    private lateinit var header: TextView
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private var lastEntry: HistoryEntry? = null
    private var running: Long? = null
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<Row>(
        bind = { h, r ->
            val unit = product?.unit
            val e = r.e
            val title = when (e.type) {
                HistoryEntry.Type.SALE -> getString(R.string.hist_sale, e.text ?: "")
                HistoryEntry.Type.REFUND -> getString(R.string.hist_refund, e.text ?: "")
                HistoryEntry.Type.COUNT -> getString(R.string.hist_count)
                HistoryEntry.Type.MOVEMENT -> InventoryUi.movementText(this, e.kind, e.text)
            }
            val change = when (e.type) {
                HistoryEntry.Type.COUNT -> e.counted?.let { c -> e.expected?.let { InventoryUi.signedQty(c - it, unit) } }
                else -> if (e.delta != 0L) InventoryUi.signedQty(e.delta, unit) else null
            }
            h.set(
                title = title,
                subtitle = listOfNotNull(DateText.dateTime(e.at, tz), r.after?.let { getString(R.string.hist_after, InventoryUi.qty(it, unit)) })
                    .joinToString(" · "),
                value = change,
                tag = if (e.voided) getString(R.string.sale_voided) else null,
            )
        },
        onClick = { r ->
            val saleId = r.e.refId
            if ((r.e.type == HistoryEntry.Type.SALE || r.e.type == HistoryEntry.Type.REFUND) && saleId != null) {
                startActivity(SaleDetailActivity.newIntent(this, saleId))
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getLongExtra(EXTRA_PRODUCT, 0L)
        val v = setScreen(getString(R.string.hist_title), R.layout.list_header) ?: return
        addAction(R.drawable.ic_add, R.string.inv_adjust) { adjustProduct(productId) { reload() } }
        header = v.findViewById(R.id.list_header)
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.hist_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        job?.cancel()
        job = scope.launch {
            val p = InventoryUi.product(graph, productId) ?: return@launch finish()
            product = p
            setScreenTitle(p.name)
            header.text = getString(
                R.string.hist_header, InventoryUi.qty(p.stock, p.unit), MoneyFormat.format(p.cost, graph.settings.store.value.currency),
            )
            val page = graph.db().read { StockHistoryDao.page(it, productId, null, PAGE) }
            running = p.stock
            lastEntry = null
            adapter.submit(rows(page))
            end = page.size < PAGE
            empty.visible(page.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = lastEntry ?: return
        job = scope.launch {
            val page = graph.db().read { StockHistoryDao.page(it, productId, after, PAGE) }
            adapter.append(rows(page))
            end = page.size < PAGE
        }
    }

    /** Pairs a page with running levels, continuing from the previous page (core StockTimeline). */
    private fun rows(page: List<HistoryEntry>): List<Row> {
        val events = page.map {
            if (it.type == HistoryEntry.Type.COUNT) StockEvent.Count(it.hlc, it.id, it.counted ?: 0L, it.expected) else StockEvent.Change(it.hlc, it.id, it.delta)
        }
        val b = StockTimeline.balances(events, running)
        running = b.before
        lastEntry = page.lastOrNull() ?: lastEntry
        return page.mapIndexed { i, e -> Row(e, b.after[i]) }
    }

    companion object {
        private const val EXTRA_PRODUCT = "product"
        private const val PAGE = 50

        fun intent(ctx: Context, productId: Long): Intent = Intent(ctx, StockHistoryActivity::class.java).putExtra(EXTRA_PRODUCT, productId)
    }
}

/** Products at or below their low-stock alert level. */
class LowStockActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false

    private val adapter = RowAdapter<LowStockItem>(
        bind = { h, it -> h.set(it.name, getString(R.string.inv_low_alert_at, InventoryUi.qty(it.lowStock, it.unit)), InventoryUi.qty(it.qty, it.unit)) },
        onClick = { startActivity(StockHistoryActivity.intent(this, it.productId)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.inv_low_stock), R.layout.list_plain) ?: return
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.inv_low_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
    }

    override fun onStarted(scope: CoroutineScope) {
        job?.cancel()
        job = this.scope.launch {
            val items = graph.db().read { StockDao.lowStockPage(it, null, PAGE) }
            adapter.submit(items)
            end = items.size < PAGE
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { StockDao.lowStockPage(it, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    companion object {
        private const val PAGE = 50
    }
}

/** Every stock change that is not a sale (deliveries, adjustments, opening stock), newest first. */
class MovementsActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<MovementRow>(
        bind = { h, m ->
            h.set(
                title = m.productName ?: "?",
                subtitle = listOf(DateText.dateTime(m.at, tz), InventoryUi.movementText(this, m.kind, m.reason)).joinToString(" · "),
                value = InventoryUi.signedQty(m.qty, m.unit),
            )
        },
        onClick = { startActivity(StockHistoryActivity.intent(this, it.productId)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.inv_movements), R.layout.list_plain) ?: return
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.inv_movements_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
    }

    override fun onStarted(scope: CoroutineScope) {
        job?.cancel()
        job = this.scope.launch {
            val items = graph.db().read { StockDao.movementPage(it, null, PAGE) }
            adapter.submit(items)
            end = items.size < PAGE
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { StockDao.movementPage(it, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    companion object {
        private const val PAGE = 50
    }
}
