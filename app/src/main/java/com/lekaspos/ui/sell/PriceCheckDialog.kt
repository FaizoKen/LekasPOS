package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.domain.sell.PriceCheck
import com.lekaspos.ui.common.trackedBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Price check (Phase 8): scan or type a barcode or name to see price and stock; the bill is not
 * touched. Keyboard scanners type into the field; serial and camera scans arrive through
 * [lookup] while the dialog is showing.
 */
class PriceCheckDialog(
    private val a: Activity,
    private val scope: CoroutineScope,
    private val check: PriceCheck,
    private val currency: CurrencySpec,
    private val onCamera: (() -> Unit)?,
) {
    private lateinit var dialog: AlertDialog
    private lateinit var field: EditText
    private lateinit var result: TextView

    val isShowing: Boolean get() = ::dialog.isInitialized && dialog.isShowing

    fun show(): AlertDialog {
        val pad = (16 * a.resources.displayMetrics.density).toInt()
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        field = EditText(a).apply {
            hint = a.getString(R.string.price_check_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            setOnEditorActionListener { _, action, event -> onEditorAction(action, event) }
        }
        result = TextView(a, null, 0, R.style.Text_Lekas_Body).apply { setPadding(0, pad / 2, 0, pad / 2) }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        col.addView(field, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(ScrollView(a).apply { addView(result) })
        val b = AlertDialog.Builder(a)
            .setTitle(R.string.price_check_title)
            .setView(col)
            .setPositiveButton(R.string.price_check_go, null)
            .setNegativeButton(R.string.close, null)
        if (onCamera != null) b.setNeutralButton(R.string.price_check_camera, null)
        dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { lookup(field.text.toString()) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { onCamera?.invoke() }
        }
        dialog.show()
        field.requestFocus()
        return dialog.trackedBy(a)
    }

    private fun onEditorAction(action: Int, event: KeyEvent?): Boolean {
        val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
        if (action != EditorInfo.IME_ACTION_SEARCH && !enter) return false
        lookup(field.text.toString())
        return true
    }

    /** Looks up [query] (a scan or typed text) and shows what was found. */
    fun lookup(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        field.setText(q)
        field.setSelection(q.length)
        scope.launch {
            val found = check.lookup(q)
            result.text = if (found.isEmpty()) {
                a.getString(R.string.sell_no_results, q)
            } else {
                found.joinToString("\n\n") { describe(it) }
            }
            field.selectAll() // the next scan replaces the text
        }
    }

    private fun describe(i: PriceCheck.Info): String {
        val lines = ArrayList<String>(5)
        lines.add(i.name)
        lines.add(
            if (i.sellMode == SellMode.OPEN_PRICE) {
                a.getString(R.string.tile_open_price)
            } else {
                a.getString(R.string.price_check_per, money(i.price), i.unit)
            },
        )
        for (p in i.packs) {
            lines.add(a.getString(R.string.price_check_pack, MoneyFormat.formatQty(p.qty), money(p.price)))
        }
        val stock = i.stock
        lines.add(
            if (stock == null) {
                a.getString(R.string.price_check_no_stock)
            } else {
                a.getString(R.string.price_check_stock, MoneyFormat.formatQty(stock), i.unit)
            },
        )
        if (!i.active) lines.add(a.getString(R.string.price_check_hidden))
        return lines.joinToString("\n")
    }

    private fun money(v: Long) = MoneyFormat.format(v, currency)
}
