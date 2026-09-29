package com.lekaspos.ui.common

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.staff.PinHash
import com.lekaspos.ui.colorOf

/**
 * PIN entry (D-037): dots for the digits typed and a 1–9 / ⌫ 0 OK keypad. A hardware keyboard
 * works through [onKey]; a barcode scanner's burst of digits is recognised by its speed and
 * thrown away, so scanning while the lock screen is up never counts as a wrong PIN.
 */
class PinPad(private val context: Context, private val onSubmit: (String) -> Unit) {

    var pin: String = ""
        private set

    private val dots = TextView(context, null, 0, R.style.Text_Lekas_Display)
    val message: TextView = TextView(context, null, 0, R.style.Text_Lekas_Body)
    private val keys = ArrayList<Button>(12)
    private var lastKeyAt = 0L
    private var burstUntil = 0L

    val view: View = build()

    fun clear() {
        pin = ""
        render()
    }

    /** A line under the dots (errors, waiting time); null hides it. */
    fun setMessage(text: CharSequence?, error: Boolean = true) {
        message.text = text
        message.setTextColor(context.colorOf(if (error) R.color.danger else R.color.text_secondary))
        message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setEnabled(on: Boolean) {
        for (k in keys) k.isEnabled = on
    }

    private fun press(key: String) {
        when (key) {
            DEL -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
            OK -> {
                if (pin.length >= PinHash.MIN_LENGTH) {
                    val p = pin
                    pin = ""
                    render()
                    onSubmit(p)
                }
                return
            }
            else -> if (pin.length < PinHash.MAX_LENGTH) pin += key
        }
        render()
    }

    /** Hardware keys: digits, Backspace and Enter. Returns true if consumed. */
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)).toString()
            else -> null
        }
        val enter = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (digit == null && !enter && code != KeyEvent.KEYCODE_DEL) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val t = e.eventTime
        val gap = t - lastKeyAt
        lastKeyAt = t
        if (gap < BURST_GAP_MS || t < burstUntil) {
            // A scanner types far faster than a person: drop the whole burst.
            burstUntil = t + BURST_IDLE_MS
            if (pin.isNotEmpty()) clear()
            return true
        }
        press(if (enter) OK else digit ?: DEL)
        return true
    }

    private fun render() {
        dots.text = if (pin.isEmpty()) context.getString(R.string.pin_enter) else "●".repeat(pin.length)
        dots.setTextColor(context.colorOf(if (pin.isEmpty()) R.color.text_disabled else R.color.text_primary))
    }

    private fun build(): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val density = context.resources.displayMetrics.density
        dots.gravity = Gravity.CENTER
        dots.letterSpacing = 0.3f
        dots.minHeight = (48 * density).toInt()
        col.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        message.gravity = Gravity.CENTER
        message.visibility = View.GONE
        col.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val h = (60 * density).toInt()
        for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(DEL, "0", OK))) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val b = Button(context, null, 0, if (key == OK) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Key)
                b.text = when (key) {
                    DEL -> "⌫"
                    OK -> context.getString(R.string.ok)
                    else -> key
                }
                if (key == DEL) {
                    b.contentDescription = context.getString(R.string.key_delete)
                    b.setOnLongClickListener {
                        clear()
                        true
                    }
                }
                b.setOnClickListener { press(key) }
                keys.add(b)
                line.addView(b, LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(3, 3, 3, 3) })
            }
            col.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        render()
        return col
    }

    companion object {
        private const val DEL = "DEL"
        private const val OK = "OK"

        /** Keys closer together than this come from a scanner, not a person. */
        private const val BURST_GAP_MS = 35L
        private const val BURST_IDLE_MS = 300L
    }
}
