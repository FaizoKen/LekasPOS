package com.lekaspos.ui.settings

import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.audit.AuditRow
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The audit log: voids, refunds, price changes, discounts, drawer opens, reprints. */
class AuditLogActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<AuditRow>(
        bind = { h, a ->
            val currency = graph.settings.store.value.currency
            val money = a.amount?.takeIf { it != 0L && a.action in MONEY_ACTIONS }?.let { MoneyFormat.format(it, currency) }
            val sub = listOfNotNull(DateText.dateTime(a.at, tz), a.detail).joinToString(" · ")
            h.set(getString(actionName(a.action)), sub, money)
        },
        onClick = {},
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.settings_audit), R.layout.list_plain) ?: return
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.audit_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
    }

    override fun onStarted(scope: CoroutineScope) {
        job?.cancel()
        job = this.scope.launch {
            val items = graph.db().read { AuditDao.recent(it, null, PAGE) }
            adapter.submit(items)
            end = items.size < PAGE
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { AuditDao.recent(it, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    private fun actionName(action: Int): Int = when (action) {
        AuditAction.SALE_VOID -> R.string.audit_sale_void
        AuditAction.REFUND -> R.string.audit_refund
        AuditAction.PRICE_OVERRIDE -> R.string.audit_price_override
        AuditAction.LINE_DISCOUNT -> R.string.audit_line_discount
        AuditAction.BILL_DISCOUNT -> R.string.audit_bill_discount
        AuditAction.DRAWER_OPEN -> R.string.audit_drawer
        AuditAction.REPRINT -> R.string.audit_reprint
        AuditAction.PRODUCT_PRICE_CHANGE -> R.string.audit_product_price
        AuditAction.PRODUCT_DELETE -> R.string.audit_product_delete
        AuditAction.BILL_CANCEL -> R.string.audit_bill_cancel
        else -> R.string.audit_other
    }

    companion object {
        private const val PAGE = 50
        private val MONEY_ACTIONS = setOf(
            AuditAction.SALE_VOID, AuditAction.REFUND, AuditAction.PRICE_OVERRIDE, AuditAction.PRODUCT_PRICE_CHANGE, AuditAction.BILL_CANCEL,
        )
    }
}
