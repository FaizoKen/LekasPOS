package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import com.lekaspos.R
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.QuickCash
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.domain.sell.Tender
import com.lekaspos.ui.colorOf
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.keys
import com.lekaspos.ui.common.trackedBy

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
    /** Part payments already taken (the payment shown again after the screen was rebuilt). */
    initial: List<Tender> = emptyList(),
    /** Every part payment taken, so it can outlive this dialog (CartSession.PaymentDraft). */
    private val onTenders: (List<Tender>) -> Unit = {},
    /** Checks a non-cash tender before it is taken (customer credit: customer, permission, limit). */
    private val authorize: (method: PaymentMethod, amount: Long, done: (Boolean) -> Unit) -> Unit = { _, _, done -> done(true) },
    private val onPaid: (tenders: List<Tender>, rounding: Long) -> Unit,
) {
    private val tenders = ArrayList<Tender>(initial)
    private var remaining = total - initial.sumOf { it.applied }

    /** When the last part payment was taken: a second tap that lands right after it is not a new payment. */
    private var partAt = 0L
    private lateinit var dialog: AlertDialog
    private lateinit var totalView: TextView
    private lateinit var remainingView: TextView
    private lateinit var remainingRow: View
    private lateinit var changeView: TextView
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
        remainingRow = root.findViewById(R.id.pay_remaining_row)
        changeView = root.findViewById(R.id.pay_change)
        cashHint = root.findViewById(R.id.pay_cash_hint)
        tendersView = root.findViewById(R.id.pay_tenders)
        amountView = root.findViewById(R.id.pay_amount)
        errorView = root.findViewById(R.id.pay_error)
        quick = root.findViewById(R.id.pay_quick)
        root.findViewById<FrameLayout>(R.id.pay_keypad).addView(keypad.view)
        val methodRows = root.findViewById<LinearLayout>(R.id.pay_methods)
        val density = activity.resources.displayMetrics.density
        if (total <= 0L) {
            // Nothing to pay (e.g. 100% discount): one button completes the sale.
            val b = Button(activity, null, 0, R.style.Widget_Lekas_Button_Primary)
            b.text = activity.getString(R.string.pay_complete)
            b.setOnClickListener { finish(0L) }
            methodRows.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.findViewById<View>(R.id.pay_keypad).visible(false)
        } else {
            addMethods(methodRows, density)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pay_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // A touch beside the dialog must not throw away payments already entered (split tender).
        dialog.setCanceledOnTouchOutside(false)
        dialog.keys { e ->
            // Below Android 13 Back arrives here as a key; from 13 on, see Back33.
            if (e.keyCode == KeyEvent.KEYCODE_BACK && tenders.isNotEmpty()) {
                if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) confirmCancel()
                true
            } else {
                keypad.onKey(e)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { back() }
        }
        refresh()
        dialog.show()
        // The app takes Back through OnBackInvokedCallback (manifest), so from Android 13 on the key
        // never reaches the listener above: the dialog's own Back cancelled at once and dropped a
        // split payment half entered without asking (2026-10 review). Registered after show(), so
        // it comes before the dialog's own.
        if (Build.VERSION.SDK_INT >= 33) Back33.register(dialog) { back() }
        return dialog.trackedBy(activity)
    }

    /** Cancel or Back: closes at once while nothing is paid; with part payments taken it asks first. */
    private fun back() {
        if (tenders.isEmpty()) dialog.cancel() else confirmCancel()
    }

    @RequiresApi(33)
    private object Back33 {
        fun register(d: Dialog, onBack: () -> Unit) {
            val cb = OnBackInvokedCallback { onBack() }
            d.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
    }

    /** Part of the bill is already paid (card, e-wallet): cancelling clears those payments too, so ask. */
    private fun confirmCancel() {
        val paid = tenders.sumOf { it.applied }
        Dialogs.confirm(activity, activity.getString(R.string.pay_title), activity.getString(R.string.pay_cancel_split, money(paid)), activity.getString(R.string.pay_cancel_yes)) {
            dialog.cancel()
        }
    }

    /** Every payment method, [PER_ROW] to a row so long names (e-wallets) never get cut off. */
    private fun addMethods(rows: LinearLayout, density: Float) {
        val gap = (6 * density).toInt()
        val perRow = PER_ROW.coerceAtMost(methods.size).coerceAtLeast(1)
        for ((r, chunk) in methods.chunked(perRow).withIndex()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for ((i, m) in chunk.withIndex()) {
                val style = if (m.kind == PaymentKind.CASH) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Button_Secondary
                val b = Button(activity, null, 0, style)
                b.text = m.name
                b.minWidth = 0
                b.setOnClickListener { pay(m) }
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = gap })
            }
            // A short last row keeps the buttons the same width as the rows above.
            repeat(perRow - chunk.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gap }) }
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = gap })
        }
    }

    private fun enteredAmount(): Long? = if (keypad.digits.isEmpty()) null else MoneyFormat.keypad(keypad.digits, currency)

    private fun pay(m: PaymentMethod) {
        // The second tap of a double tap would pay the whole rest with this method.
        if (SystemClock.uptimeMillis() - partAt < DOUBLE_TAP_MS) return
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
                    val takePart = {
                        tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, given, 0L))
                        remaining = r.remaining
                        partAt = SystemClock.uptimeMillis()
                        onTenders(ArrayList(tenders))
                        refresh()
                    }
                    // The keypad fills from the right: "50" typed for RM50 is RM0.50, and was taken at
                    // once as part of the bill (2026-10 review). Cash as the first, partial payment asks.
                    if (tenders.isEmpty()) {
                        val text = activity.getString(R.string.pay_part_cash, money(given), money(r.remaining))
                        Dialogs.confirm(activity, activity.getString(R.string.pay_title), text, activity.getString(R.string.pay_part_cash_yes)) {
                            if (dialog.isShowing && tenders.isEmpty()) takePart()
                        }
                    } else {
                        takePart()
                    }
                }
                is Settlement.Result.Rejected -> error(activity.getString(R.string.pay_error_cash, money(r.minimum)))
            }
        } else {
            val amount = entered ?: remaining
            val r = Settlement.exact(remaining, amount)
            if (r is Settlement.Result.Rejected) {
                error(activity.getString(R.string.pay_error_exceeds, m.name, money(remaining)))
                return
            }
            val before = remaining
            authorize(m, amount) { ok ->
                // Ignore a late answer if the dialog moved on meanwhile.
                if (ok && remaining == before && dialog.isShowing) take(m, amount, r)
            }
        }
    }

    private fun take(m: PaymentMethod, amount: Long, r: Settlement.Result) {
        when (r) {
            is Settlement.Result.Settled -> {
                tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, amount, 0L))
                finish(0L)
            }
            is Settlement.Result.Partial -> {
                tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, amount, 0L))
                remaining = r.remaining
                partAt = SystemClock.uptimeMillis()
                onTenders(ArrayList(tenders))
                refresh()
            }
            is Settlement.Result.Rejected -> Unit
        }
    }

    private fun finish(rounding: Long) {
        // Hand over first: the checkout freezes the bill before the dialog releases it.
        onPaid(ArrayList(tenders), rounding)
        dialog.dismiss()
    }

    /** In the change's slot (dialog_payment): nothing below it moves. */
    private fun error(text: String) {
        errorView.text = text
        errorView.visible(true)
        changeView.visibility = View.INVISIBLE
    }

    private fun refresh() {
        totalView.text = money(total)
        remainingView.text = money(remaining)
        remainingRow.visible(tenders.isNotEmpty()) // only a split payment has something "still to pay"
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
        // Nothing typed: the amount still to pay, in grey — what "Cash" takes if pressed now.
        amountView.text = money(v ?: remaining)
        amountView.setTextColor(activity.colorOf(if (v == null) R.color.text_disabled else R.color.text_primary))
        // The change shows while the cashier types what the customer gave, before any button.
        val due = Settlement.cashDue(remaining, step)
        val change = if (v != null && v > due) v - due else 0L
        changeView.text = activity.getString(R.string.pay_change, money(change))
        if (v != null) errorView.visible(false) // typing again: the refusal is old news, the change shows
        // Invisible, not gone: its slot keeps its height, so the keys below never move while typing.
        changeView.visibility = if (change > 0L && errorView.visibility != View.VISIBLE) View.VISIBLE else View.INVISIBLE
    }

    /** "Exact" and the notes customers hand over for the cash due (`:core` QuickCash). */
    private fun buildQuickCash(due: Long) {
        quick.removeAllViews()
        val cash = methods.firstOrNull { it.kind == PaymentKind.CASH } ?: return
        if (due <= 0L) return
        val amounts = listOf(due) + QuickCash.amounts(due, currency.scale)
        val density = activity.resources.displayMetrics.density
        for ((i, a) in amounts.withIndex()) {
            // One line that shrinks to fit: at large fonts "RM100" wrapped to "RM10" over "0" (2026-10 review).
            val b = com.lekaspos.ui.common.FitButton(activity, null, 0, R.style.Widget_Lekas_Button_Secondary)
            b.text = if (i == 0) activity.getString(R.string.pay_exact) else wholeMoney(a)
            b.minWidth = 0 // four notes across a 5-inch phone
            val side = (4 * density).toInt()
            b.setPadding(side, b.paddingTop, side, b.paddingBottom)
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

    /** "RM100" for a note: "RM100.00" did not fit a quarter of a small phone's width (2026-10 review). */
    private fun wholeMoney(v: Long): String {
        val s = money(v)
        val zeros = "." + "0".repeat(currency.decimals)
        return if (currency.decimals > 0 && s.endsWith(zeros)) s.dropLast(zeros.length) else s
    }

    private companion object {
        const val PER_ROW = 3
        const val DOUBLE_TAP_MS = 600L
    }
}
