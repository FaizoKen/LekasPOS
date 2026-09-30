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
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.trackedBy
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
        val keypad = Keypad(a, 9) { d -> display.text = value(d)?.let { if (p.weighed) weightText(it) + " " + p.unit else qty(it, p.unit) } ?: "" }
        val col = column(a)
        col.addView(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply {
            text = a.getString(R.string.inv_stock_now, qty(p.stock, p.unit))
        })
        col.addView(display, lp())
        col.addView(keypad.view, lp())
        val d = AlertDialog.Builder(a).setTitle(title).setView(col)
            .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create()
        d.setOnKeyListener { _, _, e -> keypad.onKey(e) }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = value(keypad.digits)
                if (v != null && (v > 0L || allowZero)) {
                    d.dismiss()
                    onOk(v)
                }
            }
        }
        val start = if (initial <= 0L) "" else if (p.weighed) initial.toString() else (initial / 1000L).toString()
        keypad.set(start)
        d.show()
        return d.trackedBy(a)
    }

    /** Manual stock adjustment: reason, in/out, quantity and a note. */
    fun askAdjust(a: Activity, p: StockProduct, onOk: (AdjustReason, Long, Boolean, String?) -> Unit): AlertDialog {
        val reasons = AdjustReason.values().toList()
        var removing = true
        val col = column(a)
        col.addView(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply {
            text = a.getString(R.string.inv_stock_now, qty(p.stock, p.unit))
        })
        val reason = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, reasons.map { reasonLabel(a, it) })
            minimumHeight = (48 * a.resources.displayMetrics.density).toInt()
        }
        col.addView(reason, lp())
        val tabs = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val out = Button(a, null, 0, R.style.Widget_Lekas_Toggle).apply { text = a.getString(R.string.inv_remove_stock) }
        val inn = Button(a, null, 0, R.style.Widget_Lekas_Toggle).apply { text = a.getString(R.string.inv_add_stock) }
        fun renderDirection() {
            val dir = reasons[reason.selectedItemPosition.coerceAtLeast(0)].delta(1L, removing) < 0L
            out.isSelected = dir
            inn.isSelected = !dir
        }
        out.setOnClickListener {
            removing = true
            renderDirection()
        }
        inn.setOnClickListener {
            removing = false
            renderDirection()
        }
        tabs.addView(out, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(inn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(tabs, lp())
        val display = TextView(a, null, 0, R.style.Text_Lekas_Display).apply {
            gravity = android.view.Gravity.END
            setBackgroundResource(R.drawable.field_bg)
        }
        fun value(digits: String): Long? {
            val n = digits.toLongOrNull() ?: return null
            if (n <= 0L) return null
            return if (p.weighed) n else n * 1000L
        }
        val keypad = Keypad(a, 9) { d -> display.text = value(d)?.let { if (p.weighed) weightText(it) + " " + p.unit else qty(it, p.unit) } ?: "" }
        col.addView(display, lp())
        col.addView(keypad.view, lp())
        val note = EditText(a).apply {
            hint = a.getString(R.string.inv_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        col.addView(note, lp())
        reason.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = renderDirection()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        renderDirection()
        keypad.clear()
        val d = AlertDialog.Builder(a).setTitle(p.name).setView(android.widget.ScrollView(a).apply { addView(col) })
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
        d.setOnKeyListener { _, _, e -> if (note.hasFocus()) false else keypad.onKey(e) }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = value(keypad.digits) ?: return@setOnClickListener
                d.dismiss()
                onOk(reasons[reason.selectedItemPosition.coerceAtLeast(0)], v, removing, note.text.toString().trim().ifEmpty { null })
            }
        }
        d.show()
        return d.trackedBy(a)
    }

    private fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 8
    }
}
