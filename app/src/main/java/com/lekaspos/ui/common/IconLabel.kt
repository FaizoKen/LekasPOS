package com.lekaspos.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView

/**
 * A button's word with its icon in front, both centred together (D-074): "⏸ Hold", "🖨 Print receipt". The icon
 * takes the text's colour at every draw (pressed, disabled) and its size (large fonts), which a compound drawable
 * does not below API 23, and a compound drawable sits at the button's edge, away from a centred word.
 */
fun TextView.setIconText(icon: Int, label: CharSequence) {
    text = iconLabel(context, icon, label)
    contentDescription = label // TalkBack reads the word, not the placeholder
}

/** [label] with [icon] in front, as [setIconText] shows it, for views that take only a text (the payment grid). */
fun iconLabel(ctx: Context, icon: Int, label: CharSequence): CharSequence {
    val d = ctx.getDrawable(icon)?.mutate() ?: return label
    return SpannableStringBuilder(ICON_CHAR).append("  ").append(label).apply {
        setSpan(IconSpan(d), 0, ICON_CHAR.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Stands in for the icon in the text (the Unicode object replacement character). */
private const val ICON_CHAR = "\uFFFC"

/** Draws [d] a little taller than the letters, centred on them, in the text's colour. */
private class IconSpan(private val d: Drawable) : ReplacementSpan() {

    private fun size(paint: Paint): Int = (paint.textSize * SCALE).toInt()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val p = paint.fontMetricsInt
            fm.ascent = p.ascent
            fm.descent = p.descent
            fm.top = p.top
            fm.bottom = p.bottom
        }
        return size(paint)
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val size = size(paint)
        val p = paint.fontMetricsInt
        val middle = y + (p.ascent + p.descent) / 2 // halfway up the letters
        d.setBounds(0, 0, size, size)
        d.setTint(paint.color)
        canvas.save()
        canvas.translate(x, (middle - size / 2).toFloat())
        d.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val SCALE = 1.2f
    }
}
