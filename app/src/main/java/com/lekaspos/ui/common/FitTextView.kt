package com.lekaspos.ui.common

import android.content.Context
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One line of text that shrinks to fit its width instead of wrapping, being cut or ellipsized: the
 * amounts a cashier must read whole (the bill total, the change) at large font sizes and in narrow
 * panes. The size from XML or setTextSize is the largest it shows; it goes down to [MIN_SCALE] of that,
 * and back up when the text or the width changes. Always one line (setSingleLine), never ellipsized.
 * Platform autosize needs API 26 (minSdk is 21).
 */
class FitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
    defStyleRes: Int = 0,
) : TextView(context, attrs, defStyleAttr, defStyleRes) {

    // Nullable: TextView's constructor calls the overrides below before this is set.
    private val fit: TextFit? = TextFit(this)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fit?.beforeMeasure(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        fit?.sizeSet()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        fit?.textChanged()
    }

    override fun requestLayout() {
        if (fit?.fitting != true) super.requestLayout()
    }
}

/** A button whose one-line label shrinks to fit, like [FitTextView] (amounts, words at large fonts). */
class FitButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
    defStyleRes: Int = 0,
) : Button(context, attrs, defStyleAttr, defStyleRes) {

    private val fit: TextFit? = TextFit(this)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fit?.beforeMeasure(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        fit?.sizeSet()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        fit?.textChanged()
    }

    override fun requestLayout() {
        if (fit?.fitting != true) super.requestLayout()
    }
}

/** The shrinking shared by [FitTextView] and [FitButton]. */
internal class TextFit(private val view: TextView) {

    /** The largest size (px): from XML, or set later with setTextSize. */
    private var base = view.textSize

    /**
     * True while [beforeMeasure] changes the size. The view is being measured right then, so it asks
     * for no new layout pass: a parent that measures twice with different widths would otherwise
     * request layout after layout, for ever.
     */
    var fitting = false
        private set

    private val paint = TextPaint()

    init {
        view.setSingleLine()
        view.ellipsize = null
    }

    fun sizeSet() {
        if (!fitting) base = view.textSize
    }

    /** setText only lays out again by itself when the width wraps the text; the fit may change anyway. */
    fun textChanged() = view.requestLayout()

    fun beforeMeasure(widthSpec: Int) {
        var room = if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            View.MeasureSpec.getSize(widthSpec)
        }
        val max = view.maxWidth
        if (max in 1 until room) room = max
        val pad = view.compoundPaddingLeft + view.compoundPaddingRight
        resize(if (room == Int.MAX_VALUE) base else sizeFor(room - pad))
    }

    private fun sizeFor(room: Int): Float {
        val text = view.text
        if (text.isNullOrEmpty() || room <= 0) return base
        val shown = view.transformationMethod?.getTransformation(text, view) ?: text
        paint.set(view.paint)
        paint.textSize = base
        val full = Layout.getDesiredWidth(shown, paint)
        if (ceil(full) <= room) return base
        val min = base * MIN_SCALE
        var size = maxOf(min, floor(base * room / full))
        paint.textSize = size
        // Width is not exactly proportional to the size (hinting): step down until it fits.
        while (size > min && ceil(Layout.getDesiredWidth(shown, paint)) > room) {
            size = maxOf(min, size - 1f)
            paint.textSize = size
        }
        return size
    }

    private fun resize(px: Float) {
        if (px == view.textSize) return
        fitting = true
        try {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        } finally {
            fitting = false
        }
    }

    private companion object {
        /** Never smaller than this share of the set size: past it the text is cut, still one line. */
        const val MIN_SCALE = 0.55f
    }
}
