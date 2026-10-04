package com.lekaspos.core.text

/**
 * What a number pad has typed (references/money.md §8: digits fill from the right and are never
 * parsed through floating point): at most [maxDigits] digits, no leading zeros.
 *
 * An amount shown to be kept or typed over ([preset], e.g. a line's quantity of 2, the first count of
 * a product, the price now) is replaced by the first key typed, and Delete clears it: appended to,
 * "3" on a quantity of 2 made 23, and a recount of 6 over a first count of 5 made 56 (2026-10 review).
 */
class DigitEntry(private val maxDigits: Int) {

    init {
        require(maxDigits > 0) { "maxDigits must be > 0" }
    }

    var digits: String = ""
        private set

    /** The digits are the amount as it was shown ([preset]); the next key starts a new one. */
    var replacing: Boolean = false
        private set

    /** Puts [value]'s digits in, to be typed after. */
    fun set(value: String) = put(value, replace = false)

    /** Puts [value]'s digits in, to be kept (OK) or replaced by the first key. */
    fun preset(value: String) = put(value, replace = true)

    /** [set] or [preset], as given: a state saved before a key is put back. */
    fun put(value: String, replace: Boolean) {
        digits = value.filter { it in '0'..'9' }.trimStart('0').take(maxDigits)
        replacing = replace && digits.isNotEmpty()
    }

    /** A key: a digit or several ("00"), or [DELETE]. Returns true when the amount or its state changed. */
    fun press(key: String): Boolean {
        val from = if (replacing) "" else digits
        val next = if (key == DELETE) {
            from.dropLast(1)
        } else {
            require(key.isNotEmpty() && key.all { it in '0'..'9' }) { "not a key: $key" }
            if ((from + key).length > maxDigits) from else (from + key).trimStart('0')
        }
        val changed = next != digits || replacing
        digits = next
        replacing = false
        return changed
    }

    companion object {
        const val DELETE = "DEL"
    }
}
