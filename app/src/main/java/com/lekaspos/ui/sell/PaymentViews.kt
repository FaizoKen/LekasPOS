package com.lekaspos.ui.sell

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.ui.common.FitButton

/**
 * The payment's views as the till builds them (D-064): `dialog_payment` upright, `dialog_payment_wide`
 * held sideways, and the rows of notes and payment methods in them. Kept apart from [PaymentDialog] so
 * SidewaysLayoutTest measures exactly what the cashier sees.
 */
internal class PaymentViews(private val ctx: Context, val wide: Boolean) {

    val root: View = LayoutInflater.from(ctx).inflate(if (wide) R.layout.dialog_payment_wide else R.layout.dialog_payment, null)

    // Step 1: how the customer pays.
    private val choose: View = root.findViewById(R.id.pay_step_choose)
    val cancel: View = root.findViewById(R.id.pay_cancel)
    val totalLabel: TextView = root.findViewById(R.id.pay_total_label)
    val total: TextView = root.findViewById(R.id.pay_total)
    val tenders: TextView = root.findViewById(R.id.pay_tenders)
    val cashHint: TextView = root.findViewById(R.id.pay_cash_hint)
    val complete: View = root.findViewById(R.id.pay_complete)
    val cash: View = root.findViewById(R.id.pay_cash)
    private val quick: LinearLayout = root.findViewById(R.id.pay_quick)
    val others: View = root.findViewById(R.id.pay_others)
    val othersLabel: TextView = root.findViewById(R.id.pay_others_label)
    val methods: LinearLayout = root.findViewById(R.id.pay_methods)
    val split: View = root.findViewById(R.id.pay_split)

    // Step 2: an amount on the keypad.
    private val amountStep: View = root.findViewById(R.id.pay_step_amount)
    val back: View = root.findViewById(R.id.pay_back)
    val due: TextView = root.findViewById(R.id.pay_due)
    val amountLabel: TextView = root.findViewById(R.id.pay_amount_label)
    val amount: TextView = root.findViewById(R.id.pay_amount)
    val change: TextView = root.findViewById(R.id.pay_change)
    val error: TextView = root.findViewById(R.id.pay_error)
    val keypad: FrameLayout = root.findViewById(R.id.pay_keypad)
    val done: View = root.findViewById(R.id.pay_done)
    val part: View = root.findViewById(R.id.pay_part)
    val partMethods: LinearLayout = root.findViewById(R.id.pay_part_methods)

    private val density = ctx.resources.displayMetrics.density

    /** A small phone upright: two methods across, so each keeps its 48dp and its words. */
    private val narrow = !wide && ctx.resources.configuration.screenWidthDp < NARROW_DP

    /** Step 1 ([amount] false) or step 2. */
    fun showStep(amount: Boolean) {
        choose.visibility = if (amount) View.GONE else View.VISIBLE
        amountStep.visibility = if (amount) View.VISIBLE else View.GONE
    }

    /**
     * The cash buttons: the amount due and the notes customers hand over ([amounts], filled: one tap and it
     * is paid), then [other] ("Other amount", outlined) filling the rest of its row. Three to a row upright,
     * two in their column sideways.
     */
    fun notes(amounts: List<CharSequence>, other: CharSequence, onAmount: (Int) -> Unit, onOther: () -> Unit) {
        val labels = amounts + other
        grid(
            quick, labels, if (wide) 2 else 3, fit = true, fillLast = true,
            style = { if (it < amounts.size) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Button_Secondary },
        ) { if (it < amounts.size) onAmount(it) else onOther() }
        // The last button of the last row.
        ((quick.getChildAt(quick.childCount - 1) as ViewGroup).let { it.getChildAt(it.childCount - 1) }).id = R.id.pay_other
    }

    /** The other ways to pay (step 1): each pays the whole rest. Long names (e-wallets) wrap, never cut. */
    fun methods(labels: List<CharSequence>, onClick: (Int) -> Unit) =
        grid(methods, labels, perRow(labels.size, column = 3), fit = false, style = { R.style.Widget_Lekas_Button_Secondary }, onClick = onClick)

    /** Every way to pay one part of a split payment; [cashAt] (the cash button, or -1) is filled. */
    fun partMethods(labels: List<CharSequence>, cashAt: Int, onClick: (Int) -> Unit) = grid(
        partMethods, labels, perRow(labels.size, column = 4), fit = false,
        style = { if (it == cashAt) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Button_Secondary },
        onClick = onClick,
    )

    /** Upright: three across (two on a small phone). Sideways, in a column: one a row up to [column], then two. */
    private fun perRow(count: Int, column: Int): Int = when {
        wide -> if (count <= column) 1 else 2
        narrow -> 2
        else -> 3
    }

    /**
     * [labels] as buttons, [perRow] to a row. A short last row keeps the widths of the rows above, or
     * ([fillLast]) its last button takes the rest of the row.
     */
    private fun grid(
        into: LinearLayout,
        labels: List<CharSequence>,
        perRow: Int,
        fit: Boolean,
        fillLast: Boolean = false,
        style: (Int) -> Int,
        onClick: (Int) -> Unit,
    ) {
        into.removeAllViews()
        if (labels.isEmpty()) return
        val gap = (GAP_DP * density).toInt()
        val n = perRow.coerceIn(1, labels.size)
        for ((r, chunk) in labels.indices.chunked(n).withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for ((i, at) in chunk.withIndex()) {
                // One line that shrinks for an amount ("RM100" wrapped to "RM10" over "0" at large fonts).
                val b = if (fit) FitButton(ctx, null, 0, style(at)) else Button(ctx, null, 0, style(at))
                b.text = labels[at]
                b.minWidth = 0
                if (fit) {
                    val side = (4 * density).toInt()
                    b.setPadding(side, b.paddingTop, side, b.paddingBottom)
                    b.textSize = NOTE_SP
                    b.setTypeface(b.typeface, Typeface.BOLD)
                }
                b.setOnClickListener { onClick(at) }
                val weight = if (fillLast && i == chunk.size - 1) 1f + (n - chunk.size) else 1f
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { if (i > 0) marginStart = gap })
            }
            // A short last row keeps the buttons the same width as the rows above. The fillers are as tall as
            // the row: a 1px one made the row 1px high and squashed its button flat (match_parent buttons take
            // the height of the other children) — a fourth payment method, or Credit, was missing.
            if (!fillLast) repeat(n - chunk.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap }) }
            into.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = gap })
        }
    }

    private companion object {
        const val NARROW_DP = 360
        const val GAP_DP = 6
        const val NOTE_SP = 18f
    }
}
