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
 *
 * The rounding is cumulative (2026-10 review): once R of the line's Q have come back, the
 * returns so far hold round(amount × R / Q) of every amount, and a part is the step from what
 * earlier parts gave to that. A part therefore never takes more than is left of any amount.
 * (Rounding each part on its own let a part refund more than was left of the line, and the
 * last return then went negative: it charged the customer.)
 */
object Refunds {

    fun part(src: RefundSource, qty: Long): RefundPart {
        require(qty > 0L) { "refund qty must be > 0" }
        require(qty <= src.remainingQty) { "refund qty ${qty} exceeds remaining ${src.remainingQty}" }
        val done = src.refunded
        // The cost share follows the quantities alone (2026-10 review): a refund whose goods do not
        // go back on the shelf stores no cost (their cost stays in cost of goods), so what earlier
        // refunds stored says nothing about how much of the line's cost they covered.
        val costBefore = Rounding.mulDivHalfUp(src.cost, src.refundedQty, src.qty)
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
                cost = src.cost - costBefore,
            )
        }
        val upTo = Checked.add(src.refundedQty, qty)
        // What the returns so far plus this one hold of [amount]: never less than earlier parts
        // gave ([already]), never more than the line had.
        fun upToNow(amount: Long, already: Long): Long =
            Rounding.mulDivHalfUp(amount, upTo, src.qty).coerceIn(minOf(already, amount), maxOf(already, amount))

        val doneDiscount = done?.discount ?: 0L
        val doneBill = done?.billDiscount ?: 0L
        val doneNet = done?.net ?: 0L
        // The money first, then gross, line discount and bill discount around it, so the part adds
        // up like the line (gross − discounts = net) with every amount within what is left of it.
        val net = upToNow(src.net, doneNet)
        val discLo = minOf(doneDiscount, src.discount)
        val discHi = maxOf(doneDiscount, src.discount)
        val billLo = minOf(doneBill, src.billDiscount)
        val billHi = maxOf(doneBill, src.billDiscount)
        val gross = Rounding.mulDivHalfUp(src.gross, upTo, src.qty)
            .coerceIn(net + discLo + billLo, net + discHi + billHi)
        // The line discount (printed per line) keeps its own share; the bill discount takes the
        // odd sen, unless that would leave its range.
        var discount = upToNow(src.discount, doneDiscount)
        var bill = gross - net - discount
        if (bill < billLo) {
            bill = billLo
            discount = gross - net - billLo
        } else if (bill > billHi) {
            bill = billHi
            discount = gross - net - billHi
        }
        val partDiscount = discount - doneDiscount
        val partBill = bill - doneBill
        val partNet = net - doneNet
        // Gross from its parts: the part always adds up like the line, whatever earlier parts held.
        return RefundPart(
            lineId = src.lineId,
            qty = qty,
            baseQty = upToNow(src.baseQty, done?.baseQty ?: 0L) - (done?.baseQty ?: 0L),
            gross = partDiscount + partBill + partNet,
            discount = partDiscount,
            billDiscount = partBill,
            net = partNet,
            tax = upToNow(src.tax, done?.tax ?: 0L) - (done?.tax ?: 0L),
            cost = Rounding.mulDivHalfUp(src.cost, upTo, src.qty) - costBefore,
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
