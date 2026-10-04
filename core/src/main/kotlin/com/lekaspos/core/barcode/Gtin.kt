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
     * EAN-13 with a leading 0, and GTIN-14 with a leading 0 is an EAN-13. A 7- or 11-digit code
     * may be an EAN-8 / UPC-A without its leading 0 (a product file stores it with the 0 again,
     * [restoreLeadingZero]), so the padded form is tried too when it is a valid GTIN. An EAN-13
     * also finds a code stored in its 14-digit form (a distributor's file, before [canonical]).
     */
    fun lookupVariants(raw: String): List<String> {
        val code = asciiDigits(raw.trim())
        if (code.isEmpty()) return emptyList()
        if (!code.all { it in '0'..'9' }) return listOf(code)
        val forms = when (code.length) {
            7, 11 -> if (isValid("0$code")) listOf(code, "0$code") else listOf(code)
            12 -> listOf(code, "0$code")
            13 -> if (code[0] == '0') listOf(code, code.substring(1)) else listOf(code, "0$code")
            14 -> if (code[0] == '0') listOf(code, code.substring(1)) else listOf(code)
            else -> listOf(code)
        }
        // UPC-E, the short form of a UPC-A on small packs: cameras and many scanners send the 8 digits
        // while a supplier's list holds the 12 (or 13), or the other way round (2026-10 review).
        val upcE = when (code.length) {
            8 -> upcEToUpcA(code)?.let { listOf(it, "0$it") }
            12 -> upcAToUpcE(code)?.let { listOf(it) }
            13 -> if (code[0] == '0') upcAToUpcE(code.substring(1))?.let { listOf(it) } else null
            else -> null
        }
        return if (upcE == null) forms else (forms + upcE).distinct()
    }

    /**
     * The UPC-A of the UPC-E [code] (8 digits: number system 0 or 1, six digits, check digit), or null
     * when [code] is none. The check digit is the same in both forms.
     */
    fun upcEToUpcA(code: String): String? {
        if (code.length != 8 || !code.all { it in '0'..'9' } || (code[0] != '0' && code[0] != '1')) return null
        val d = code.substring(1, 7)
        val body = code[0] + when (d[5]) {
            '0', '1', '2' -> d.substring(0, 2) + d[5] + "0000" + d.substring(2, 5)
            '3' -> d.substring(0, 3) + "00000" + d.substring(3, 5)
            '4' -> d.substring(0, 4) + "00000" + d[4]
            else -> d.substring(0, 5) + "0000" + d[5]
        }
        val upcA = body + code[7]
        return if (isValid(upcA)) upcA else null
    }

    /** The UPC-E of the UPC-A [code] when it has one (its zeros fit one of the four patterns), else null. */
    fun upcAToUpcE(code: String): String? {
        if (code.length != 12 || !code.all { it in '0'..'9' } || (code[0] != '0' && code[0] != '1') || !isValid(code)) return null
        val m = code.substring(1, 6) // manufacturer
        val p = code.substring(6, 11) // product
        val six = when {
            m.substring(2) in setOf("000", "100", "200") && p.startsWith("00") -> m.substring(0, 2) + p.substring(2) + m[2]
            m.endsWith("00") && p.startsWith("000") -> m.substring(0, 3) + p.substring(3) + "3"
            m.endsWith("0") && p.startsWith("0000") -> m.substring(0, 4) + p[4] + "4"
            p.startsWith("0000") && p[4] in '5'..'9' -> m + p[4]
            else -> return null
        }
        val upcE = code[0] + six + code[11]
        return if (upcEToUpcA(upcE) == code) upcE else null
    }

    /**
     * [text] with other scripts' digits (Arabic-Indic, Bengali, Devanagari, full-width …) as 0–9: a
     * barcode typed on such a keyboard was kept as typed, and no scan ever matched it (2026-10 review).
     */
    fun asciiDigits(text: String): String {
        if (text.all { it.code < 0x80 }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            val d = if (c.code >= 0x80) Character.digit(c, 10) else -1
            sb.append(if (d >= 0) '0' + d else c)
        }
        return sb.toString()
    }

    /**
     * The form a barcode is stored in (2026-10 review: typed or imported codes came in forms no
     * scan could reach): surrounding spaces and line ends gone ("ABC123\n" from a camera or a
     * paste); the digits of an EAN/UPC typed as printed under the bars without their spaces or
     * dashes ("9 556001 234567"); a GTIN-14 with a leading 0 as its EAN-13; a 7- or 11-digit code
     * with the 0 a spreadsheet dropped ([restoreLeadingZero]). Other codes stay as they are.
     */
    fun canonical(raw: String): String {
        val code = asciiDigits(raw.trim())
        if (code.isEmpty()) return code
        val digits = if (code.any { it == ' ' || it == '-' }) code.filter { it != ' ' && it != '-' } else code
        if (digits !== code && !(digits.all { it in '0'..'9' } && isValid(digits))) return code
        if (digits.length == 14 && digits[0] == '0' && isValid(digits)) return digits.substring(1)
        return restoreLeadingZero(digits)
    }

    /**
     * [code] with the leading 0 a spreadsheet dropped (it stored the barcode as a number): a
     * 7- or 11-digit code that is a valid EAN-8 / UPC-A with one 0 in front gets it back
     * (2026-10 review). Any other code is returned as it is: 7 and 11 digits are no GTIN length,
     * and a 12-digit code is already a UPC-A (a leading 0 never changes a GTIN check digit), which
     * the lookups pair with its EAN-13 form.
     */
    fun restoreLeadingZero(code: String): String {
        if ((code.length == 7 || code.length == 11) && code.all { it in '0'..'9' }) {
            val padded = "0$code"
            if (isValid(padded)) return padded
        }
        return code
    }

    /**
     * The form an older import stored [code] in when a spreadsheet had dropped its leading 0 (an
     * 8- or 12-digit code starting with 0, without it), or null: a re-import must find that product
     * instead of creating it again.
     */
    fun withoutLeadingZero(code: String): String? =
        if ((code.length == 8 || code.length == 12) && code[0] == '0' && code.all { it in '0'..'9' }) code.substring(1) else null
}
