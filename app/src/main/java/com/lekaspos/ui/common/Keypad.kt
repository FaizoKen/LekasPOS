package com.lekaspos.ui.common

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.lekaspos.R

/**
 * On-screen number pad for money, quantities and weights (references/money.md §8: digits fill
 * from the right, never parsed through floating point). A hardware keyboard or keypad also
 * works through [onKey].
 */
class Keypad(private val context: Context, private val maxDigits: Int = 9, private val onChange: (String) -> Unit) {

    var digits: String = ""
        private set

    val view: View = build()

    fun set(value: String) {
        digits = value.filter { it in '0'..'9' }.trimStart('0').take(maxDigits)
        onChange(digits)
    }

    fun clear() = set("")

    private fun press(key: String) {
        val next = when (key) {
            DEL -> digits.dropLast(1)
            else -> if ((digits + key).length > maxDigits) digits else (digits + key).trimStart('0')
        }
        if (next != digits) {
            digits = next
            onChange(digits)
        }
    }

    /** Hardware keys: digits, Backspace, and Escape/Delete to clear. Returns true if consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val code = event.keyCode
        when {
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> press(('0' + (code - KeyEvent.KEYCODE_0)).toString())
            code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> press(('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)).toString())
            code == KeyEvent.KEYCODE_DEL -> press(DEL)
            code == KeyEvent.KEYCODE_FORWARD_DEL -> clear()
            else -> return false
        }
        return true
    }

    private fun build(): View {
        val rows = listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"), listOf("00", "0", DEL))
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val h = (56 * context.resources.displayMetrics.density).toInt()
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val b = Button(context, null, 0, R.style.Widget_Lekas_Key)
                b.text = if (key == DEL) "⌫" else key
                b.gravity = Gravity.CENTER
                if (key == DEL) {
                    b.contentDescription = context.getString(R.string.key_delete)
                    b.setOnLongClickListener {
                        clear()
                        true
                    }
                }
                b.setOnClickListener { press(key) }
                line.addView(b, LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(3, 3, 3, 3) })
            }
            grid.addView(line)
        }
        return grid
    }

    companion object {
        private const val DEL = "DEL"
    }
}
