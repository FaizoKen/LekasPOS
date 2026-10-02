package com.lekaspos.ui.common

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.lekaspos.core.scan.ScanBuffer

/**
 * A scanner fired while a text field had focus: its characters went into the field ("INV-0771"
 * became "INV-07719556001234567") and the item was lost. The field still gets every key; when a
 * burst at scanner speed ends with Enter or Tab, the burst is taken out of the field again (what
 * was typed before stays) and handed to [onScan]. The selling screen had this since D-054;
 * receiving, counting and picking lost such scans (2026-10 review).
 */
class FieldScan(private val onScan: (String) -> Unit) {
    private val burst = ScanBuffer()

    /** Every key of the screen while [field] has focus; true when the key ended a scan (consumed). */
    fun onKey(e: KeyEvent, field: android.widget.EditText): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                val r = burst.onTerminator() as? ScanBuffer.Result.Scan ?: return false
                takeOut(field, r.code)
                onScan(r.code)
                return true
            }
        }
        val ch = scanChar(e)
        if (ch > 0x1F && ch and KeyCharacterMap.COMBINING_ACCENT == 0) {
            if (burst.isIdle(e.eventTime)) burst.clear() // a person's earlier typing is not part of a scan
            burst.onChar(ch.toChar(), e.eventTime)
        }
        return false
    }

    fun clear() = burst.clear()

    private fun takeOut(field: android.widget.EditText, code: String) {
        val text = field.text ?: return
        val end = field.selectionEnd.takeIf { it in 0..text.length } ?: text.length
        val start = end - code.length
        when {
            start >= 0 && text.substring(start, end) == code -> text.delete(start, end)
            text.endsWith(code) -> text.delete(text.length - code.length, text.length)
        }
    }
}

/**
 * Keyboard-wedge (HID) scanner input for a screen whose text fields do not have focus
 * (references/architecture.md §7): feed every key event to [onKey]; fast bursts become
 * [onScan], slow typing becomes [onTyped] (text, Enter pressed).
 */
class ScanInput(
    private val onScan: (String) -> Unit,
    private val onTyped: (text: String, submit: Boolean) -> Unit,
) {
    private val buffer = ScanBuffer()
    private var scannedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (buffer.isIdle(SystemClock.uptimeMillis())) deliver(buffer.onIdle()) else if (!buffer.isEmpty) handler.postDelayed(this, 50L)
        }
    }

    /** Returns true when the key was taken as scanner/typing input. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_MULTIPLE && e.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = e.characters ?: return false
            for (c in chars) {
                if (c == '\n' || c == '\r') deliver(buffer.onTerminator()) else if (c >= ' ') feed(c, e.eventTime)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                // A scanner's second terminator (CR+LF) right after a scan must not press a focused button.
                if (buffer.isEmpty) return SystemClock.uptimeMillis() - scannedAt < AFTER_SCAN_MS
                handler.removeCallbacks(idleCheck)
                deliver(buffer.onTerminator())
                return true
            }
        }
        val ch = scanChar(e)
        if (ch <= 0x1F || ch and KeyCharacterMap.COMBINING_ACCENT != 0) return false
        feed(ch.toChar(), e.eventTime)
        return true
    }

    fun clear() {
        handler.removeCallbacks(idleCheck)
        buffer.clear()
    }

    private fun feed(c: Char, at: Long) {
        buffer.onChar(c, at)
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, ScanBuffer.IDLE_MS)
    }

    private companion object {
        const val AFTER_SCAN_MS = 300L
    }

    private fun deliver(r: ScanBuffer.Result?) {
        when (r) {
            null -> Unit
            is ScanBuffer.Result.Scan -> {
                scannedAt = SystemClock.uptimeMillis()
                onScan(r.code)
            }
            is ScanBuffer.Result.Typed -> onTyped(r.text, r.submit)
        }
    }
}

/**
 * The character a scanner's key stands for: the digits by key code — a scanner in numeric-keypad
 * mode with NumLock off lost every digit, and one set up for a US keyboard on a phone with another
 * layout typed "&é" for "12" (2026-10 review) — else what the keyboard layout gives.
 */
fun scanChar(e: KeyEvent): Int = when {
    e.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0'.code + (e.keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !e.isShiftPressed && !e.isAltPressed ->
        '0'.code + (e.keyCode - KeyEvent.KEYCODE_0)
    else -> e.getUnicodeChar(e.metaState)
}
