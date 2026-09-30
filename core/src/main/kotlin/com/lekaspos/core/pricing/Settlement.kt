package com.lekaspos.core.pricing

import com.lekaspos.core.money.Rounding

/**
 * Paying a bill: references/money.md §4–§5. Cash rounding applies only when cash settles the
 * final remainder; card/e-wallet/other tenders pay exact amounts and never produce change.
 */
object Settlement {

    /** What the customer must hand over in cash to settle [remaining]. */
    fun cashDue(remaining: Long, cashStep: Long): Long = Rounding.toStep(remaining, cashStep)

    sealed class Result {
        /** The bill is fully paid. [applied] is what this tender contributes to the total. */
        data class Settled(val applied: Long, val rounding: Long, val change: Long) : Result()

        /** Partly paid; [remaining] is still due. */
        data class Partial(val applied: Long, val remaining: Long) : Result()

        /** Tender refused, e.g. cash between the exact and the rounded amount. */
        data class Rejected(val reason: Reason, val minimum: Long) : Result()
    }

    enum class Reason { CASH_BELOW_ROUNDED_DUE, EXCEEDS_DUE, NOT_POSITIVE, NOTHING_DUE }

    /** Cash tender of [given] against [remaining] (both > 0 for sales). */
    fun cash(remaining: Long, given: Long, cashStep: Long): Result {
        if (remaining <= 0L) return Result.Rejected(Reason.NOTHING_DUE, 0L)
        val due = cashDue(remaining, cashStep)
        // A remainder that rounds to nothing in cash (e-wallet RM50.00 of RM50.02: 2 sen left) is
        // settled by cash of 0.00: the 2 sen are the rounding, and anything handed over comes back.
        if (due == 0L) return Result.Settled(applied = 0L, rounding = -remaining, change = given.coerceAtLeast(0L))
        if (given <= 0L) return Result.Rejected(Reason.NOT_POSITIVE, 0L)
        return when {
            given >= due -> Result.Settled(applied = due, rounding = due - remaining, change = given - due)
            given < remaining -> Result.Partial(applied = given, remaining = remaining - given)
            else -> Result.Rejected(Reason.CASH_BELOW_ROUNDED_DUE, due)
        }
    }

    /** Card / e-wallet / credit / other tender: exact, never more than what is due. */
    fun exact(remaining: Long, amount: Long): Result {
        if (remaining <= 0L) return Result.Rejected(Reason.NOTHING_DUE, 0L)
        if (amount <= 0L) return Result.Rejected(Reason.NOT_POSITIVE, 0L)
        if (amount > remaining) return Result.Rejected(Reason.EXCEEDS_DUE, remaining)
        return if (amount == remaining) {
            Result.Settled(applied = amount, rounding = 0L, change = 0L)
        } else {
            Result.Partial(applied = amount, remaining = remaining - amount)
        }
    }

    /** Cash refund of [refundDue] (> 0 = amount to give back), mirrored rounding. */
    fun cashRefund(refundDue: Long, cashStep: Long): Result.Settled {
        require(refundDue > 0L)
        val out = cashDue(refundDue, cashStep)
        return Result.Settled(applied = -out, rounding = -(out - refundDue), change = 0L)
    }
}
