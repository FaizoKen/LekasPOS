package com.lekaspos.ui.sell

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The selling screen's top bar: the store name on the left, the Menu button always at the top
 * right, and between them the pills (who is selling, held bills, backup, printer).
 *
 * In a plain row, four pills were wider than a small phone: they pushed the Menu button off the
 * screen (2026-10 review). Here the pills that do not fit beside the name move to a line below
 * instead, so every pill stays readable and Menu never moves.
 *
 * First child = the name, last child = the Menu button, the others = pills. Left-to-right only
 * (English and Malay).
 */
class TopBarLayout(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gap = (GAP_DP * density).toInt()
    private val minTitle = (MIN_TITLE_DP * density).toInt()
    private val rowHeight = (ROW_DP * density).toInt()

    /** How many of the visible pills stay on the first line. */
    private var onFirstLine = 0
    private var firstLineHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val n = childCount
        onFirstLine = 0
        firstLineHeight = rowHeight
        if (n < 2) {
            setMeasuredDimension(width, paddingTop + rowHeight + paddingBottom)
            return
        }
        val atMost = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST)
        val title = getChildAt(0)
        val menu = getChildAt(n - 1)
        menu.measure(atMost, heightSpec(menu))

        // First line: the pills that fit between a readable name and the Menu button, in order.
        val room = inner - menu.measuredWidth - minTitle
        var used = 0
        var fits = true
        for (i in 1 until n - 1) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(atMost, heightSpec(c))
            if (fits && used + c.measuredWidth + gap <= room) {
                used += c.measuredWidth + gap
                onFirstLine++
                firstLineHeight = maxOf(firstLineHeight, c.measuredHeight)
            } else {
                fits = false
            }
        }
        val titleWidth = (inner - menu.measuredWidth - used).coerceAtLeast(0)
        title.measure(MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.EXACTLY), heightSpec(title))
        firstLineHeight = maxOf(firstLineHeight, title.measuredHeight, menu.measuredHeight)

        // The other pills flow below, as many lines as they need.
        var below = 0
        var x = 0
        var lineHeight = 0
        var seen = 0
        for (i in 1 until n - 1) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (++seen <= onFirstLine) continue
            if (x > 0 && x + c.measuredWidth > inner) {
                below += lineHeight + gap
                x = 0
                lineHeight = 0
            }
            x += c.measuredWidth + gap
            lineHeight = maxOf(lineHeight, c.measuredHeight)
        }
        if (lineHeight > 0) below += lineHeight + gap
        setMeasuredDimension(width, paddingTop + firstLineHeight + below + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = childCount
        if (n < 2) return
        val left = paddingLeft
        val right = r - l - paddingRight
        val top = paddingTop
        val title = getChildAt(0)
        val menu = getChildAt(n - 1)
        place(menu, right - menu.measuredWidth, top, firstLineHeight)
        place(title, left, top, firstLineHeight)
        var x = left + title.measuredWidth // the name takes what the first line's pills leave
        var belowX = left
        var belowY = top + firstLineHeight
        var lineHeight = 0
        var seen = 0
        for (i in 1 until n - 1) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (++seen <= onFirstLine) {
                x += gap
                place(c, x, top, firstLineHeight)
                x += c.measuredWidth
                continue
            }
            if (belowX > left && belowX + c.measuredWidth > right) {
                belowY += lineHeight + gap
                belowX = left
                lineHeight = 0
            }
            place(c, belowX, belowY, c.measuredHeight)
            belowX += c.measuredWidth + gap
            lineHeight = maxOf(lineHeight, c.measuredHeight)
        }
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    /** Lays [c] out at [x], centred vertically in a line of [height] starting at [top]. */
    private fun place(c: View, x: Int, top: Int, height: Int) {
        val y = top + (height - c.measuredHeight) / 2
        c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
    }

    private fun heightSpec(c: View): Int =
        getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0, c.layoutParams.height)

    private companion object {
        const val GAP_DP = 4
        const val ROW_DP = 56

        /** Room always left for the store name on the first line. */
        const val MIN_TITLE_DP = 96
    }
}
