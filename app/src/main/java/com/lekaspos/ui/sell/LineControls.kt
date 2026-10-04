package com.lekaspos.ui.sell

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The selected bill line's buttons on one line under the item (the owner's ask, 2026-10-04): Remove,
 * −, quantity, +, More. Remove sits at the far end from + with a wider gap, so fast taps on + never
 * land on it. When they do not fit — a narrow bill pane (a phone turned, two panes), a small phone, a
 * large font — they take two lines as before (D-049): − quantity + over Remove and More, so no button
 * is ever cut. The quantity takes the room left over.
 *
 * Children in this order: remove, minus, quantity, plus, more; gone ones are left out. Left-to-right
 * only (English and Malay), like [TopBarLayout].
 */
class LineControls(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gap = (GAP_DP * density).toInt()
    private val removeGap = (REMOVE_GAP_DP * density).toInt()
    private val rowGap = (ROW_GAP_DP * density).toInt()
    private val minQty = (MIN_QTY_DP * density).toInt()

    /** All on one line (else two: − quantity + over Remove and More). */
    var oneLine = true
        private set

    private var firstRowHeight = 0

    private fun shown(vararg at: Int): List<View> = at.map { getChildAt(it) }.filter { it != null && it.visibility != View.GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val all = shown(REMOVE, MINUS, QTY, PLUS, MORE)
        if (all.isEmpty()) {
            setMeasuredDimension(width, paddingTop + paddingBottom)
            return
        }
        // Each button's own width first: its label at full size.
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val natural = HashMap<View, Int>()
        for (c in all) {
            c.measure(free, heightSpec(c))
            natural[c] = if (c === getChildAt(QTY)) maxOf(c.measuredWidth, minQty) else c.measuredWidth
        }
        val need = all.sumOf { natural.getValue(it) } + gaps(all)
        oneLine = need <= inner
        val height = if (oneLine) {
            val qty = getChildAt(QTY).takeIf { it.visibility != View.GONE }
            val extra = inner - need
            // The room left goes to the quantity; without one (a label-price line), to Remove and More.
            val widths = HashMap(natural)
            if (qty != null) {
                widths[qty] = widths.getValue(qty) + extra
            } else {
                val ends = shown(REMOVE, MORE).ifEmpty { all }
                for ((i, c) in ends.withIndex()) widths[c] = widths.getValue(c) + extra / ends.size + if (i == 0) extra % ends.size else 0
            }
            measureRow(all, widths)
        } else {
            val top = shown(MINUS, QTY, PLUS)
            val bottom = shown(REMOVE, MORE)
            firstRowHeight = measureStretched(top, inner, stretch = getChildAt(QTY))
            val second = measureStretched(bottom, inner, stretch = null)
            firstRowHeight + if (top.isNotEmpty() && bottom.isNotEmpty()) rowGap + second else second
        }
        setMeasuredDimension(width, paddingTop + height + paddingBottom)
    }

    /** One line of [row] at [widths], all as tall as the tallest; returns that height. */
    private fun measureRow(row: List<View>, widths: Map<View, Int>): Int {
        val h = row.maxOf { it.measuredHeight }
        for (c in row) c.measure(exactly(widths.getValue(c)), exactly(h))
        return h
    }

    /** One line of [row] filling [inner]: [stretch] takes the room left, or all share it alike. */
    private fun measureStretched(row: List<View>, inner: Int, stretch: View?): Int {
        if (row.isEmpty()) return 0
        val room = inner - gaps(row)
        val widths = HashMap<View, Int>()
        if (stretch != null && stretch in row) {
            val others = row.filter { it !== stretch }
            for (c in others) widths[c] = c.measuredWidth
            widths[stretch] = (room - others.sumOf { it.measuredWidth }).coerceAtLeast(0)
        } else {
            for ((i, c) in row.withIndex()) widths[c] = room / row.size + if (i == 0) room % row.size else 0
        }
        return measureRow(row, widths)
    }

    /** The gaps between [row]'s buttons: a wider one after Remove. */
    private fun gaps(row: List<View>): Int {
        var sum = 0
        for (i in 0 until row.size - 1) sum += if (row[i] === getChildAt(REMOVE)) removeGap else gap
        return sum
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft
        val top = paddingTop
        if (oneLine) {
            place(shown(REMOVE, MINUS, QTY, PLUS, MORE), left, top)
        } else {
            val first = shown(MINUS, QTY, PLUS)
            place(first, left, top)
            place(shown(REMOVE, MORE), left, top + if (first.isEmpty()) 0 else firstRowHeight + rowGap)
        }
    }

    private fun place(row: List<View>, left: Int, top: Int) {
        var x = left
        for ((i, c) in row.withIndex()) {
            c.layout(x, top, x + c.measuredWidth, top + c.measuredHeight)
            x += c.measuredWidth
            if (i < row.size - 1) x += if (c === getChildAt(REMOVE)) removeGap else gap
        }
    }

    /** Pressed at once, like the bill list it sits in (TapList, D-063). */
    override fun shouldDelayChildPressedState(): Boolean = false

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)

    private fun heightSpec(c: View): Int =
        getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0, c.layoutParams.height)

    private companion object {
        const val REMOVE = 0
        const val MINUS = 1
        const val QTY = 2
        const val PLUS = 3
        const val MORE = 4

        const val GAP_DP = 4
        const val REMOVE_GAP_DP = 10
        const val ROW_GAP_DP = 6
        const val MIN_QTY_DP = 56
    }
}
