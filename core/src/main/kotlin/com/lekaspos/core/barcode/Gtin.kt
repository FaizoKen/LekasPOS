package com.lekaspos.core.barcode

/** GS1 barcodes (EAN-8, UPC-A, EAN-13, GTIN-14): check digits and lookup variants. */
object Gtin {

    /** GS1 mod-10 check digit for [digits] (the code without its check digit). */
    fun checkDigit(digits: CharSequence): Int {
        var sum = 0
        var weight3 = true // the digit next to the check digit has weight 3
        for (i in digits.length - 1 downTo 0) {
            val d = digits[i] - '0'
            require(d in 0..9) { "not a digit: ${digits[i]}" }
            sum += if (weight3) d * 3 else d
            weight3 = !weight3
        }
        return (10 - sum % 10) % 10
    }

    /** True for an all-digit code of length 8, 12, 13 or 14 with a correct check digit. */
    fun isValid(code: String): Boolean {
        if (code.length != 8 && code.length != 12 && code.length != 13 && code.length != 14) return false
        if (!code.all { it in '0'..'9' }) return false
        return checkDigit(code.subSequence(0, code.length - 1)) == code.last() - '0'
    }

    /** [body] plus its check digit. */
    fun withCheckDigit(body: String): String = body + checkDigit(body)

    /**
     * Codes to try when looking a scan up: scanners report UPC-A either as 12 digits or as
     * EAN-13 with a leading 0, and GTIN-14 with a leading 0 is an EAN-13.
     */
    fun lookupVariants(raw: String): List<String> {
        val code = raw.trim()
        if (code.isEmpty()) return emptyList()
        if (!code.all { it in '0'..'9' }) return listOf(code)
        return when (code.length) {
            12 -> listOf(code, "0$code")
            13 -> if (code[0] == '0') listOf(code, code.substring(1)) else listOf(code)
            14 -> if (code[0] == '0') listOf(code, code.substring(1)) else listOf(code)
            else -> listOf(code)
        }
    }
}
