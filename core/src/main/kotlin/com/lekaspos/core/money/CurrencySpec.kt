package com.lekaspos.core.money

/**
 * How the store's currency is written and rounded. Comes from store settings, never from the
 * device locale, so every device prints identical receipts.
 */
data class CurrencySpec(
    val code: String = "MYR",
    val symbol: String = "RM",
    /** Digits after the decimal point, 0..3. */
    val decimals: Int = 2,
    /** Cash rounding step in minor units (MYR: 5 sen). 0 or 1 = no cash rounding. */
    val cashStep: Long = 5L,
    val symbolBefore: Boolean = true,
    val groupSeparator: Char = ',',
    val decimalSeparator: Char = '.',
) {
    init {
        require(decimals in 0..3) { "decimals must be 0..3" }
        require(cashStep >= 0L) { "cashStep must be >= 0" }
        require(groupSeparator != decimalSeparator) { "separators must differ" }
    }

    /** 10^decimals: minor units per major unit. */
    val scale: Long
        get() = when (decimals) {
            0 -> 1L
            1 -> 10L
            2 -> 100L
            else -> 1000L
        }

    companion object {
        val MYR = CurrencySpec()
    }
}
