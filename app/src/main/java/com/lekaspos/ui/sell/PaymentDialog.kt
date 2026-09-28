package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.domain.sell.Tender
import com.lekaspos.ui.common.Keypad

/**
 * Taking payment (references/money.md §4–§5): cash with change and 5-sen rounding when cash
 * settles the rest, card / e-wallet / other for exact amounts, any mix of them (split tender).
 * Payments are only recorded — no card terminal or wallet is contacted.
 */
class PaymentDialog(
    private val activity: Activity,
    private val total: Long,
    private val currency: CurrencySpec,
    private val methods: List<PaymentMethod>,
    private val onPaid: (tenders: List<Tender>, rounding: Long) -> Unit,
) {
    private val tenders = ArrayList<Tender>()
    private var remaining = total
    private lateinit var dialog: AlertDialog
    private lateinit var totalView: TextView
    private lateinit var remainingView: TextView
    private lateinit var cashHint: TextView
    private lateinit var tendersView: TextView
    private lateinit var amountView: TextView
    private lateinit var errorView: TextView
    private lateinit var quick: LinearLayout
    private val keypad = Keypad(activity, 9) { renderAmount() }

    private val step: Long get() = currency.cashStep

    fun show(): AlertDialog {
        val root = activity.layoutInflater.inflate(R.layout.dialog_payment, null)
        totalView = root.findViewById(R.id.pay_total)
        remainingView = root.findViewById(R.id.pay_remaining)
        cashHint = root.findViewById(R.id.pay_cash_hint)
        tendersView = root.findViewById(R.id.pay_tenders)
        amountView = root.findViewById(R.id.pay_amount)
        errorView = root.findViewById(R.id.pay_error)
        quick = root.findViewById(R.id.pay_quick)
        root.findViewById<FrameLayout>(R.id.pay_keypad).addView(keypad.view)
        val methodRow = root.findViewById<LinearLayout>(R.id.pay_methods)
        val density = activity.resources.displayMetrics.density
        if (total <= 0L) {
            // Nothing to pay (e.g. 100% discount): one button completes the sale.
            val b = Button(activity, null, 0, R.style.Widget_Lekas_Button_Primary)
            b.text = activity.getString(R.string.pay_complete)
            b.setOnClickListener { finish(0L) }
            methodRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.findViewById<View>(R.id.pay_keypad).visible(false)
        } else {
            for ((i, m) in methods.withIndex()) {
                val b = Button(activity, null, 0, if (m.kind == PaymentKind.CASH) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Button_Secondary)
                b.text = m.name
                b.setOnClickListener { pay(m) }
                methodRow.addView(
                    b,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = (6 * density).toInt()
                    },
                )
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pay_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnKeyListener { _, _, e -> keypad.onKey(e) }
        refresh()
        dialog.show()
        return dialog
    }

    private fun enteredAmount(): Long? = if (keypad.digits.isEmpty()) null else MoneyFormat.keypad(keypad.digits, currency)

    private fun pay(m: PaymentMethod) {
        val entered = enteredAmount()
        errorView.visible(false)
        if (m.kind == PaymentKind.CASH) {
            val given = entered ?: Settlement.cashDue(remaining, step)
            when (val r = Settlement.cash(remaining, given, step)) {
                is Settlement.Result.Settled -> {
                    tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, given, r.change))
                    finish(r.rounding)
                }
                is Settlement.Result.Partial -> {
                    tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, given, 0L))
                    remaining = r.remaining
                    refresh()
                }
                is Settlement.Result.Rejected -> error(activity.getString(R.string.pay_error_cash, money(r.minimum)))
            }
        } else {
            val amount = entered ?: remaining
            when (val r = Settlement.exact(remaining, amount)) {
                is Settlement.Result.Settled -> {
                    tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, amount, 0L))
                    finish(0L)
                }
                is Settlement.Result.Partial -> {
                    tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, amount, 0L))
                    remaining = r.remaining
                    refresh()
                }
                is Settlement.Result.Rejected -> error(activity.getString(R.string.pay_error_exceeds, m.name, money(remaining)))
            }
        }
    }

    private fun finish(rounding: Long) {
        // Hand over first: the checkout freezes the bill before the dialog releases it.
        onPaid(ArrayList(tenders), rounding)
        dialog.dismiss()
    }

    private fun error(text: String) {
        errorView.text = text
        errorView.visible(true)
    }

    private fun refresh() {
        totalView.text = money(total)
        remainingView.text = money(remaining)
        val due = Settlement.cashDue(remaining, step)
        cashHint.text = activity.getString(R.string.pay_cash_due, money(due))
        cashHint.visible(due != remaining && remaining > 0L)
        if (tenders.isEmpty()) {
            tendersView.visible(false)
        } else {
            tendersView.text = tenders.joinToString("\n") { activity.getString(R.string.pay_tender_row, it.name, money(it.applied)) }
            tendersView.visible(true)
        }
        buildQuickCash(due)
        keypad.clear()
    }

    private fun renderAmount() {
        val v = enteredAmount()
        amountView.text = if (v == null) activity.getString(R.string.pay_amount_hint, money(remaining)) else money(v)
    }

    /** "Exact" and the next banknote amounts above the cash due. */
    private fun buildQuickCash(due: Long) {
        quick.removeAllViews()
        val cash = methods.firstOrNull { it.kind == PaymentKind.CASH } ?: return
        if (due <= 0L) return
        val major = currency.scale
        val amounts = LinkedHashSet<Long>()
        amounts.add(due)
        for (note in longArrayOf(1L, 5L, 10L, 50L, 100L)) {
            val unit = Checked.mul(note, major)
            val up = Checked.mul((due + unit - 1L) / unit, unit)
            if (up > due) amounts.add(up)
            if (amounts.size >= 4) break
        }
        val density = activity.resources.displayMetrics.density
        for ((i, a) in amounts.withIndex()) {
            val b = Button(activity, null, 0, R.style.Widget_Lekas_Button_Secondary)
            b.text = if (i == 0) activity.getString(R.string.pay_exact) else MoneyFormat.format(a, currency)
            b.setOnClickListener {
                keypad.set(a.toString())
                pay(cash)
            }
            quick.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = (6 * density).toInt()
                },
            )
        }
    }

    private fun money(v: Long) = MoneyFormat.format(v, currency)
}
