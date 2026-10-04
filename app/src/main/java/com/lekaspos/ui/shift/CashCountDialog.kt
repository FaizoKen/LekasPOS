package com.lekaspos.ui.shift

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.shift.CashCount
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.trackedBy

/**
 * The drawer counted note by note and coin by coin (`:core` CashCount): the cashier types how many
 * of each there are, the total adds up while they type, and OK hands back the total and the count.
 * Adding up a drawer in one's head was where a closing count went wrong.
 */
class CashCountDialog(
    private val activity: Activity,
    private val currency: CurrencySpec,
    /** What was counted the last time this dialog was used for this count (shown again). */
    private val start: Map<Long, Long>,
    private val onDone: (total: Long, pieces: Map<Long, Long>) -> Unit,
) {
    private val values = CashCount.denominations(currency.code)
    private val fields = ArrayList<EditText>(values.size)
    private val amounts = ArrayList<TextView>(values.size)
    private lateinit var totalView: TextView

    fun show(): AlertDialog {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        totalView = TextView(activity, null, 0, R.style.Text_Lekas_Display).apply { gravity = Gravity.END }
        col.addView(totalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        }
        for ((i, v) in values.withIndex()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                isBaselineAligned = false
            }
            val label = TextView(activity, null, 0, R.style.Text_Lekas_Body).apply {
                text = activity.getString(R.string.cash_count_row, name(v))
            }
            val field = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(MAX_DIGITS))
                gravity = Gravity.END
                hint = "0"
                setSingleLine(true)
                imeOptions = if (i == values.size - 1) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                contentDescription = activity.getString(R.string.cash_count_field, name(v))
                start[v]?.takeIf { it > 0L }?.let { setText(it.toString()) }
                addTextChangedListener(watcher)
            }
            val amount = TextView(activity, null, 0, R.style.Text_Lekas_Body).apply { gravity = Gravity.END }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(field, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(amount, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            fields.add(field)
            amounts.add(amount)
        }
        render()
        val d = AlertDialog.Builder(activity)
            .setTitle(R.string.cash_count_title)
            .setView(Dialogs.scrolling(col))
            .setPositiveButton(R.string.ok) { _, _ ->
                val pieces = pieces()
                onDone(CashCount.total(pieces), pieces)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // A touch beside it must not throw a half-counted drawer away.
        d.setCanceledOnTouchOutside(false)
        if (Dialogs.canShow(activity)) d.show()
        d.trackedBy(activity)
        fields.firstOrNull()?.requestFocus()
        return d
    }

    /** Denomination → how many, as typed (empty = none). */
    private fun pieces(): Map<Long, Long> {
        val out = LinkedHashMap<Long, Long>(values.size)
        for ((i, v) in values.withIndex()) out[v] = fields[i].text.toString().trim().toLongOrNull() ?: 0L
        return out
    }

    private fun render() {
        val pieces = pieces()
        for ((i, v) in values.withIndex()) {
            val n = pieces[v] ?: 0L
            amounts[i].text = if (n > 0L) MoneyFormat.format(CashCount.total(mapOf(v to n)), currency, withSymbol = false) else ""
        }
        totalView.text = activity.getString(R.string.cash_count_total, MoneyFormat.format(CashCount.total(pieces), currency))
    }

    /** "RM100" for a note, "50 sen" for a coin. */
    private fun name(v: Long): String = label(activity, currency, v)

    companion object {
        /** Up to 99,999 of one note or coin: far more than any drawer holds, far below an overflow. */
        private const val MAX_DIGITS = 5

        /** A denomination as people say it: whole units with the symbol ("RM100"), coins in their small unit ("50 sen"). */
        fun label(activity: Activity, currency: CurrencySpec, v: Long): String =
            if (v >= currency.scale && v % currency.scale == 0L) {
                MoneyFormat.format(v, currency).substringBefore(currency.decimalSeparator)
            } else {
                activity.getString(R.string.cash_count_coin, v)
            }
    }
}
