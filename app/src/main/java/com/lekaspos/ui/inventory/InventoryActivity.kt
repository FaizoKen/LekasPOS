package com.lekaspos.ui.inventory

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.data.stock.StockDao
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Inventory hub: receive, adjust, count, low stock, suppliers, deliveries, stock changes. */
class InventoryActivity : ScreenActivity() {

    private class Entry(val title: Int, val subtitle: Int, val open: () -> Unit)

    private var lowCount: Long? = null
    private lateinit var adapter: RowAdapter<Entry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.inv_title), R.layout.list_plain) ?: return
        val entries = listOf(
            Entry(R.string.inv_receive, R.string.inv_receive_sub) { startActivity(Intent(this, ReceiveActivity::class.java)) },
            Entry(R.string.inv_adjust, R.string.inv_adjust_sub) {
                @Suppress("DEPRECATION")
                startActivityForResult(ProductPickActivity.intent(this, getString(R.string.inv_adjust)), REQ_ADJUST)
            },
            Entry(R.string.inv_count, R.string.inv_count_sub) { startActivity(Intent(this, CountSessionsActivity::class.java)) },
            Entry(R.string.inv_low_stock, R.string.inv_low_stock_sub) { startActivity(Intent(this, LowStockActivity::class.java)) },
            Entry(R.string.inv_suppliers, R.string.inv_suppliers_sub) { startActivity(Intent(this, SuppliersActivity::class.java)) },
            Entry(R.string.inv_purchases, R.string.inv_purchases_sub) { startActivity(PurchasesActivity.intent(this, null)) },
            Entry(R.string.inv_movements, R.string.inv_movements_sub) { startActivity(Intent(this, MovementsActivity::class.java)) },
        )
        adapter = RowAdapter(
            bind = { h, e ->
                val tag = if (e.title == R.string.inv_low_stock) lowCount?.takeIf { it > 0L }?.toString() else null
                h.set(getString(e.title), getString(e.subtitle), tag = tag)
            },
            onClick = { it.open() },
        )
        adapter.submit(entries)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStarted(scope: CoroutineScope) {
        scope.launch {
            lowCount = graph.db().read { StockDao.lowStockCount(it) }
            adapter.notifyItemChanged(3)
        }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getLongExtra(ProductPickActivity.EXTRA_PRODUCT_ID, 0L) ?: 0L
        if (requestCode == REQ_ADJUST && resultCode == RESULT_OK && id != 0L) adjust(this, id)
    }

    companion object {
        private const val REQ_ADJUST = 41

        /** Opens the adjustment dialog for [productId] and saves it (shared by several screens). */
        fun adjust(a: ScreenActivity, productId: Long, done: () -> Unit = {}) = a.adjustProduct(productId, done)
    }
}

/** Adjust-stock flow usable from any screen. */
fun ScreenActivity.adjustProduct(productId: Long, done: () -> Unit = {}) {
    launchUi {
        val p = InventoryUi.product(graph, productId) ?: return@launchUi
        InventoryUi.askAdjust(this@adjustProduct, p) { reason, qty, removing, note ->
            launchUi {
                graph.inventory.adjust(p.id, reason, qty, removing, note)
                toast(getString(R.string.inv_adjusted, p.name))
                done()
            }
        }
    }
}
