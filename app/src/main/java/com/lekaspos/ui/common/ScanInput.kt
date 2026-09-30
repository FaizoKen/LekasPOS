package com.lekaspos.ui.common

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.lekaspos.core.scan.ScanBuffer

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
        val ch = e.getUnicodeChar(e.metaState)
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
