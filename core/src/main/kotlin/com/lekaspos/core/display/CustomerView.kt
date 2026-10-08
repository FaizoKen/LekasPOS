package com.lekaspos.core.display

import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.pricing.PricedCart

/** One line of the bill as the customer sees it: name, how many at what price, what it comes to. */
data class CustomerLine(
    val key: Long,
    val productId: Long?,
    val categoryId: Long?,
    val name: String,
    val qty: Long,
    val unit: String?,
    val weighed: Boolean,
    val unitPrice: Long,
    /** A label price (a weighed item with its price on the label): no "qty × price". */
    val fixed: Boolean,
    /** Price less the line's own discount (and promotion). */
    val amount: Long,
)

/**
 * What the customer screen shows (D-069), from the till's state: a welcome between customers, the bill as it
 * is scanned (the item just added picked out), the total to pay while the payment is open, and the change with
 * a thank-you for a while after the sale. Pure: the screen only draws it.
 */
sealed class CustomerView {
    /** No bill: the shop's name and a welcome (also while the till is locked). */
    object Welcome : CustomerView()

    data class Bill(
        val lines: List<CustomerLine>,
        /** The line added or changed last (shown large, with its picture). */
        val last: CustomerLine?,
        val items: Long,
        val discount: Long,
        val total: Long,
        /** The payment is open: "Total to pay". */
        val paying: Boolean,
    ) : CustomerView()

    data class Thanks(val total: Long, val received: Long, val change: Long) : CustomerView()

    companion object {
        /** How long the change and the thank-you stay after a sale, unless the next customer's first item comes. */
        const val THANKS_MS = 30_000L

        /**
         * [items] and [priced]: the open bill; [lastKey]: its line added or changed last; [paying]: the payment is
         * open; [locked]: nobody is signed in; [done]: the last sale (total, received, change, when), if any.
         */
        fun of(
            items: List<CartItem>,
            priced: PricedCart,
            lastKey: Long,
            paying: Boolean,
            locked: Boolean,
            done: Done?,
            now: Long,
        ): CustomerView {
            if (items.isEmpty()) {
                if (done != null && !locked && now - done.at in 0L until THANKS_MS) return Thanks(done.total, done.received, done.change)
                return Welcome
            }
            if (locked) return Welcome // a bill waiting for whoever signs in is nobody's to see
            val lines = items.mapIndexed { i, it ->
                val p = priced.lines.getOrNull(i)
                CustomerLine(
                    key = it.key, productId = it.productId, categoryId = it.categoryId, name = it.name, qty = it.qty, unit = it.unit,
                    weighed = it.sellMode == SellMode.WEIGHT, unitPrice = it.unitPrice, fixed = it.fixedGross != null,
                    amount = if (p != null) p.gross - p.lineDiscount else 0L,
                )
            }
            var count = 0L
            for (it in items) count += if (it.sellMode == SellMode.WEIGHT || it.fixedGross != null) 1L else maxOf(1L, it.qty / 1000L)
            return Bill(
                lines = lines,
                last = lines.firstOrNull { it.key == lastKey } ?: lines.lastOrNull(),
                items = count,
                discount = priced.billDiscount,
                total = priced.total,
                paying = paying,
            )
        }
    }

    /** The last sale as the customer screen needs it. */
    data class Done(val total: Long, val received: Long, val change: Long, val at: Long)
}
