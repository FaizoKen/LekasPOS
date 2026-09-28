package com.lekaspos.core.money

/** Formatting and exact parsing of money and quantities. Never goes through Double. */
object MoneyFormat {

    /** "RM1,234.50", "-RM0.05"; [withSymbol] = false gives "1,234.50". */
    fun format(minor: Long, spec: CurrencySpec, withSymbol: Boolean = true): String {
        val negative = minor < 0L
        val abs = if (negative) Checked.neg(minor) else minor
        val major = abs / spec.scale
        val fraction = abs % spec.scale
        val sb = StringBuilder(24)
        if (negative) sb.append('-')
        if (withSymbol && spec.symbolBefore) sb.append(spec.symbol)
        appendGrouped(sb, major, spec.groupSeparator)
        if (spec.decimals > 0) {
            sb.append(spec.decimalSeparator)
            appendPadded(sb, fraction, spec.decimals)
        }
        if (withSymbol && !spec.symbolBefore) sb.append(' ').append(spec.symbol)
        return sb.toString()
    }

    /** Machine format for CSV/exports: "-1234.50" — no symbol, no grouping, '.' decimal. */
    fun plain(minor: Long, decimals: Int): String {
        require(decimals in 0..3)
        val scale = pow10(decimals)
        val negative = minor < 0L
        val abs = if (negative) Checked.neg(minor) else minor
        val sb = StringBuilder(20)
        if (negative) sb.append('-')
        sb.append(abs / scale)
        if (decimals > 0) {
            sb.append('.')
            appendPadded(sb, abs % scale, decimals)
        }
        return sb.toString()
    }

    /**
     * Parses user text such as "12.5", "RM 1,234.50", "-3" into minor units.
     * Returns null for anything that is not an exact amount (letters, too many decimals,
     * misplaced separators, overflow).
     */
    fun parse(text: String, spec: CurrencySpec): Long? {
        var s = text.trim()
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1).trim()
        }
        if (spec.symbol.isNotEmpty()) {
            if (s.startsWith(spec.symbol, ignoreCase = true)) s = s.substring(spec.symbol.length).trim()
            else if (s.endsWith(spec.symbol, ignoreCase = true)) s = s.substring(0, s.length - spec.symbol.length).trim()
        }
        if (s.startsWith("-") && !negative) {
            negative = true
            s = s.substring(1).trim()
        }
        return parseUnsigned(s, spec.decimals, spec.decimalSeparator, spec.groupSeparator)
            ?.let { if (negative) -it else it }
    }

    /** Machine parse for CSV imports: digits, optional '-', optional '.' fraction. */
    fun parsePlain(text: String, decimals: Int): Long? {
        var s = text.trim()
        var negative = false
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1)
        }
        return parseUnsigned(s, decimals, '.', null)?.let { if (negative) -it else it }
    }

    /**
     * POS keypad entry: digits fill from the right, so "1","2","5","0" means 12.50 with two
     * decimals. Leading zeros are ignored. Returns null if [digits] contains a non-digit or
     * would overflow.
     */
    fun keypad(digits: String, spec: CurrencySpec): Long? {
        if (digits.isEmpty()) return 0L
        var v = 0L
        for (c in digits) {
            if (c !in '0'..'9') return null
            v = try {
                Checked.add(Checked.mul(v, 10L), (c - '0').toLong())
            } catch (e: ArithmeticException) {
                return null
            }
        }
        return v
    }

    /** Quantity in milli-units as text: 3000 → "3", 253 → "0.253", 1500 → "1.5". */
    fun formatQty(milli: Long): String {
        val negative = milli < 0L
        val abs = if (negative) Checked.neg(milli) else milli
        val sb = StringBuilder(16)
        if (negative) sb.append('-')
        sb.append(abs / 1000L)
        var frac = abs % 1000L
        if (frac != 0L) {
            var digits = 3
            while (frac % 10L == 0L) {
                frac /= 10L
                digits--
            }
            sb.append('.')
            appendPadded(sb, frac, digits)
        }
        return sb.toString()
    }

    /** Parses "3", "0.253", "1.5" into milli-units; more than 3 decimals → null. */
    fun parseQty(text: String): Long? {
        var s = text.trim()
        var negative = false
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1)
        }
        return parseUnsigned(s, 3, '.', null)?.let { if (negative) -it else it }
    }

    private fun parseUnsigned(s: String, decimals: Int, decimalSep: Char, groupSep: Char?): Long? {
        if (s.isEmpty()) return null
        val dot = s.indexOf(decimalSep)
        if (dot != s.lastIndexOf(decimalSep)) return null
        val intPart = if (dot >= 0) s.substring(0, dot) else s
        val fracPart = if (dot >= 0) s.substring(dot + 1) else ""
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        if (fracPart.length > decimals) return null
        if (!fracPart.all { it in '0'..'9' }) return null
        val intDigits = if (groupSep != null && intPart.indexOf(groupSep) >= 0) {
            val groups = intPart.split(groupSep)
            // "1,234,567": first group 1-3 digits, all others exactly 3
            if (groups[0].isEmpty() || groups[0].length > 3) return null
            for (i in 1 until groups.size) if (groups[i].length != 3) return null
            groups.joinToString("")
        } else {
            intPart
        }
        if (!intDigits.all { it in '0'..'9' }) return null
        return try {
            var v = 0L
            for (c in intDigits) v = Checked.add(Checked.mul(v, 10L), (c - '0').toLong())
            for (i in 0 until decimals) {
                val d = if (i < fracPart.length) (fracPart[i] - '0').toLong() else 0L
                v = Checked.add(Checked.mul(v, 10L), d)
            }
            v
        } catch (e: ArithmeticException) {
            null
        }
    }

    private fun appendGrouped(sb: StringBuilder, value: Long, sep: Char) {
        val digits = value.toString()
        val first = digits.length % 3
        for (i in digits.indices) {
            if (i > 0 && (i - first) % 3 == 0) sb.append(sep)
            sb.append(digits[i])
        }
    }

    private fun appendPadded(sb: StringBuilder, value: Long, width: Int) {
        val s = value.toString()
        for (i in s.length until width) sb.append('0')
        sb.append(s)
    }

    private fun pow10(n: Int): Long {
        var r = 1L
        for (i in 0 until n) r *= 10L
        return r
    }
}
