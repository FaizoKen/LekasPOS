package com.lekaspos.ui.common

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R

/**
 * Wider than tall (a phone or tablet held sideways). A keypad under what it types does not fit the
 * height then: keypad dialogs and the sign-in put them side by side instead (D-063).
 */
fun Context.sideways(): Boolean = resources.configuration.let { it.screenWidthDp > it.screenHeightDp }

/**
 * A keypad key's height in px: [normalDp], or a little less on a short screen held sideways — four rows of
 * keys and the dialog's own room then fit a phone's height (never under the 48dp every button keeps).
 */
fun Context.keyHeightPx(normalDp: Int): Int {
    val dp = if (sideways() && resources.configuration.screenHeightDp < SHORT_DP) minOf(normalDp, SHORT_KEY_DP) else normalDp
    return (dp * resources.displayMetrics.density).toInt()
}

private const val SHORT_DP = 480
private const val SHORT_KEY_DP = 50

/** A sideways dialog's width: most of the screen, so its columns are not squeezed. */
fun AlertDialog.wide() {
    val dm = context.resources.displayMetrics
    val w = minOf((dm.widthPixels * 0.94f).toInt(), (MAX_WIDE_DP * dm.density).toInt())
    window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private const val MAX_WIDE_DP = 860

/**
 * A dialog built around a number pad (D-063). Upright, as before: the title bar, [info] over the pad
 * (scrolling if it must), the buttons in the bar below. Sideways ([sideways]): no title bar or button
 * bar — the title, [info] and the buttons in a column on the left, the pad on the right — so every key
 * and button shows at once, with nothing to scroll on a phone held sideways.
 */
class PadDialog(private val a: Context, private val title: CharSequence?) {

    private class Action(val which: Int, val text: CharSequence, val onClick: ((AlertDialog) -> Unit)?)

    private val info = ArrayList<Pair<View, LinearLayout.LayoutParams?>>()
    private var pad: View? = null
    private val actions = ArrayList<Action>(3)

    /** Above the pad (upright) or beside it (sideways), in the order added. */
    fun info(v: View, params: LinearLayout.LayoutParams? = null) = apply { info += v to params }

    fun pad(v: View) = apply { pad = v }

    /** The main button: [onClick] runs and the dialog stays open (it dismisses it when done). */
    fun positive(text: CharSequence, onClick: (AlertDialog) -> Unit) = apply { actions += Action(AlertDialog.BUTTON_POSITIVE, text, onClick) }

    /** A third button, like [positive]. */
    fun neutral(text: CharSequence, onClick: (AlertDialog) -> Unit) = apply { actions += Action(AlertDialog.BUTTON_NEUTRAL, text, onClick) }

    /** Closes the dialog (Cancel, Close). */
    fun negative(text: CharSequence) = apply { actions += Action(AlertDialog.BUTTON_NEGATIVE, text, null) }

    fun create(): AlertDialog = if (a.sideways()) createSideways() else createUpright()

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun createUpright(): AlertDialog {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        for ((v, lp) in info) col.addView(v, lp ?: matchWrap())
        pad?.let { col.addView(it, matchWrap()) }
        val b = AlertDialog.Builder(a).setView(Dialogs.scrolling(col))
        if (title != null) b.setTitle(title)
        for (act in actions) {
            when (act.which) {
                AlertDialog.BUTTON_POSITIVE -> b.setPositiveButton(act.text, null)
                AlertDialog.BUTTON_NEUTRAL -> b.setNeutralButton(act.text, null)
                else -> b.setNegativeButton(act.text, null)
            }
        }
        val d = b.create()
        d.setOnShowListener {
            // Set here, not in the builder: a click that only checks the input must not close the dialog.
            for (act in actions) {
                val click = act.onClick ?: continue
                d.getButton(act.which)?.setOnClickListener { click(d) }
            }
        }
        return d
    }

    /** The sideways dialog, once made: its buttons act on it. */
    private var sidewaysDialog: AlertDialog? = null

    private fun createSideways(): AlertDialog {
        val d = AlertDialog.Builder(a).setView(sidewaysBody()).create()
        sidewaysDialog = d
        d.setOnShowListener { d.wide() }
        return d
    }

    /** The sideways body: title, info and buttons on the left, the pad on the right (tests measure it). */
    internal fun sidewaysBody(): View {
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (title != null) {
            left.addView(
                TextView(a, null, 0, R.style.Text_Lekas_Section).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                },
                matchWrap().apply { bottomMargin = dp(4) },
            )
        }
        for ((v, lp) in info) left.addView(v, lp ?: matchWrap())
        left.addView(View(a), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) // the buttons at the bottom
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        // In the order of a dialog's own bar: the third button, Cancel, then the main one.
        val order = listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)
        for ((i, act) in actions.sortedBy { order.indexOf(it.which) }.withIndex()) {
            val style = if (act.which == AlertDialog.BUTTON_POSITIVE) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Button_Secondary
            val btn = FitButton(a, null, 0, style)
            btn.text = act.text
            btn.minWidth = 0
            btn.setOnClickListener {
                val d = sidewaysDialog ?: return@setOnClickListener
                act.onClick?.invoke(d) ?: d.dismiss()
            }
            bar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        if (actions.isNotEmpty()) left.addView(bar, matchWrap().apply { topMargin = dp(8) })
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pad?.let { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }) }
        return Dialogs.scrolling(row)
    }

    private companion object {
        const val TITLE_SP = 20f
    }
}
