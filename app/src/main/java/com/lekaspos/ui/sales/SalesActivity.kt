package com.lekaspos.ui.sales

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.sale.ReceiptNumbers
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleRow
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sales and refunds, newest first (keyset-paged), with lookup by receipt number. */
class SalesActivity : ScreenActivity() {

    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<SaleRow>(
        bind = { h, s ->
            val currency = graph.settings.store.value.currency
            h.set(
                title = s.receiptNo,
                subtitle = DateText.dateTime(s.soldAt, tz),
                value = MoneyFormat.format(s.total, currency),
                tag = when {
                    s.status == SaleStatus.VOIDED -> getString(R.string.sale_voided)
                    s.kind == SaleKind.REFUND -> getString(R.string.sale_refund)
                    else -> null
                },
            )
        },
        onClick = { startActivity(SaleDetailActivity.newIntent(this, it.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.sales_title), R.layout.list_with_search) ?: return
        search = v.findViewById(R.id.list_search)
        search.setHint(R.string.sales_search_hint)
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
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

    override fun onStarted(scope: CoroutineScope) {
        // Its menu entry's name: "Receipts" for whoever may not refund or void (D-063).
        val refunds = graph.permissions.shown(Perm.REFUND) || graph.permissions.shown(Perm.VOID)
        setScreenTitle(getString(if (refunds) R.string.sales_title else R.string.menu_receipts))
        reload(debounce = false)
    }

    private fun reload(debounce: Boolean) {
        job?.cancel()
        val q = search.text.toString().trim().uppercase()
        job = scope.launch {
            if (debounce) delay(250L)
            val db = graph.db()
            val items = db.read { r ->
                if (q.isEmpty()) {
                    SaleDao.history(r, null, PAGE)
                } else {
                    // A number alone: this till's receipt first, then those of the shop's other tills.
                    val own = ReceiptNumbers.prefix(r, db.deviceNo)
                    val prefixes = if (ReceiptNumbers.isNumber(q)) listOf(own) + SaleDao.receiptPrefixes(r).filter { it != own } else listOf(own)
                    ReceiptNumbers.candidates(q, prefixes).mapNotNull { SaleDao.byReceipt(r, it) }.distinctBy { it.id }
                }
            }
            adapter.submit(items)
            end = q.isNotEmpty() || items.size < PAGE
            empty.setText(if (q.isEmpty()) R.string.sales_empty else R.string.sales_none_found)
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { SaleDao.history(it, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    companion object {
        private const val PAGE = 50
    }
}
