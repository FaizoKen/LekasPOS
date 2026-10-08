package com.lekaspos.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import com.lekaspos.R
import com.lekaspos.core.model.TileColor
import com.lekaspos.ui.colorOf

/**
 * The shades of [TileColor] codes (D-066). Each keeps white text readable (at least 4.5:1), so a
 * coloured tile's name and price are white on it.
 */
object TileColors {

    fun argb(code: Int): Int? = when (TileColor.known(code)) {
        TileColor.RED -> 0xFFC62828.toInt()
        TileColor.PINK -> 0xFFAD1457.toInt()
        TileColor.PURPLE -> 0xFF6A1B9A.toInt()
        TileColor.INDIGO -> 0xFF283593.toInt()
        TileColor.BLUE -> 0xFF1565C0.toInt()
        TileColor.TEAL -> 0xFF00695C.toInt()
        TileColor.GREEN -> 0xFF2E7D32.toInt()
        TileColor.LIME -> 0xFF4D7C0F.toInt()
        TileColor.AMBER -> 0xFFA14A00.toInt()
        TileColor.ORANGE -> 0xFFC2410C.toInt()
        TileColor.BROWN -> 0xFF6D4C41.toInt()
        TileColor.GREY -> 0xFF455A64.toInt()
        else -> null
    }

    /** Its name, for TalkBack and the colour picker. */
    fun name(ctx: Context, code: Int): String = ctx.getString(
        when (TileColor.known(code)) {
            TileColor.RED -> R.string.color_red
            TileColor.PINK -> R.string.color_pink
            TileColor.PURPLE -> R.string.color_purple
            TileColor.INDIGO -> R.string.color_indigo
            TileColor.BLUE -> R.string.color_blue
            TileColor.TEAL -> R.string.color_teal
            TileColor.GREEN -> R.string.color_green
            TileColor.LIME -> R.string.color_lime
            TileColor.AMBER -> R.string.color_amber
            TileColor.ORANGE -> R.string.color_orange
            TileColor.BROWN -> R.string.color_brown
            TileColor.GREY -> R.string.color_grey
            else -> R.string.color_none
        },
    )

    /**
     * A tile's background in colour [code] (not none): the colour, a dark frame when it is on the bill
     * (selected), a light ripple. A tile builds it only when its colour changes.
     */
    fun tileBackground(ctx: Context, code: Int): Drawable {
        val fill = argb(code) ?: ctx.colorOf(R.color.surface)
        val d = ctx.resources.displayMetrics.density
        fun shape(stroke: Boolean) = GradientDrawable().apply {
            cornerRadius = 8 * d
            setColor(fill)
            if (stroke) setStroke((4 * d).toInt(), ctx.colorOf(R.color.text_primary))
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), shape(true))
            addState(intArrayOf(), shape(false))
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), states, null)
    }

    /** A round swatch of [code] (none: white with a grey ring) for the colour pickers. */
    fun swatch(ctx: Context, code: Int, chosen: Boolean): Drawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(argb(code) ?: ctx.colorOf(R.color.surface))
            when {
                chosen -> setStroke((4 * d).toInt(), ctx.colorOf(R.color.text_primary))
                argb(code) == null -> setStroke((2 * d).toInt(), ctx.colorOf(R.color.field_stroke))
            }
        }
    }
}
