package com.lekaspos.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Children side by side, wrapping onto the next line when the width is used up (the colour swatches,
 * D-066): every phone width and font size shows them all, nothing to scroll sideways. Children are
 * measured unbounded within the width; [gap] is the space around each.
 */
class WrapRow @JvmOverloads constructor(context: Context, private val gap: Int = 0) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            x += c.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            lineHeight = maxOf(lineHeight, c.measuredHeight)
        }
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) widest else maxWidth
        setMeasuredDimension(width + paddingLeft + paddingRight, y + lineHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            val left = if (rtl) r - l - paddingRight - x - c.measuredWidth else paddingLeft + x
            c.layout(left, paddingTop + y, left + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            lineHeight = maxOf(lineHeight, c.measuredHeight)
        }
    }
}
