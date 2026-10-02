package com.lekaspos.ui.common

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.lekaspos.R
import com.lekaspos.app.LekasApp

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
        // Dialogs are windows of their own: the screen does not see these taps. An idle time that ran
        // out while paying is kept (the till locks after the payment), so it is not simply reset here.
        if (LekasApp.graph(context).staff.dialogActivity()) return
        val next = when (key) {
            DEL -> digits.dropLast(1)
            else -> if ((digits + key).length > maxDigits) digits else (digits + key).trimStart('0')
        }
        if (next != digits) {
            digits = next
            onChange(digits)
        }
    }

    private var lastKeyAt = 0L
    private var burstUntil = 0L

    /** The digits before the last key typed at a person's speed (restored when a scanner's burst starts). */
    private var beforeKey = ""

    /**
     * Hardware keys: digits, Backspace, and Delete to clear. Returns true if consumed.
     *
     * Two protections against a barcode scanned while the dialog is open (found in the 2026-10
     * review: the scanner's Enter pressed the focused "Exact" button and completed the sale):
     * Enter, Tab and Space are always swallowed, so they never press whatever has keyboard focus;
     * and digits arriving at scanner speed are dropped and undone, so a barcode never becomes the
     * amount.
     */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (DialogKeys.pressesFocused(code)) return true
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)).toString()
            else -> null
        }
        if (digit == null && code != KeyEvent.KEYCODE_DEL && code != KeyEvent.KEYCODE_FORWARD_DEL) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val t = event.eventTime
        val gap = t - lastKeyAt
        lastKeyAt = t
        if (gap < DialogKeys.BURST_GAP_MS || t < burstUntil) {
            if (t >= burstUntil) set(beforeKey) // a burst begins: undo the key that started it
            burstUntil = t + DialogKeys.BURST_IDLE_MS
            return true
        }
        beforeKey = digits
        when {
            digit != null -> press(digit)
            code == KeyEvent.KEYCODE_DEL -> press(DEL)
            else -> clear()
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
