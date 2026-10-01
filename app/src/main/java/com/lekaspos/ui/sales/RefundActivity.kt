package com.lekaspos.ui.sales

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.refund.Refunds
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.domain.sale.SaleActions
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.AmountDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Refund / return (references/money.md §6): pick what comes back (up to what is left of each
 * line), whether it goes back into stock, a reason, and how the money is paid back. The
 * refund is its own numbered document; amounts are exactly what was paid for those items.
 */
class RefundActivity : ScreenActivity() {

    private var saleId = 0L
    private var info: SaleActions.RefundInfo? = null
    private val picks = HashMap<Long, Long>()
    private var methods: List<PaymentMethod> = emptyList()
    private lateinit var totalView: TextView
    private lateinit var restock: Switch
    private lateinit var reason: EditText
    private lateinit var method: Spinner
    private lateinit var submit: Button
    private val qtyViews = HashMap<Long, Button>()

    // A double tap on "Refund" opened two confirm dialogs, and accepting both paid a partial
    // refund out twice (2026-10 review): one dialog at a time, one refund per screen.
    private var confirmDialog: AlertDialog? = null
    private var refunding = false

    private val currency get() = graph.settings.store.value.currency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saleId = intent.getLongExtra(EXTRA_SALE_ID, 0L)
        setScreen(getString(R.string.refund_title))
        launchUi {
            val data = graph.sales.refundInfo(saleId)
            if (data == null) {
                finish()
                return@launchUi
            }
            // Back onto the customer's account only for a sale that has a customer.
            val credit = data.header.customerId != null
            methods = graph.db().read { PaymentMethodDao.active(it) }.filter { it.kind != PaymentKind.CREDIT || credit }
            info = data
            build(data)
        }
    }

    private fun build(data: SaleActions.RefundInfo) {
        val form = Form(this)
        form.info(getString(R.string.refund_of, data.header.receiptNo))
        val density = resources.displayMetrics.density
        for (line in data.lines) {
            val src = data.sources[line.id] ?: continue
            if (src.remainingQty <= 0L) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (56 * density).toInt()
            }
            val text = TextView(this, null, 0, R.style.Text_Lekas_Body).apply {
                this.text = getString(
                    R.string.refund_line, line.name, MoneyFormat.formatQty(src.remainingQty), MoneyFormat.formatQty(line.qty),
                )
            }
            val qty = Button(this, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
                this.text = "0"
                setOnClickListener { askQty(line.id, line.id in data.weighed, src.remainingQty) }
            }
            val all = Button(this, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
                this.text = getString(R.string.refund_all)
                setOnClickListener { setPick(line.id, if (picks[line.id] == src.remainingQty) 0L else src.remainingQty) }
            }
            qtyViews[line.id] = qty
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(qty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (6 * density).toInt() })
            form.add(row)
        }
        if (qtyViews.isEmpty()) form.info(getString(R.string.refund_nothing_left))
        restock = form.switch(getString(R.string.refund_restock), true)
        reason = form.text(getString(R.string.refund_reason), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        method = form.choice(getString(R.string.refund_method), methods.map { it.name }, 0) { renderTotal() }
        totalView = form.info("")
        submit = form.button(getString(R.string.refund_do), primary = true) { confirm() }
        content.removeAllViews()
        content.addView(form.view)
        renderTotal()
    }

    private fun askQty(lineId: Long, weighed: Boolean, max: Long) {
        AmountDialog(
            this, getString(R.string.refund_qty_title),
            if (weighed) AmountDialog.Kind.WEIGHT else AmountDialog.Kind.PIECES, currency, allowZero = true, initial = picks[lineId] ?: 0L,
        ) { setPick(lineId, it.coerceIn(0L, max)) }.show()
    }

    private fun setPick(lineId: Long, qty: Long) {
        if (qty <= 0L) picks.remove(lineId) else picks[lineId] = qty
        qtyViews[lineId]?.text = MoneyFormat.formatQty(qty)
        renderTotal()
    }

    /** Refund amount for the current picks, cash rounding included when paying back in cash. */
    private fun refundDue(): Long? {
        val data = info ?: return null
        if (picks.isEmpty()) return null
        val parts = picks.mapNotNull { (id, qty) -> data.sources[id]?.let { Refunds.part(it, qty) } }
        val due = Refunds.totals(parts, data.header.pricesInclTax).due
        val m = methods.getOrNull(method.selectedItemPosition)
        return if (m?.kind == PaymentKind.CASH && due > 0L) Settlement.cashDue(due, currency.cashStep) else due
    }

    private fun renderTotal() {
        if (!::submit.isInitialized) return // the method spinner reports its first selection during layout
        val due = refundDue()
        totalView.text = if (due == null) "" else getString(R.string.refund_total, MoneyFormat.format(due, currency))
        submit.isEnabled = due != null
    }

    private fun confirm() {
        if (refunding || confirmDialog?.isShowing == true) return
        val due = refundDue() ?: return
        val why = reason.text.toString().trim()
        if (why.isEmpty()) {
            reason.error = getString(R.string.reason_required)
            reason.requestFocus()
            return
        }
        val m = methods.getOrNull(method.selectedItemPosition) ?: return
        confirmDialog = Dialogs.confirm(
            this, getString(R.string.refund_title), getString(R.string.refund_confirm, MoneyFormat.format(due, currency), m.name),
            getString(R.string.refund_do),
        ) {
            val chosen = HashMap(picks)
            val back = restock.isChecked
            withApproval(Perm.REFUND) { approval ->
                if (refunding) return@withApproval
                refunding = true
                submit.isEnabled = false
                launchUi {
                    var done = false
                    try {
                        // The refund itself runs in the app scope: leaving this screen cannot cut it off.
                        val sale = graph.appScope.async(Dispatchers.Main) { graph.sales.refund(saleId, chosen, back, why, m, approval) }.await()
                        done = true
                        toast(getString(R.string.refund_done, sale.receiptNo))
                        finish()
                    } finally {
                        // Made: the screen stays locked until it closes. Refused or failed: try again.
                        if (!done) {
                            refunding = false
                            submit.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SALE_ID = "sale_id"

        fun newIntent(ctx: Context, saleId: Long): Intent = Intent(ctx, RefundActivity::class.java).putExtra(EXTRA_SALE_ID, saleId)
    }
}
