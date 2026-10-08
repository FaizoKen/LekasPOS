package com.lekaspos.ui.settings

import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.audit.AuditRow
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sell.visible
import com.lekaspos.ui.staff.permLabel
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The activity log (needs VIEW_AUDIT): who did which sensitive action and who approved it —
 * voids, refunds, overrides, discounts, drawer opens, sign-ins, staff and shift changes.
 */
class AuditLogActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()
    private var names: Map<Long, String> = emptyMap()

    /** 0 = every action. */
    private var filter = 0

    private val adapter = RowAdapter<AuditRow>(
        bind = { h, a ->
            val currency = graph.settings.store.value.currency
            val money = a.amount?.takeIf { it != 0L && a.action in MONEY_ACTIONS }?.let { MoneyFormat.format(it, currency) }
            val who = a.staffId?.let { names[it] }
            val approver = a.approvedBy?.let { names[it] }?.let { getString(R.string.audit_approved_by, it) }
            val detail = if (a.action == AuditAction.APPROVAL) permNames(a.amount ?: 0L) else a.detail
            val sub = listOfNotNull(DateText.dateTime(a.at, tz), who, approver, detail).joinToString(" · ")
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
        addAction(R.drawable.ic_search, R.string.audit_filter) { chooseFilter() }
        guard(Perm.VIEW_AUDIT)
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        job?.cancel()
        val f = filter
        job = launchUi {
            val (items, staff) = graph.db().read { r ->
                (if (f == 0) AuditDao.recent(r, null, PAGE) else AuditDao.byAction(r, f, null, PAGE)) to StaffDao.names(r)
            }
            names = staff
            adapter.submit(items)
            end = items.size < PAGE
            empty.visible(items.isEmpty())
            setScreenTitle(if (f == 0) getString(R.string.settings_audit) else getString(actionName(f)))
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        val f = filter
        job = launchUi {
            val more = graph.db().read { r -> if (f == 0) AuditDao.recent(r, after, PAGE) else AuditDao.byAction(r, f, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    private fun chooseFilter() {
        val actions = listOf(0) + ACTIONS
        val labels = actions.map { if (it == 0) getString(R.string.audit_all) else getString(actionName(it)) }
        Dialogs.choose(this, getString(R.string.audit_filter), labels, actions.indexOf(filter).coerceAtLeast(0)) { i ->
            filter = actions[i]
            reload()
        }
    }

    private fun permNames(bits: Long): String = Perm.LIST.filter { Perm.has(bits, it) }.joinToString(", ") { getString(permLabel(it)) }

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
        AuditAction.PROMOTION_CHANGE -> R.string.audit_promotion
        AuditAction.BILL_CANCEL -> R.string.audit_bill_cancel
        AuditAction.APPROVAL -> R.string.audit_approval
        AuditAction.SIGN_IN -> R.string.audit_sign_in
        AuditAction.PIN_LOCKOUT -> R.string.audit_pin_lockout
        AuditAction.STAFF_CHANGE -> R.string.audit_staff
        AuditAction.ROLE_CHANGE -> R.string.audit_role
        AuditAction.SHIFT_OPEN -> R.string.audit_shift_open
        AuditAction.SHIFT_CLOSE -> R.string.audit_shift_close
        AuditAction.CASH_IN -> R.string.shift_cash_in
        AuditAction.CASH_OUT -> R.string.shift_cash_out
        AuditAction.CASH_DROP -> R.string.shift_drop
        AuditAction.CREDIT_ADJUST -> R.string.credit_adjust_title
        AuditAction.CREDIT_OVER_LIMIT -> R.string.audit_over_limit
        AuditAction.OWNER_PIN_RESET -> R.string.audit_pin_reset
        AuditAction.PRODUCT_IMPORT -> R.string.audit_product_import
        AuditAction.CREDIT_LIMIT_CHANGE -> R.string.audit_credit_limit
        AuditAction.SETTINGS_CHANGE -> R.string.audit_settings
        AuditAction.CREDIT_PAYMENT -> R.string.audit_credit_payment
        AuditAction.LINE_REMOVE -> R.string.audit_line_remove
        AuditAction.LINE_REMOVE_AFTER_PAY -> R.string.audit_line_remove_after_pay
        AuditAction.BILL_CANCEL_AFTER_PAY -> R.string.audit_bill_cancel_after_pay
        AuditAction.STOCK_WRITE_OFF -> R.string.audit_write_off
        AuditAction.SHIFT_CONTINUED -> R.string.audit_shift_continued
        else -> R.string.audit_other
    }

    companion object {
        private const val PAGE = 50
        private val ACTIONS = listOf(
            AuditAction.SALE_VOID, AuditAction.REFUND, AuditAction.PRICE_OVERRIDE, AuditAction.LINE_DISCOUNT,
            AuditAction.BILL_DISCOUNT, AuditAction.BILL_CANCEL, AuditAction.DRAWER_OPEN, AuditAction.REPRINT,
            AuditAction.PRODUCT_PRICE_CHANGE, AuditAction.PRODUCT_DELETE, AuditAction.APPROVAL, AuditAction.SIGN_IN,
            AuditAction.PIN_LOCKOUT, AuditAction.STAFF_CHANGE, AuditAction.ROLE_CHANGE, AuditAction.SHIFT_OPEN,
            AuditAction.SHIFT_CLOSE, AuditAction.CASH_IN, AuditAction.CASH_OUT, AuditAction.CASH_DROP,
            AuditAction.CREDIT_ADJUST, AuditAction.CREDIT_OVER_LIMIT, AuditAction.OWNER_PIN_RESET,
            AuditAction.CREDIT_LIMIT_CHANGE, AuditAction.CREDIT_PAYMENT, AuditAction.PRODUCT_IMPORT,
            AuditAction.PROMOTION_CHANGE, AuditAction.SETTINGS_CHANGE, AuditAction.LINE_REMOVE, AuditAction.LINE_REMOVE_AFTER_PAY,
            AuditAction.BILL_CANCEL_AFTER_PAY, AuditAction.STOCK_WRITE_OFF, AuditAction.SHIFT_CONTINUED,
        )
        private val MONEY_ACTIONS = setOf(
            AuditAction.SALE_VOID, AuditAction.REFUND, AuditAction.PRICE_OVERRIDE, AuditAction.PRODUCT_PRICE_CHANGE,
            AuditAction.BILL_CANCEL, AuditAction.SHIFT_OPEN, AuditAction.SHIFT_CLOSE, AuditAction.CASH_IN, AuditAction.CASH_OUT,
            AuditAction.CASH_DROP, AuditAction.CREDIT_ADJUST, AuditAction.CREDIT_OVER_LIMIT, AuditAction.CREDIT_LIMIT_CHANGE,
            AuditAction.CREDIT_PAYMENT, AuditAction.LINE_REMOVE, AuditAction.LINE_REMOVE_AFTER_PAY, AuditAction.BILL_CANCEL_AFTER_PAY,
            AuditAction.STOCK_WRITE_OFF,
        )
    }
}
