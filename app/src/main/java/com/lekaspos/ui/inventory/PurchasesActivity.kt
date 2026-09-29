package com.lekaspos.ui.inventory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.purchase.PurchaseDao
import com.lekaspos.data.purchase.PurchaseRow
import com.lekaspos.data.supplier.SupplierDao
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Deliveries received (purchase records), newest first; optionally one supplier's. */
class PurchasesActivity : ScreenActivity() {

    private var supplierId: Long? = null
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<PurchaseRow>(
        bind = { h, p ->
            val currency = graph.settings.store.value.currency
            h.set(
                title = p.supplierName ?: getString(R.string.inv_no_supplier),
                subtitle = listOfNotNull(DateText.dateTime(p.at, tz), p.refNo, resources.getQuantityString(R.plurals.inv_lines, p.lineCount, p.lineCount))
                    .joinToString(" · "),
                value = MoneyFormat.format(p.total, currency),
            )
        },
        onClick = { startActivity(PurchaseDetailActivity.intent(this, it.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supplierId = intent.getLongExtra(EXTRA_SUPPLIER, 0L).takeIf { it != 0L }
        val v = setScreen(getString(R.string.inv_purchases), R.layout.list_plain) ?: return
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.inv_purchases_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        supplierId?.let { id ->
            launchUi { graph.db().read { SupplierDao.get(it, id) }?.let { s -> setScreenTitle(getString(R.string.inv_purchases_of, s.name)) } }
        }
    }

    override fun onStarted(scope: CoroutineScope) {
        job?.cancel()
        job = this.scope.launch {
            val items = graph.db().read { PurchaseDao.page(it, supplierId, null, PAGE) }
            adapter.submit(items)
            end = items.size < PAGE
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { PurchaseDao.page(it, supplierId, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    companion object {
        private const val EXTRA_SUPPLIER = "supplier"
        private const val PAGE = 50

        fun intent(ctx: Context, supplierId: Long?): Intent = Intent(ctx, PurchasesActivity::class.java).putExtra(EXTRA_SUPPLIER, supplierId ?: 0L)
    }
}

/** One delivery: supplier, reference, date and its lines. */
class PurchaseDetailActivity : ScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        setScreen(getString(R.string.inv_purchase))
        launchUi {
            val (p, lines) = graph.db().read { PurchaseDao.get(it, id) to PurchaseDao.lines(it, id) }
            if (p == null) return@launchUi finish()
            val currency = graph.settings.store.value.currency
            val f = Form(this@PurchaseDetailActivity)
            f.section(p.supplierName ?: getString(R.string.inv_no_supplier))
            f.info(listOfNotNull(DateText.dateTime(p.at, TimeZone.getDefault()), p.refNo?.let { getString(R.string.inv_ref_is, it) }).joinToString(" · "))
            p.note?.let { f.info(it) }
            f.section(getString(R.string.inv_purchase_lines))
            for (l in lines) {
                f.info(
                    getString(
                        R.string.inv_purchase_line, l.name ?: "?", InventoryUi.qty(l.qty, l.unit),
                        MoneyFormat.format(l.unitCost, currency), MoneyFormat.format(l.total, currency),
                    ),
                )
            }
            f.section(getString(R.string.inv_purchase_total, MoneyFormat.format(p.total, currency)))
            content.removeAllViews()
            content.addView(f.view)
        }
    }

    companion object {
        private const val EXTRA_ID = "id"

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, PurchaseDetailActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
