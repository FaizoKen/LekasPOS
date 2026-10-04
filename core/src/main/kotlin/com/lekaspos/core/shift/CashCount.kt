package com.lekaspos.core.shift

import com.lekaspos.core.money.Checked

/**
 * Counting the drawer note by note and coin by coin when a shift closes: the cashier types how
 * many of each there are and the till adds them up, instead of adding a drawer full of cash in
 * their head and typing one total (a slip there is a "difference" nobody can explain).
 */
object CashCount {

    /**
     * The notes and coins in use for the currency [code] (ISO 4217), largest first, in minor units;
     * empty when the till does not know them (the total is then typed as before).
     * Malaysia: RM100, 50, 20, 10, 5, 1 notes and 50, 20, 10, 5 sen coins (1 sen is no longer
     * used: cash is rounded to 5 sen).
     */
    fun denominations(code: String): List<Long> = when (code.trim().uppercase()) {
        "MYR" -> listOf(10_000L, 5_000L, 2_000L, 1_000L, 500L, 100L, 50L, 20L, 10L, 5L)
        else -> emptyList()
    }

    /** Σ value × pieces over [pieces] (denomination → how many); throws on a negative count or an overflow. */
    fun total(pieces: Map<Long, Long>): Long {
        var sum = 0L
        for ((value, n) in pieces) {
            require(value > 0L) { "denomination must be > 0" }
            require(n >= 0L) { "count must be >= 0" }
            sum = Checked.add(sum, Checked.mul(value, n))
        }
        return sum
    }

    /**
     * The count as one line for the shift's note, largest first, e.g. "RM100 x 2, RM10 x 3, 50 sen x 1"
     * (only denominations counted at least once; plain "x": the note is printed on the shift report,
     * and many receipt printers have no "×"); [label] writes one denomination.
     */
    fun summary(pieces: Map<Long, Long>, label: (Long) -> String): String =
        pieces.entries.filter { it.value > 0L }.sortedByDescending { it.key }.joinToString(", ") { "${label(it.key)} x ${it.value}" }
}
