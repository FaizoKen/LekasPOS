package com.lekaspos.core.staff

import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.SaleKind

/**
 * "Void their own last sale" (`Perm.VOID_LAST`, 1.14.0, D-072): a cashier puts right the sale they have just made — the
 * wrong payment method, the wrong items, a customer who changed their mind at the counter — without a manager. Only
 * while it is plainly that: a sale (not a refund) made by them on this till, still its latest document, in the shift
 * still open, at most [WINDOW_MS] ago. Anything older or anyone else's still needs `Perm.VOID`.
 */
object VoidLast {
    const val WINDOW_MS: Long = 5L * 60_000L

    /** What the rule looks at: the sale's own fields and, for the till, its latest document and open shift. */
    data class Sale(val id: Long, val kind: Int, val staffId: Long?, val soldAt: Long, val shiftId: Long?)

    fun allowed(
        sale: Sale,
        staffId: Long,
        deviceNo: Int,
        latestOfTill: Long?,
        openShiftId: Long?,
        now: Long,
    ): Boolean {
        val age = now - sale.soldAt
        return sale.kind == SaleKind.SALE &&
            sale.staffId == staffId &&
            Ids.deviceOf(sale.id) == deviceNo &&
            latestOfTill == sale.id &&
            sale.shiftId == openShiftId &&
            age in 0L..WINDOW_MS // a clock set back before the sale: not "just made"
    }
}
