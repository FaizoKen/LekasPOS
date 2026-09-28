package com.lekaspos.core.scan

/**
 * Separates a keyboard-wedge (HID) barcode scanner from a person typing, for key presses that
 * arrive while no text field has focus. Scanners "type" a whole code within a few
 * milliseconds per character and usually finish with Enter; people are 5–20× slower.
 *
 * The caller feeds characters with their event time, calls [onTerminator] for Enter/Tab and
 * [onIdle] when no key arrived for [IDLE_MS].
 */
class ScanBuffer(
    private val maxAvgGapMs: Long = 60L,
    private val minScanLength: Int = 3,
    /** Codes without a terminator are accepted only from this length (avoids stray keys). */
    private val minLengthWithoutTerminator: Int = 6,
) {
    sealed class Result {
        /** A scanner delivered [code]. */
        data class Scan(val code: String) : Result()

        /** A person typed [text]; [submit] = they pressed Enter. */
        data class Typed(val text: String, val submit: Boolean) : Result()
    }

    private val sb = StringBuilder(32)
    private var firstAt = 0L
    private var lastAt = 0L

    val isEmpty: Boolean get() = sb.isEmpty()

    fun onChar(c: Char, at: Long) {
        if (sb.length >= MAX_LENGTH) sb.setLength(0) // runaway input (key held down): start over
        if (sb.isEmpty()) firstAt = at
        sb.append(c)
        lastAt = at
    }

    fun onTerminator(): Result? {
        if (sb.isEmpty()) return null
        val text = sb.toString()
        val r = if (isFast() && text.length >= minScanLength) Result.Scan(text) else Result.Typed(text, submit = true)
        sb.setLength(0)
        return r
    }

    /** True when [now] is at least [IDLE_MS] after the last character. */
    fun isIdle(now: Long): Boolean = sb.isNotEmpty() && now - lastAt >= IDLE_MS

    fun onIdle(): Result? {
        if (sb.isEmpty()) return null
        val text = sb.toString()
        val r = if (isFast() && text.length >= minLengthWithoutTerminator) Result.Scan(text) else Result.Typed(text, submit = false)
        sb.setLength(0)
        return r
    }

    fun clear() {
        sb.setLength(0)
    }

    private fun isFast(): Boolean {
        if (sb.length < 2) return false
        return (lastAt - firstAt) / (sb.length - 1) <= maxAvgGapMs
    }

    companion object {
        const val IDLE_MS = 250L
        const val MAX_LENGTH = 128
    }
}
