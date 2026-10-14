package com.lekaspos.ui.inventory

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.app.AppGraph
import com.lekaspos.core.inventory.AdjustReason
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.product.ProductDao
import com.lekaspos.domain.sell.BarcodeLookup
import com.lekaspos.domain.sell.Resolution
import com.lekaspos.ui.common.FitButton
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.PadDialog
import com.lekaspos.ui.common.keys
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.replacing
import com.lekaspos.ui.sell.visible
import com.lekaspos.ui.sell.weightText

/** A product chosen for stock work (by scan, search or tap). */
data class StockProduct(
    val id: Long,
    val name: String,
    val unit: String,
    val sellMode: Int,
    val cost: Long,
    val stock: Long,
    /** Base milli-units the scanned barcode stands for (a carton: 24 000), 0 = not scanned. */
    val scannedQty: Long = 0L,
) {
    val weighed: Boolean get() = sellMode == SellMode.WEIGHT
}

object InventoryUi {

    /** Resolves a scanned code to a product (pack barcodes carry their size; scale labels their weight). */
    suspend fun resolve(graph: AppGraph, code: String): StockProduct? {
        val templates = graph.settings.store.value.templates()
        return graph.db().read { r ->
            val (productId, qty) = when (val res = BarcodeLookup.resolve(r, code, templates)) {
                is Resolution.NotFound -> return@read null
                is Resolution.Plain -> res.hit.product.id to res.hit.packQty
                is Resolution.Scale -> res.product.id to (res.label.weightMilli ?: 1000L)
            }
            load(r, productId)?.copy(scannedQty = qty)
        }
    }

    suspend fun product(graph: AppGraph, id: Long): StockProduct? = graph.db().read { load(it, id) }

    private fun load(r: android.database.sqlite.SQLiteDatabase, id: Long): StockProduct? {
        val p = ProductDao.get(r, id) ?: return null
        return StockProduct(p.id, p.name, p.unit, p.sellMode, p.cost, com.lekaspos.data.stock.StockDao.level(r, p.id))
    }

    fun qty(milli: Long, unit: String?): String = MoneyFormat.formatQty(milli) + (unit?.let { " $it" } ?: "")

    fun signedQty(milli: Long, unit: String?): String = (if (milli > 0L) "+" else "") + qty(milli, unit)

    fun kindLabel(ctx: Context, kind: Int): String = ctx.getString(
        when (kind) {
            MovementKind.RECEIVE -> R.string.move_receive
            MovementKind.WASTE -> R.string.move_waste
            MovementKind.RETURN_TO_SUPPLIER -> R.string.move_return
            MovementKind.TRANSFER_IN -> R.string.move_transfer_in
            MovementKind.TRANSFER_OUT -> R.string.move_transfer_out
            MovementKind.OPENING -> R.string.move_opening
            else -> R.string.move_adjust
        },
    )

    fun reasonLabel(ctx: Context, r: AdjustReason): String = ctx.getString(
        when (r) {
            AdjustReason.DAMAGED -> R.string.reason_damaged
            AdjustReason.EXPIRED -> R.string.reason_expired
            AdjustReason.LOST -> R.string.reason_lost
            AdjustReason.THEFT -> R.string.reason_theft
            AdjustReason.OWN_USE -> R.string.reason_own_use
            AdjustReason.RETURNED -> R.string.reason_returned
            AdjustReason.FOUND -> R.string.reason_found
            AdjustReason.CORRECTION -> R.string.reason_correction
            AdjustReason.OTHER -> R.string.reason_other
        },
    )

    /** "Written off · Damaged: box crushed" for a movement row. */
    fun movementText(ctx: Context, kind: Int, reason: String?): String {
        val (r, note) = AdjustReason.decode(reason)
        val parts = ArrayList<String>(3)
        parts.add(kindLabel(ctx, kind))
        if (r != null && r.kind != MovementKind.RETURN_TO_SUPPLIER) parts.add(reasonLabel(ctx, r))
        val detail = listOfNotNull(parts.joinToString(" · "), note).joinToString(": ")
        return detail
    }

    /**
     * Quantity entry for stock work: whole pieces, or kg with three decimals for weighed goods.
     * [allowZero] for counts ("none left").
     */
    fun askQty(a: Activity, title: CharSequence, p: StockProduct, initial: Long, allowZero: Boolean, onOk: (Long) -> Unit): AlertDialog {
        val display = TextView(a, null, 0, R.style.Text_Lekas_Display).apply {
            gravity = android.view.Gravity.END
            setBackgroundResource(R.drawable.field_bg)
            val pad = (12 * a.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun value(digits: String): Long? {
            if (digits.isEmpty()) return if (allowZero) 0L else null
            val n = digits.toLongOrNull() ?: return null
            return if (p.weighed) n else n * 1000L
        }
        lateinit var keypad: Keypad
        keypad = Keypad(a, 9) { d ->
            display.text = value(d)?.let { if (p.weighed) weightText(it) + " " + p.unit else qty(it, p.unit) } ?: ""
            display.replacing(keypad.replacing)
        }
        // The keypad beside the quantity on a phone held sideways (PadDialog, D-063).
        val pd = PadDialog(a, title)
        pd.info(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply { text = a.getString(R.string.inv_stock_now, qty(p.stock, p.unit)) })
        pd.info(display, lp())
        pd.pad(keypad.view)
        pd.negative(a.getString(R.string.cancel))
        pd.positive(a.getString(R.string.ok)) { d ->
            val v = value(keypad.digits)
            if (v != null && (v > 0L || allowZero)) {
                d.dismiss()
                onOk(v)
            }
        }
        val d = pd.create()
        d.keys { e -> keypad.onKey(e) }
        val start = if (initial <= 0L) "" else if (p.weighed) initial.toString() else (initial / 1000L).toString()
        keypad.preset(start) // the quantity now (a first count, a delivery line): the first key replaces it
        d.show()
        return d.trackedBy(a)
    }
    /**
     * "Change stock" (D-080): **Count** (first) — how many there are now, shelf and storeroom together, and the app
     * takes that number; or **Remove** / **Add** a quantity for a reason (damaged, expired, found…) with a note.
     * A new manager wanted to "just edit the quantity" of a product: Count is that, and it keeps the reason on record.
     */
    fun askChange(a: Activity, p: StockProduct, onOk: (StockChange) -> Unit): AlertDialog {
        val c = changeDialog(a, p, onOk)
        val d = c.pad.create()
        d.keys { e -> if (c.note.hasFocus()) false else c.keypad.onKey(e) }
        d.show()
        return d.trackedBy(a)
    }

    /** The parts of "Change stock" a test measures: the dialog, its three choices, the keypad and the note. */
    class ChangeDialog(val pad: PadDialog, val modes: List<Button>, val keypad: Keypad, val note: EditText)

    /**
     * Builds [askChange]'s dialog. Short enough for the keypad's height held sideways (D-063): the app's number is
     * on the line under the amount (with the difference once a count is typed), the help is one caption.
     */
    fun changeDialog(a: Context, p: StockProduct, onOk: (StockChange) -> Unit): ChangeDialog {
        var mode = MODE_COUNT
        var reasons = emptyList<AdjustReason>()
        val density = a.resources.displayMetrics.density
        // The keypad beside the rest on a phone held sideways (PadDialog, D-063).
        val pd = PadDialog(a, p.name)
        val tabs = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        // Three side by side on a 5-inch phone: the label shrinks to one line ("Keluarkan" broke as "Keluark/an").
        fun tab(label: Int) = FitButton(a, null, 0, R.style.Widget_Lekas_Toggle).also {
            it.text = a.getString(label)
            val pad = (6 * density).toInt()
            it.setPadding(pad, it.paddingTop, pad, it.paddingBottom)
            it.minHeight = (48 * density).toInt() // 48dp, not 56: the dialog fits beside a sideways keypad
            it.minimumHeight = it.minHeight
            tabs.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val count = tab(R.string.inv_count_stock)
        val out = tab(R.string.inv_remove_stock)
        val inn = tab(R.string.inv_add_stock)
        pd.info(tabs)
        val help = TextView(a, null, 0, R.style.Text_Lekas_Caption)
        pd.info(help, lp())
        val reason = Spinner(a).apply { minimumHeight = (48 * density).toInt() }
        pd.info(reason, lp())
        // The amount with, on its left, what the app has (or how far a count is from it): one box, so everything shows
        // beside the keypad of a tablet held sideways — a line of its own under the amount fell out of sight there.
        val amount = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.field_bg)
            val pad = (12 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val status = TextView(a, null, 0, R.style.Text_Lekas_Caption)
        val display = TextView(a, null, 0, R.style.Text_Lekas_Display).apply { gravity = android.view.Gravity.END }
        amount.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        amount.addView(
            display,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (8 * density).toInt()
            },
        )
        pd.info(amount, lp())
        val note = EditText(a).apply {
            hint = a.getString(R.string.inv_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        pd.info(note, lp())

        /** The quantity typed: 0 is a count ("none left"), never an amount to remove or add. */
        fun value(digits: String): Long? {
            val n = digits.toLongOrNull() ?: return null
            if (n < 0L || (n == 0L && mode != MODE_COUNT)) return null
            return if (p.weighed) n else n * 1000L
        }
        fun shown(v: Long) = if (p.weighed) weightText(v) + " " + p.unit else qty(v, p.unit)
        val appHas = a.getString(R.string.inv_app_has, qty(p.stock, p.unit))
        lateinit var keypad: Keypad
        keypad = Keypad(a, 9) { d ->
            val v = value(d)
            display.text = v?.let { shown(it) } ?: ""
            display.replacing(keypad.replacing)
            // A count typed: how far it is from the app. Otherwise (the app's own number shown, or Remove / Add) what the
            // app has.
            val change = if (mode == MODE_COUNT && !keypad.replacing) v?.minus(p.stock) else null
            status.text = when {
                change == null -> appHas
                change < 0L -> a.getString(R.string.inv_diff_less, qty(-change, p.unit))
                change > 0L -> a.getString(R.string.inv_diff_more, qty(change, p.unit))
                else -> a.getString(R.string.inv_diff_same)
            }
        }
        pd.pad(keypad.view)

        fun select(m: Int) {
            mode = m
            count.isSelected = m == MODE_COUNT
            out.isSelected = m == MODE_REMOVE
            inn.isSelected = m == MODE_ADD
            help.setText(
                when (m) {
                    MODE_COUNT -> R.string.inv_count_help
                    MODE_REMOVE -> R.string.inv_remove_help
                    else -> R.string.inv_add_help
                },
            )
            val counting = m == MODE_COUNT
            reason.visible(!counting)
            note.visible(!counting)
            if (counting) {
                // The number the app has: OK keeps it ("checked, it is right"), the first key typed replaces it.
                keypad.preset(if (p.stock <= 0L) "" else if (p.weighed) p.stock.toString() else (p.stock / 1000L).toString())
            } else {
                reasons = AdjustReason.choices(removing = m == MODE_REMOVE)
                reason.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, reasons.map { reasonLabel(a, it) })
                keypad.clear()
            }
        }
        count.setOnClickListener { select(MODE_COUNT) }
        out.setOnClickListener { select(MODE_REMOVE) }
        inn.setOnClickListener { select(MODE_ADD) }
        select(MODE_COUNT)
        pd.negative(a.getString(R.string.cancel))
        pd.positive(a.getString(R.string.save)) { d ->
            val v = value(keypad.digits) ?: return@positive
            d.dismiss()
            if (mode == MODE_COUNT) {
                onOk(StockChange.Count(v))
            } else {
                val r = reasons.getOrNull(reason.selectedItemPosition) ?: reasons.first()
                onOk(StockChange.Move(r, v, mode == MODE_REMOVE, note.text.toString().trim().ifEmpty { null }))
            }
        }
        return ChangeDialog(pd, listOf(count, out, inn), keypad, note)
    }

    private const val MODE_COUNT = 0
    private const val MODE_REMOVE = 1
    private const val MODE_ADD = 2

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 8
    }
}

/** What "Change stock" records (D-080). */
sealed class StockChange {
    /** How many there are now: it becomes the stock. */
    data class Count(val qty: Long) : StockChange()

    /** [qty] taken off ([removing]) or added, for [reason]. */
    data class Move(val reason: AdjustReason, val qty: Long, val removing: Boolean, val note: String?) : StockChange()
}
