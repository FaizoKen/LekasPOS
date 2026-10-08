package com.lekaspos.core.staff

import com.lekaspos.core.model.AuditAction

/** How many times something happened and the money it came to (minor units; 0 where it has none). */
data class Tally(val count: Long = 0L, val amount: Long = 0L) {
    operator fun plus(o: Tally) = Tally(count + o.count, amount + o.amount)
}

/** One group of the activity log: [count] entries of [action] whose amounts add up to [amount]. */
data class ActionTotal(val action: Int, val count: Long, val amount: Long)

/**
 * What the owner should look at in a person's or a shift's work at the till (D-067): every way cash
 * can go missing that leaves a trace in the activity log. None of it proves theft — a wrong item
 * scanned is taken off every day — but one cashier with far more than the others, or anything taken
 * off after the customer saw the total, is worth a question.
 */
data class Checks(
    /** Bills cleared with items on them. */
    val cleared: Tally = Tally(),
    /** Items taken off bills, or quantities lowered (the value taken off). */
    val removed: Tally = Tally(),
    /** Of [cleared] and [removed]: after the payment screen had shown the total. */
    val afterPay: Tally = Tally(),
    val voids: Tally = Tally(),
    val refunds: Tally = Tally(),
    /** Discounts and price changes at the till (counts only: their amounts are of mixed kinds). */
    val discounts: Long = 0L,
    /** The drawer opened without a sale. */
    val drawerOpens: Long = 0L,
    /** Receipt copies printed or shared. */
    val copies: Long = 0L,
    /** Cash taken out of the drawer (cash drops to the bank are not counted here). */
    val cashOut: Tally = Tally(),
    /** Stock written off or counted down by hand, at cost. */
    val writeOffs: Tally = Tally(),
    /** Sold on in someone else's shift without counting the drawer. */
    val continued: Long = 0L,
    /** Shifts opened with another amount than the last close left (amount: counted − left, summed; D-068). */
    val floatDiffs: Tally = Tally(),
) {
    /** Nothing to look at. */
    val isEmpty: Boolean get() = this == Checks()

    /** Signs that most often go with cash going missing: taken off after the total was shown, drawer opened without a sale. */
    val warning: Boolean get() = afterPay.count > 0L || drawerOpens > 0L || floatDiffs.amount < 0L

    companion object {
        /** Folds activity-log groups (of one person, one shift or one till) into [Checks]; other actions are ignored. */
        fun of(rows: Iterable<ActionTotal>): Checks {
            var c = Checks()
            for (r in rows) {
                val t = Tally(r.count, r.amount)
                c = when (r.action) {
                    AuditAction.BILL_CANCEL -> c.copy(cleared = c.cleared + t)
                    AuditAction.BILL_CANCEL_AFTER_PAY -> c.copy(cleared = c.cleared + t, afterPay = c.afterPay + t)
                    AuditAction.LINE_REMOVE -> c.copy(removed = c.removed + t)
                    AuditAction.LINE_REMOVE_AFTER_PAY -> c.copy(removed = c.removed + t, afterPay = c.afterPay + t)
                    AuditAction.SALE_VOID -> c.copy(voids = c.voids + t)
                    AuditAction.REFUND -> c.copy(refunds = c.refunds + t)
                    AuditAction.LINE_DISCOUNT, AuditAction.BILL_DISCOUNT, AuditAction.PRICE_OVERRIDE -> c.copy(discounts = c.discounts + r.count)
                    AuditAction.DRAWER_OPEN -> c.copy(drawerOpens = c.drawerOpens + r.count)
                    AuditAction.REPRINT -> c.copy(copies = c.copies + r.count)
                    AuditAction.CASH_OUT -> c.copy(cashOut = c.cashOut + t)
                    AuditAction.STOCK_WRITE_OFF -> c.copy(writeOffs = c.writeOffs + t)
                    AuditAction.SHIFT_CONTINUED -> c.copy(continued = c.continued + r.count)
                    AuditAction.FLOAT_DIFFERENCE -> c.copy(floatDiffs = c.floatDiffs + t)
                    else -> c
                }
            }
            return c
        }

        /** The actions [of] reads (the queries ask for these only). */
        val ACTIONS: List<Int> = listOf(
            AuditAction.BILL_CANCEL, AuditAction.BILL_CANCEL_AFTER_PAY, AuditAction.LINE_REMOVE, AuditAction.LINE_REMOVE_AFTER_PAY,
            AuditAction.SALE_VOID, AuditAction.REFUND, AuditAction.LINE_DISCOUNT, AuditAction.BILL_DISCOUNT,
            AuditAction.PRICE_OVERRIDE, AuditAction.DRAWER_OPEN, AuditAction.REPRINT, AuditAction.CASH_OUT,
            AuditAction.STOCK_WRITE_OFF, AuditAction.SHIFT_CONTINUED, AuditAction.FLOAT_DIFFERENCE,
        )
    }
}

/**
 * One person's line in the staff check for a period: their sales, the [checks] of what they did, and
 * the cash of the shifts they opened that were closed ([shifts], [overShort]: counted − expected, so
 * negative = cash missing; [shortShifts] of them were short).
 */
data class StaffCheck(
    val staffId: Long,
    val name: String?,
    val sales: Tally = Tally(),
    val checks: Checks = Checks(),
    val shifts: Long = 0L,
    val overShort: Long = 0L,
    val shortShifts: Long = 0L,
) {
    companion object {
        /**
         * Everyone with sales, checks or shifts in the period, the most to look at first: anything after
         * the total was shown, then cash missing from their shifts, then the value taken off bills.
         */
        fun rank(list: List<StaffCheck>): List<StaffCheck> = list.sortedWith(
            compareByDescending<StaffCheck> { it.checks.afterPay.amount }
                .thenBy { it.overShort.coerceAtMost(0L) }
                .thenByDescending { it.checks.removed.amount + it.checks.cleared.amount }
                .thenBy { it.name ?: "" },
        )
    }
}
