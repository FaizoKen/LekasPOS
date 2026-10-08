package com.lekaspos.ui.common

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import com.lekaspos.core.model.TileColor

/**
 * The tile colours to choose from (D-066): "none" then every [TileColor], round swatches drawn 36dp
 * inside 48dp touch targets, wrapping to as many lines as the width needs. The chosen one has a dark ring.
 */
class ColorPicker(private val ctx: Context, chosen: Int, private val onPick: (Int) -> Unit = {}) {

    var chosen: Int = TileColor.known(chosen)
        private set

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    val view: WrapRow = WrapRow(ctx, dp(4)).also { row ->
        for (code in listOf(TileColor.NONE) + TileColor.ALL) {
            val v = View(ctx).apply {
                contentDescription = TileColors.name(ctx, code)
                isClickable = true
                isFocusable = true
                tag = code
                setOnClickListener {
                    this@ColorPicker.chosen = code
                    paint()
                    onPick(code)
                }
            }
            row.addView(v, ViewGroup.LayoutParams(dp(48), dp(48)))
        }
        paint(row)
    }

    private fun paint(row: WrapRow = view) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val code = v.tag as Int
            v.background = InsetDrawable(TileColors.swatch(ctx, code, code == chosen), dp(6))
            v.isSelected = code == chosen // TalkBack says which one is chosen
        }
    }
}
