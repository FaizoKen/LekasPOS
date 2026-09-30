package com.lekaspos.core.refund

import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding

/**
 * One line of the original sale and what earlier (non-voided) refunds already returned of it.
 * All values are positive: quantities in milli-units, amounts in minor units.
 */
data class RefundSource(
    val lineId: Long,
    val qty: Long,
    val baseQty: Long,
    val gross: Long,
    val discount: Long,
    val billDiscount: Long,
    val net: Long,
    val tax: Long,
    val cost: Long,
    val refunded: RefundPart? = null,
) {
    init {
        require(qty > 0L) { "original qty must be > 0" }
    }

    val refundedQty: Long get() = refunded?.qty ?: 0L
    val remainingQty: Long get() = qty - refundedQty
}

/** What is returned of one line: positive values (the refund document stores them negated). */
data class RefundPart(
    val lineId: Long,
    val qty: Long,
    val baseQty: Long,
    val gross: Long,
    val discount: Long,
    val billDiscount: Long,
    val net: Long,
    val tax: Long,
    val cost: Long,
)

/**
 * Refund amounts (references/money.md §6): a partial return refunds the original line's amounts
 * pro rata (half-up), and returning the last remaining quantity refunds exactly what is left,
 * so all refunds of a line always add up to what was paid — allocated discounts and tax
 * included — to the sen.
 */
object Refunds {

    fun part(src: RefundSource, qty: Long): RefundPart {
        require(qty > 0L) { "refund qty must be > 0" }
        require(qty <= src.remainingQty) { "refund qty ${qty} exceeds remaining ${src.remainingQty}" }
        val done = src.refunded
        if (qty == src.remainingQty) {
            return RefundPart(
                lineId = src.lineId,
                qty = qty,
                baseQty = src.baseQty - (done?.baseQty ?: 0L),
                gross = src.gross - (done?.gross ?: 0L),
                discount = src.discount - (done?.discount ?: 0L),
                billDiscount = src.billDiscount - (done?.billDiscount ?: 0L),
                net = src.net - (done?.net ?: 0L),
                tax = src.tax - (done?.tax ?: 0L),
                cost = src.cost - (done?.cost ?: 0L),
            )
        }
        fun share(amount: Long, already: Long): Long {
            val v = Rounding.mulDivHalfUp(amount, qty, src.qty)
            // never more than what is still left of the line (rounding of earlier parts)
            val left = amount - already
            return if (amount >= 0L) minOf(v, left) else maxOf(v, left)
        }
        val gross = share(src.gross, done?.gross ?: 0L)
        val billDiscount = share(src.billDiscount, done?.billDiscount ?: 0L)
        // The line discount follows from the others, so the part adds up like the line itself
        // (gross − discounts = net): rounding each on its own left the receipt 1 sen out.
        val discountLeft = src.discount - (done?.discount ?: 0L)
        val discount = (gross - billDiscount - share(src.net, done?.net ?: 0L))
            .coerceIn(minOf(0L, discountLeft), maxOf(0L, discountLeft))
        return RefundPart(
            lineId = src.lineId,
            qty = qty,
            baseQty = share(src.baseQty, done?.baseQty ?: 0L),
            gross = gross,
            discount = discount,
            billDiscount = billDiscount,
            net = gross - billDiscount - discount,
            tax = share(src.tax, done?.tax ?: 0L),
            cost = share(src.cost, done?.cost ?: 0L),
        )
    }

    /** Totals of a refund document (positive; negate when storing). */
    data class Totals(val subtotal: Long, val discount: Long, val tax: Long, val net: Long, val due: Long, val cost: Long)

    fun totals(parts: List<RefundPart>, pricesIncludeTax: Boolean): Totals {
        var gross = 0L
        var discount = 0L
        var tax = 0L
        var net = 0L
        var cost = 0L
        for (p in parts) {
            gross = Checked.add(gross, p.gross)
            discount = Checked.add(discount, Checked.add(p.discount, p.billDiscount))
            tax = Checked.add(tax, p.tax)
            net = Checked.add(net, p.net)
            cost = Checked.add(cost, p.cost)
        }
        val due = if (pricesIncludeTax) net else Checked.add(net, tax)
        return Totals(gross, discount, tax, net, due, cost)
    }

    /** Adds two parts of the same line (used to sum earlier refunds). */
    fun plus(a: RefundPart?, b: RefundPart): RefundPart = if (a == null) b else RefundPart(
        lineId = b.lineId,
        qty = Checked.add(a.qty, b.qty),
        baseQty = Checked.add(a.baseQty, b.baseQty),
        gross = Checked.add(a.gross, b.gross),
        discount = Checked.add(a.discount, b.discount),
        billDiscount = Checked.add(a.billDiscount, b.billDiscount),
        net = Checked.add(a.net, b.net),
        tax = Checked.add(a.tax, b.tax),
        cost = Checked.add(a.cost, b.cost),
    )
}
