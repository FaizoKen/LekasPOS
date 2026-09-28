package com.lekaspos.core.money

/**
 * The only rounding rules used for money (see the skill's references/money.md):
 * half-up = nearest integer, halves away from zero, symmetric for negatives.
 */
object Rounding {

    /** [n] / [d] rounded half away from zero. */
    fun roundHalfUp(n: Long, d: Long): Long {
        require(d != 0L) { "division by zero" }
        require(d != Long.MIN_VALUE) { "divisor out of range" }
        val q = n / d
        val r = n % d
        if (r == 0L) return q
        val absR = if (r < 0) -r else r
        val absD = if (d < 0) -d else d
        // 2|r| >= |d|  <=>  |r| >= |d| - |r|   (no overflow)
        return if (absR >= absD - absR) {
            if ((n < 0) == (d < 0)) q + 1 else q - 1
        } else {
            q
        }
    }

    /** [a] × [b] / [d], half-up. Throws [ArithmeticException] if a × b overflows. */
    fun mulDivHalfUp(a: Long, b: Long, d: Long): Long = roundHalfUp(Checked.mul(a, b), d)

    /** Rounds [amount] to the nearest multiple of [step] (half-up). A step of 0 or 1 is a no-op. */
    fun toStep(amount: Long, step: Long): Long {
        require(step >= 0) { "negative step" }
        if (step <= 1L) return amount
        return Checked.mul(roundHalfUp(amount, step), step)
    }

    /**
     * Splits [total] into parts proportional to [weights] with the largest-remainder method:
     * every part gets floor(total × w / W) and the leftover units go to the largest fractional
     * remainders (ties → lower index). The parts always sum exactly to [total].
     * A negative total is split by absolute value and negated, so refunds mirror sales.
     * Weights must be ≥ 0; if they are all 0 the whole total goes to the first part.
     */
    fun allocate(total: Long, weights: LongArray): LongArray {
        val n = weights.size
        val out = LongArray(n)
        if (n == 0) {
            require(total == 0L) { "cannot allocate $total over zero parts" }
            return out
        }
        if (total == 0L) return out
        var sum = 0L
        for (w in weights) {
            require(w >= 0L) { "negative weight $w" }
            sum = Checked.add(sum, w)
        }
        if (sum == 0L) {
            out[0] = total
            return out
        }
        val negative = total < 0
        val t = if (negative) Checked.neg(total) else total
        val remainders = LongArray(n)
        var given = 0L
        for (i in 0 until n) {
            val p = Checked.mul(t, weights[i])
            out[i] = p / sum
            remainders[i] = p % sum
            given += out[i]
        }
        var left = t - given // always < n
        while (left > 0) {
            var best = 0
            for (i in 1 until n) if (remainders[i] > remainders[best]) best = i
            out[best] += 1
            remainders[best] = -1 // at most one extra unit per part
            left--
        }
        if (negative) for (i in 0 until n) out[i] = -out[i]
        return out
    }
}
