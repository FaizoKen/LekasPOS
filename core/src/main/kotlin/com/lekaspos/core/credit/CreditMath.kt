package com.lekaspos.core.credit

import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.money.Checked

/**
 * Customer credit ("buy now, pay later", D-039). A customer's balance is what they owe:
 * the sum over their `credit_entry` events of [delta]. A CHARGE with a negative amount
 * reverses an earlier charge (refund or void of a credit sale).
 */
object CreditMath {

    fun delta(kind: Int, amount: Long): Long = when (kind) {
        CreditKind.CHARGE, CreditKind.ADJUST -> amount
        CreditKind.PAYMENT -> -amount
        else -> throw IllegalArgumentException("unknown credit kind $kind")
    }

    /** True when charging [charge] more takes [balance] over [limit]; a limit of 0 means no limit. */
    fun overLimit(balance: Long, charge: Long, limit: Long): Boolean =
        limit > 0L && charge > 0L && Checked.add(balance, charge) > limit

    /** Credit still available, or null when the customer has no limit. */
    fun available(balance: Long, limit: Long): Long? = if (limit <= 0L) null else Checked.sub(limit, balance).coerceAtLeast(0L)
}
