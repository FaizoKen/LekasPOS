package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.core.time.DateText
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.trackedBy
import java.util.TimeZone

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(8), dp(20), 0)
}

private fun Activity.display(): TextView = TextView(this, null, 0, R.style.Text_Lekas_Display).apply {
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    setPadding(dp(12), dp(8), dp(12), dp(8))
    setBackgroundResource(R.drawable.field_bg)
}

private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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
    private val onOk: (Long) -> Unit,
) {
    enum class Kind { MONEY, PIECES, WEIGHT, PERCENT }

    private val display = activity.display()
    private val keypad = Keypad(activity, if (kind == Kind.PERCENT) 3 else 9) { render(it) }

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
    }

    fun show(): AlertDialog {
        val col = activity.column()
        message?.let {
            col.addView(TextView(activity, null, 0, R.style.Text_Lekas_Caption).apply { text = it }, matchWrap())
        }
        col.addView(display, matchWrap().apply { topMargin = activity.dp(8); bottomMargin = activity.dp(8) })
        col.addView(keypad.view, matchWrap())
        val d = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(col)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnKeyListener { _, _, e -> keypad.onKey(e) }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = value(keypad.digits)
                if (v != null) {
                    d.dismiss()
                    onOk(v)
                }
            }
        }
        val start = when (kind) {
            Kind.MONEY, Kind.WEIGHT -> if (initial > 0L) initial.toString() else ""
            Kind.PIECES -> if (initial >= 1000L && initial % 1000L == 0L) (initial / 1000L).toString() else ""
            Kind.PERCENT -> if (initial > 0L) (initial / 100L).toString() else ""
        }
        keypad.set(start)
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
    }

    fun show(): AlertDialog {
        val col = activity.column()
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
        col.addView(tabs, matchWrap())
        col.addView(display, matchWrap().apply { topMargin = activity.dp(8); bottomMargin = activity.dp(8) })
        col.addView(keypad.view, matchWrap())
        val d = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(col)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.discount_none) { _, _ -> onSet(Discount.None) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnKeyListener { _, _, e -> keypad.onKey(e) }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val disc = discount()
                if (disc != null) {
                    d.dismiss()
                    onSet(disc)
                }
            }
        }
        keypad.set(initialDigits)
        render()
        d.show()
        d.trackedBy(activity)
        return d
    }
}

/** Actions offered for one line of the bill. */
interface LineActions {
    fun changeQty(item: CartItem, qty: Long)
    fun enterQty(item: CartItem)
    fun discount(item: CartItem)
    fun price(item: CartItem)
    fun remove(item: CartItem)
}

fun showLineDialog(a: Activity, item: CartItem, amount: Long, currency: CurrencySpec, actions: LineActions): AlertDialog {
    val col = a.column()
    val weighed = item.sellMode == SellMode.WEIGHT || item.fixedGross != null
    val price = MoneyFormat.format(item.unitPrice, currency)
    col.addView(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply {
        text = if (weighed && item.unit != null) "$price / ${item.unit}" else a.getString(R.string.line_each, price)
    }, matchWrap())
    col.addView(TextView(a, null, 0, R.style.Text_Lekas_Display).apply {
        gravity = Gravity.CENTER
        text = MoneyFormat.format(amount, currency)
    }, matchWrap().apply { topMargin = a.dp(4) })

    lateinit var dialog: AlertDialog
    fun button(label: Int, style: Int, onClick: () -> Unit) = Button(a, null, 0, style).apply {
        text = a.getString(label)
        setOnClickListener {
            dialog.dismiss()
            onClick()
        }
    }

    if (!weighed) {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus = button(R.string.line_minus, R.style.Widget_Lekas_Button_Secondary) { actions.changeQty(item, item.qty - 1000L) }
        val plus = button(R.string.line_plus, R.style.Widget_Lekas_Button_Secondary) { actions.changeQty(item, item.qty + 1000L) }
        val qty = Button(a, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
            text = MoneyFormat.formatQty(item.qty)
            textSize = 22f
            setOnClickListener {
                dialog.dismiss()
                actions.enterQty(item)
            }
        }
        row.addView(minus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(qty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginStart = a.dp(8); marginEnd = a.dp(8) })
        row.addView(plus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row, matchWrap().apply { topMargin = a.dp(8) })
    } else if (item.fixedGross == null) {
        col.addView(button(R.string.line_weight, R.style.Widget_Lekas_Button_Secondary) { actions.enterQty(item) }, matchWrap().apply { topMargin = a.dp(8) })
    }
    val row2 = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false // a wrapped label (Malay is longer) keeps both buttons level
    }
    val half = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) }
    row2.addView(button(R.string.line_discount, R.style.Widget_Lekas_Button_Secondary) { actions.discount(item) }, half())
    row2.addView(button(R.string.line_price, R.style.Widget_Lekas_Button_Secondary) { actions.price(item) }, half().apply { marginStart = a.dp(8) })
    col.addView(row2, matchWrap().apply { topMargin = a.dp(8) })
    col.addView(button(R.string.line_remove, R.style.Widget_Lekas_Button_Danger) { actions.remove(item) }, matchWrap().apply { topMargin = a.dp(8) })

    dialog = AlertDialog.Builder(a).setTitle(item.name).setView(col).setNegativeButton(R.string.close, null).create()
    dialog.show()
    return dialog.trackedBy(a)
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
        val name = it.label ?: DateText.time(it.updatedAt, tz)
        a.getString(R.string.held_row, name, it.lines, MoneyFormat.format(it.total, currency))
    }.toTypedArray<CharSequence>()
    val d = AlertDialog.Builder(a)
        .setTitle(R.string.held_title)
        .setItems(labels) { _, which -> onResume(bills[which].id) }
        .setNegativeButton(R.string.close, null)
        .create()
    d.listView.setOnItemLongClickListener { _, _, position, _ ->
        AlertDialog.Builder(a)
            .setMessage(R.string.held_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                d.dismiss()
                onDelete(bills[position].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .trackedBy(a)
        true
    }
    d.show()
    d.trackedBy(a)
}

fun showUnknownBarcode(a: Activity, code: String, onAddProduct: () -> Unit, onSellOther: () -> Unit): AlertDialog =
    AlertDialog.Builder(a)
        .setTitle(R.string.sell_not_found_title)
        .setMessage(a.getString(R.string.sell_not_found_message, code))
        .setPositiveButton(R.string.sell_add_product) { _, _ -> onAddProduct() }
        .setNeutralButton(R.string.sell_sell_other) { _, _ -> onSellOther() }
        .setNegativeButton(R.string.cancel, null)
        .show()
        .trackedBy(a)

/** A one-off item that is not in the catalogue: a name and a price. */
fun showOtherItem(a: Activity, currency: CurrencySpec, onAdd: (String, Long) -> Unit): AlertDialog {
    val col = a.column()
    val name = EditText(a).apply {
        hint = a.getString(R.string.other_item_name)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(true)
    }
    val display = a.display()
    val keypad = Keypad(a, 9) { display.text = MoneyFormat.format(MoneyFormat.keypad(it, currency) ?: 0L, currency) }
    col.addView(name, matchWrap())
    col.addView(display, matchWrap().apply { topMargin = a.dp(8); bottomMargin = a.dp(8) })
    col.addView(keypad.view, matchWrap())
    keypad.clear()
    val d = AlertDialog.Builder(a)
        .setTitle(R.string.other_item_title)
        .setView(col)
        .setPositiveButton(R.string.ok, null)
        .setNegativeButton(R.string.cancel, null)
        .create()
    d.setOnKeyListener { _, _, e -> if (name.hasFocus()) false else keypad.onKey(e) }
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val price = MoneyFormat.keypad(keypad.digits, currency) ?: 0L
            val label = name.text.toString().trim().ifEmpty { a.getString(R.string.other_item_default) }
            if (price > 0L) {
                d.dismiss()
                onAdd(label, price)
            }
        }
    }
    d.show()
    return d.trackedBy(a)
}

/** Forwards scanner/keyboard keys of a dialog without text fields to [handler]. */
fun AlertDialog.forwardKeys(handler: (KeyEvent) -> Boolean) {
    setOnKeyListener { _, _, e -> handler(e) }
}

internal fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}
