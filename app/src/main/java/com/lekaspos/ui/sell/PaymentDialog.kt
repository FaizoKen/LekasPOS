package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
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
import com.lekaspos.ui.common.sideways
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.common.wide

/**
 * Taking payment (references/money.md §4–§5): cash with change and 5-sen rounding when cash
 * settles the rest, card / e-wallet / other for exact amounts, any mix of them (split tender).
 * Payments are only recorded — no card terminal or wallet is contacted.
 *
 * Two steps (D-064). First, how the customer pays: the total to pay; Cash — one tap on the amount due or
 * the note the customer gave, or "Other amount"; then the other ways to pay, each paying the whole rest.
 * Second, only when asked for, an amount on the keypad: the cash the customer gave ("Other amount", with
 * the change as it is typed), or one part of a split payment with any way to pay.
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
    private enum class Step { CHOOSE, CASH, SPLIT }

    private val tenders = ArrayList<Tender>(initial)
    private var remaining = total - initial.sumOf { it.applied }
    private var step = Step.CHOOSE

    /**
     * When the dialog or a step appeared, or a part payment was taken. A tap landing right after is the
     * second half of a double tap (on Pay, Back, a payment): it would pay with whatever is under the finger.
     */
    private var shownAt = 0L
    private lateinit var dialog: AlertDialog
    private lateinit var v: PaymentViews
    private val keypad = Keypad(activity, 9) { renderAmount() }
    private val cash: PaymentMethod? = methods.firstOrNull { it.kind == PaymentKind.CASH }

    private val cashStep: Long get() = currency.cashStep

    fun show(): AlertDialog {
        // Held sideways: the same steps in columns, so nothing is below the fold on a phone (D-063).
        val wide = activity.sideways()
        v = PaymentViews(activity, wide)
        v.keypad.addView(keypad.view)
        v.cancel.setOnClickListener { back() }
        v.back.setOnClickListener { back() }
        v.split.setOnClickListener { go(Step.SPLIT) }
        v.done.setOnClickListener { cash?.let { pay(it, enteredAmount()) } }
        v.complete.setOnClickListener { if (SystemClock.uptimeMillis() - shownAt >= GUARD_MS) finish(0L) }
        val others = methods.filter { it.kind != PaymentKind.CASH }
        if (total <= 0L) {
            // Nothing to pay (e.g. 100% discount): one button completes the sale.
            v.complete.visible(true)
            v.cash.visible(false)
            v.others.visible(false)
            v.split.visible(false)
        } else {
            v.cash.visible(cash != null)
            v.others.visible(others.isNotEmpty())
            v.othersLabel.setText(if (cash != null) R.string.pay_other_ways else R.string.pay_ways)
            v.methods(others.map { it.name }) { pay(others[it], null) }
            v.split.visible(methods.size > 1)
            v.partMethods(methods.map { it.name }, methods.indexOf(cash)) { pay(methods[it], enteredAmount()) }
        }
        dialog = AlertDialog.Builder(activity).setView(v.root).create()
        // A touch beside the dialog must not throw away payments already entered (split tender).
        dialog.setCanceledOnTouchOutside(false)
        dialog.keys { e -> onKey(e) }
        if (wide) dialog.setOnShowListener { dialog.wide() }
        refresh()
        go(Step.CHOOSE)
        dialog.show()
        // The app takes Back through OnBackInvokedCallback (manifest), so from Android 13 on the key
        // never reaches the listener above: the dialog's own Back cancelled at once and dropped a
        // split payment half entered without asking (2026-10 review). Registered after show(), so
        // it comes before the dialog's own.
        if (Build.VERSION.SDK_INT >= 33) Back33.register(dialog) { back() }
        return dialog.trackedBy(activity)
    }

    private fun onKey(e: KeyEvent): Boolean {
        // Below Android 13 Back arrives here as a key; from 13 on, see Back33.
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        // A number typed on a keyboard on the first step: the cash the customer gave.
        val digit = e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || e.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
        if (digit && step == Step.CHOOSE && e.action == KeyEvent.ACTION_DOWN) {
            if (cash == null || total <= 0L) return true
            go(Step.CASH)
        }
        return keypad.onKey(e)
    }

    /**
     * Back or Cancel: from the keypad back to the first step; there, closes at once while nothing is paid,
     * and asks first once part of the bill is paid.
     */
    private fun back() {
        when {
            step != Step.CHOOSE -> go(Step.CHOOSE)
            tenders.isEmpty() -> dialog.cancel()
            else -> confirmCancel()
        }
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

    /** Shows [to]: the keypad starts empty, showing in grey what a payment without typing takes. */
    private fun go(to: Step) {
        step = to
        shownAt = SystemClock.uptimeMillis()
        v.showStep(amount = to != Step.CHOOSE)
        v.amountLabel.setText(if (to == Step.SPLIT) R.string.pay_amount_part else R.string.pay_amount_cash)
        v.done.visible(to == Step.CASH)
        v.part.visible(to == Step.SPLIT)
        v.error.visible(false)
        val due = if (to == Step.CASH) Settlement.cashDue(remaining, cashStep) else remaining
        v.due.text = activity.getString(if (tenders.isEmpty()) R.string.pay_due else R.string.pay_due_rest, money(due))
        keypad.clear() // renders the amount
    }

    /** What was typed; nothing (or zero) means "the amount due", as the grey amount shows. */
    private fun enteredAmount(): Long? =
        if (keypad.digits.isEmpty()) null else MoneyFormat.keypad(keypad.digits, currency)?.takeIf { it > 0L }

    /** Pays [entered] (null: what is still due) with [m]. */
    private fun pay(m: PaymentMethod, entered: Long?) {
        if (SystemClock.uptimeMillis() - shownAt < GUARD_MS) return
        // An old refusal goes, but keeps its room: the slot never shrinks under the cashier's finger.
        if (v.error.visibility == View.VISIBLE) v.error.visibility = View.INVISIBLE
        if (m.kind == PaymentKind.CASH) {
            val given = entered ?: Settlement.cashDue(remaining, cashStep)
            when (val r = Settlement.cash(remaining, given, cashStep)) {
                is Settlement.Result.Settled -> {
                    tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, given, r.change))
                    finish(r.rounding)
                }
                is Settlement.Result.Partial -> {
                    val takePart = {
                        tenders.add(Tender(m.id, m.kind, m.name, m.opensDrawer, r.applied, given, 0L))
                        partTaken(r.remaining)
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
                partTaken(r.remaining)
            }
            is Settlement.Result.Rejected -> Unit
        }
    }

    /** A part of the bill is paid: back to the first step for the rest. */
    private fun partTaken(rest: Long) {
        remaining = rest
        onTenders(ArrayList(tenders))
        refresh()
        go(Step.CHOOSE)
    }

    private fun finish(rounding: Long) {
        // Hand over first: the checkout freezes the bill before the dialog releases it.
        onPaid(ArrayList(tenders), rounding)
        dialog.dismiss()
    }

    /** In the change's slot on the keypad step: nothing below it moves. */
    private fun error(text: String) {
        if (step == Step.CHOOSE) {
            Dialogs.message(activity, null, text)
            return
        }
        v.error.text = text
        v.error.visible(true)
        v.change.visibility = View.INVISIBLE
    }

    /** The first step: what is still to pay, what is paid already, and the cash buttons for it. */
    private fun refresh() {
        v.totalLabel.setText(if (tenders.isEmpty()) R.string.pay_to_pay else R.string.pay_remaining)
        v.total.text = money(remaining)
        if (tenders.isEmpty()) {
            v.tenders.visible(false)
        } else {
            val paid = tenders.joinToString(", ") { activity.getString(R.string.pay_tender_row, it.name, money(it.applied)) }
            v.tenders.text = activity.getString(R.string.pay_paid, paid) + "\n" + activity.getString(R.string.pay_bill_total, money(total))
            v.tenders.visible(true)
        }
        val due = Settlement.cashDue(remaining, cashStep)
        v.cashHint.text = activity.getString(R.string.pay_cash_due, money(due))
        v.cashHint.visible(cash != null && due != remaining && remaining > 0L)
        val cashMethod = cash ?: return
        if (total <= 0L) return
        // The amount due, then the notes customers hand over for it (`:core` QuickCash).
        val amounts = listOf(due) + QuickCash.amounts(due, currency.scale, max = NOTES)
        v.notes(amounts.map { wholeMoney(it) }, activity.getString(R.string.pay_other_amount), { pay(cashMethod, amounts[it]) }, { go(Step.CASH) })
    }

    private fun renderAmount() {
        val typed = enteredAmount()
        val due = Settlement.cashDue(remaining, cashStep)
        // Nothing typed: in grey, what a payment takes if pressed now — the cash due, or the rest of the bill.
        v.amount.text = money(typed ?: if (step == Step.SPLIT) remaining else due)
        v.amount.setTextColor(activity.colorOf(if (typed == null) R.color.text_disabled else R.color.text_primary))
        // The change shows while the cashier types what the customer gave, before any button.
        val change = if (typed != null && typed > due) typed - due else 0L
        v.change.text = activity.getString(R.string.pay_change, money(change))
        // Typing again: the refusal is old news and the change shows — invisible, not gone, as a refusal of
        // three lines made the slot taller, and the keys would move up under the next digit.
        if (typed != null && v.error.visibility == View.VISIBLE) v.error.visibility = View.INVISIBLE
        // Invisible, not gone: its slot keeps its height, so the keys below never move while typing.
        v.change.visibility = if (change > 0L && v.error.visibility != View.VISIBLE) View.VISIBLE else View.INVISIBLE
    }

    private fun money(v: Long) = MoneyFormat.format(v, currency)

    /** "RM100" for a note: "RM100.00" did not fit a small phone's button (2026-10 review). */
    private fun wholeMoney(v: Long): String {
        val s = money(v)
        val zeros = "." + "0".repeat(currency.decimals)
        return if (currency.decimals > 0 && s.endsWith(zeros)) s.dropLast(zeros.length) else s
    }

    private companion object {
        /** Notes offered besides the amount due: with it and "Other amount", two rows of three. */
        const val NOTES = 4

        /** A double tap's second tap comes within about this long. */
        const val GUARD_MS = 500L
    }
}
