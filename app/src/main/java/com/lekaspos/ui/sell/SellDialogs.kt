package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.core.time.DateText
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.ui.colorOf
import com.lekaspos.ui.common.DialogKeys
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.PadDialog
import com.lekaspos.ui.common.keys
import com.lekaspos.ui.common.trackedBy
import java.util.TimeZone

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.display(): TextView = TextView(this, null, 0, R.style.Text_Lekas_Display).apply {
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    setPadding(dp(12), dp(8), dp(12), dp(8))
    setBackgroundResource(R.drawable.field_bg)
}

private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** An amount shown to be kept or typed over (Keypad.replacing) is grey, like the payment's "still to pay". */
internal fun TextView.replacing(on: Boolean) {
    setTextColor(context.colorOf(if (on) R.color.text_disabled else R.color.text_primary))
}

/** "1.253" style weight with all three decimals while typing. */
fun weightText(milli: Long): String {
    val frac = (milli % 1000L).toString().padStart(3, '0')
    return "${milli / 1000L}.$frac"
}

/** Keypad entry of one money amount, piece count, weight or percentage. */
class AmountDialog(
    private val activity: Activity,
    private val title: CharSequence,
    private val kind: Kind,
    private val currency: CurrencySpec,
    private val unit: String? = null,
    private val initial: Long = 0L,
    private val message: CharSequence? = null,
    private val allowZero: Boolean = false,
    /**
     * A button beside OK for money ([Kind.MONEY]): its action gets a way to put an amount in, which
     * the cashier then confirms with OK (counting the drawer by notes and coins).
     */
    private val extra: Pair<CharSequence, (setAmount: (Long) -> Unit) -> Unit>? = null,
    private val onOk: (Long) -> Unit,
) {
    enum class Kind { MONEY, PIECES, WEIGHT, PERCENT }

    init {
        require(extra == null || kind == Kind.MONEY) { "only money takes an amount from elsewhere" }
    }

    private val display = activity.display()
    /** Digits the keypad takes: no bill line is ever 100,000 pieces or 100,000 kg (CartSession.MAX_QTY). */
    private val keypad = Keypad(
        activity,
        when (kind) {
            Kind.PERCENT -> 3
            Kind.PIECES -> 5
            Kind.WEIGHT -> 8
            Kind.MONEY -> 9
        },
    ) { render(it) }

    fun value(digits: String): Long? {
        if (digits.isEmpty()) return if (allowZero) 0L else null
        val v = when (kind) {
            Kind.MONEY -> MoneyFormat.keypad(digits, currency)
            Kind.PIECES -> digits.toLongOrNull()?.times(1000L)
            Kind.WEIGHT -> digits.toLongOrNull()
            Kind.PERCENT -> digits.toLongOrNull()?.times(100L)?.takeIf { it <= 10_000L }
        } ?: return null
        return if (v == 0L && !allowZero) null else v
    }

    private fun render(digits: String) {
        val v = value(digits) ?: 0L
        display.text = when (kind) {
            Kind.MONEY -> MoneyFormat.format(v, currency)
            Kind.PIECES -> MoneyFormat.formatQty(v)
            Kind.WEIGHT -> weightText(v) + (unit?.let { " $it" } ?: "")
            Kind.PERCENT -> ReceiptLayout.percent(v.toInt())
        }
        display.replacing(keypad.replacing)
    }

    fun show(): AlertDialog {
        // The keypad beside the amount on a phone held sideways (PadDialog, D-063).
        val p = PadDialog(activity, title)
        message?.let { p.info(TextView(activity, null, 0, R.style.Text_Lekas_Caption).apply { text = it }) }
        p.info(display, matchWrap().apply { topMargin = activity.dp(8); bottomMargin = activity.dp(8) })
        p.pad(keypad.view)
        // The extra button keeps this dialog open: the amount it brings is confirmed with OK.
        if (extra != null) {
            p.neutral(extra.first) { d ->
                // Only what the keypad can hold (9 digits): a longer amount would be cut, not refused.
                extra.second { amount -> if (d.isShowing && amount in 0L..999_999_999L) keypad.preset(amount.toString()) }
            }
        }
        p.negative(activity.getString(R.string.cancel))
        p.positive(activity.getString(R.string.ok)) { d ->
            val v = value(keypad.digits)
            if (v != null) {
                d.dismiss()
                onOk(v)
            }
        }
        val d = p.create()
        d.keys { e -> keypad.onKey(e) }
        val start = when (kind) {
            Kind.MONEY, Kind.WEIGHT -> if (initial > 0L) initial.toString() else ""
            Kind.PIECES -> if (initial >= 1000L && initial % 1000L == 0L) (initial / 1000L).toString() else ""
            Kind.PERCENT -> if (initial > 0L) (initial / 100L).toString() else ""
        }
        keypad.preset(start) // the amount now: OK keeps it, the first key replaces it
        render(keypad.digits)
        d.show()
        d.trackedBy(activity)
        return d
    }
}

/** Amount-or-percent discount with a "no discount" button. */
class DiscountDialog(
    private val activity: Activity,
    private val title: CharSequence,
    private val currency: CurrencySpec,
    current: Discount,
    private val onSet: (Discount) -> Unit,
) {
    private var percent = current is Discount.Percent
    private val display = activity.display()
    private val keypad = Keypad(activity, 9) { render() }
    private val amountTab = Button(activity, null, 0, R.style.Widget_Lekas_Toggle)
    private val percentTab = Button(activity, null, 0, R.style.Widget_Lekas_Toggle)
    private val initialDigits = when (current) {
        is Discount.Amount -> current.minor.toString()
        is Discount.Percent -> (current.bp / 100).toString()
        Discount.None -> ""
    }

    private fun discount(): Discount? {
        val digits = keypad.digits
        if (digits.isEmpty()) return null
        return if (percent) {
            val bp = (digits.toLongOrNull() ?: return null) * 100L
            if (bp <= 0L || bp > 10_000L) null else Discount.Percent(bp.toInt())
        } else {
            val minor = MoneyFormat.keypad(digits, currency) ?: return null
            if (minor <= 0L) null else Discount.Amount(minor)
        }
    }

    private fun render() {
        amountTab.isSelected = !percent
        percentTab.isSelected = percent
        val d = discount()
        display.text = when {
            d is Discount.Percent -> ReceiptLayout.percent(d.bp)
            d is Discount.Amount -> MoneyFormat.format(d.minor, currency)
            percent -> "0%"
            else -> MoneyFormat.format(0L, currency)
        }
        display.replacing(keypad.replacing)
    }

    fun show(): AlertDialog {
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        amountTab.text = activity.getString(R.string.discount_amount)
        percentTab.text = activity.getString(R.string.discount_percent)
        amountTab.setOnClickListener {
            percent = false
            keypad.clear()
        }
        percentTab.setOnClickListener {
            percent = true
            keypad.clear()
        }
        tabs.addView(amountTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(percentTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The keypad beside the amount on a phone held sideways (PadDialog, D-063).
        val p = PadDialog(activity, title)
        p.info(tabs)
        p.info(display, matchWrap().apply { topMargin = activity.dp(8); bottomMargin = activity.dp(8) })
        p.pad(keypad.view)
        p.neutral(activity.getString(R.string.discount_none)) { d ->
            d.dismiss()
            onSet(Discount.None)
        }
        p.negative(activity.getString(R.string.cancel))
        p.positive(activity.getString(R.string.ok)) { d ->
            val disc = discount()
            if (disc != null) {
                d.dismiss()
                onSet(disc)
            }
        }
        val d = p.create()
        d.keys { e -> keypad.onKey(e) }
        keypad.preset(initialDigits) // the discount now: OK keeps it, the first key replaces it
        render()
        d.show()
        d.trackedBy(activity)
        return d
    }
}

fun showHeldBills(
    a: Activity,
    bills: List<CartSession.HeldBill>,
    currency: CurrencySpec,
    onResume: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (bills.isEmpty()) {
        AlertDialog.Builder(a).setTitle(R.string.held_title).setMessage(R.string.held_empty).setPositiveButton(R.string.ok, null).show().trackedBy(a)
        return
    }
    val tz = TimeZone.getDefault()
    val labels = bills.map {
        // Whose bill it is (D-067): a bill parked by one cashier and paid or thrown away by another.
        val name = (it.label ?: DateText.time(it.updatedAt, tz)) + (it.by?.let { by -> " · $by" } ?: "")
        a.getString(R.string.held_row, name, it.lines, MoneyFormat.format(it.total, currency))
    }.toTypedArray<CharSequence>()
    fun confirmDelete(position: Int, then: () -> Unit) {
        AlertDialog.Builder(a)
            .setMessage(R.string.held_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                then()
                onDelete(bills[position].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .trackedBy(a)
    }
    // Deleting was a long press only, which nobody finds (D-049): bills left behind kept the
    // "n held" pill on screen for good (2026-10 review). A tap still resumes at once.
    val d = AlertDialog.Builder(a)
        .setTitle(R.string.held_title)
        .setItems(labels) { _, which -> onResume(bills[which].id) }
        .setNeutralButton(R.string.held_delete_one) { _, _ ->
            AlertDialog.Builder(a)
                .setTitle(R.string.held_delete_which)
                .setItems(labels) { _, which -> confirmDelete(which) {} }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .trackedBy(a)
        }
        .setNegativeButton(R.string.close, null)
        .create()
    d.listView.setOnItemLongClickListener { _, _, position, _ ->
        confirmDelete(position) { d.dismiss() }
        true
    }
    d.show()
    d.trackedBy(a)
}

/**
 * A barcode no product has: register it (then it goes on the bill), or cancel (D-050). [needsManager]:
 * the person signed in may not add products, and is told a manager's PIN comes first (D-063).
 */
fun showUnknownBarcode(a: Activity, code: String, needsManager: Boolean, onAddProduct: () -> Unit): AlertDialog =
    AlertDialog.Builder(a)
        .setTitle(R.string.sell_not_found_title)
        .setMessage(a.getString(if (needsManager) R.string.sell_not_found_manager else R.string.sell_not_found_message, code))
        .setPositiveButton(R.string.sell_add_product) { _, _ -> onAddProduct() }
        .setNegativeButton(R.string.cancel, null)
        .show()
        .trackedBy(a)

/**
 * Forwards scanner/keyboard keys of a dialog without text fields to [handler] (the screen's scan
 * input); an Enter or Tab it does not take never presses a focused button of the dialog.
 */
fun AlertDialog.forwardKeys(handler: (KeyEvent) -> Boolean) {
    keys { e -> handler(e) || DialogKeys.pressesFocused(e.keyCode) }
}

internal fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}
