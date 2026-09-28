package com.lekaspos.core.money

/**
 * Overflow-checked Long arithmetic. Money must never wrap around silently.
 * (Implemented here rather than via java.lang.Math.*Exact, which Android only has from API 24.)
 */
object Checked {
    fun add(a: Long, b: Long): Long {
        val r = a + b
        if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("long overflow: $a + $b")
        return r
    }

    fun sub(a: Long, b: Long): Long {
        val r = a - b
        if (((a xor b) and (a xor r)) < 0) throw ArithmeticException("long overflow: $a - $b")
        return r
    }

    fun mul(a: Long, b: Long): Long {
        val r = a * b
        val absA = if (a < 0) -a else a
        val absB = if (b < 0) -b else b
        if ((absA or absB) ushr 31 != 0L) {
            if ((b != 0L && r / b != a) || (a == Long.MIN_VALUE && b == -1L)) {
                throw ArithmeticException("long overflow: $a * $b")
            }
        }
        return r
    }

    fun neg(a: Long): Long {
        if (a == Long.MIN_VALUE) throw ArithmeticException("long overflow: -$a")
        return -a
    }

    /** Floor division (rounds toward negative infinity). */
    fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a % b != 0L) && ((a xor b) < 0)) q--
        return q
    }

    /** Floor modulus, result has the sign of [b]. */
    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}
