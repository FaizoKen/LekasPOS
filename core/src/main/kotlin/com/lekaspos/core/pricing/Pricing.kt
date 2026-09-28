package com.lekaspos.core.pricing

import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding

/** A discount on a line or on the whole bill. */
sealed class Discount {
    object None : Discount()

    /** Fixed amount in minor units (capped at the discounted base). */
    data class Amount(val minor: Long) : Discount() {
        init {
            require(minor >= 0L) { "discount amount must be >= 0" }
        }
    }

    /** Percentage in basis points (10% = 1000). */
    data class Percent(val bp: Int) : Discount() {
        init {
            require(bp in 0..10_000) { "discount percent must be 0..100%" }
        }
    }
}

/** One cart line as the pricing engine needs it. */
data class PriceLine(
    /** Quantity in milli-units of the selling unit, > 0. */
    val qty: Long,
    /** Price per selling unit in minor units, >= 0. */
    val unitPrice: Long,
    /** Price-embedded scale label: the gross is exactly this amount. */
    val fixedGross: Long? = null,
    val discount: Discount = Discount.None,
    /** Tax rate id (null = not taxed) and its rate in basis points. */
    val taxRateId: Long? = null,
    val taxBp: Int = 0,
) {
    init {
        require(qty > 0L) { "qty must be > 0" }
        require(unitPrice >= 0L) { "unit price must be >= 0" }
        require(fixedGross == null || fixedGross >= 0L) { "fixed gross must be >= 0" }
        require(taxBp in 0..100_000) { "tax rate out of range" }
        require(taxRateId != null || taxBp == 0) { "tax bp without a tax rate" }
    }
}

data class PricedLine(
    val gross: Long,
    val lineDiscount: Long,
    val billDiscount: Long,
    /** gross − lineDiscount − billDiscount (tax included when prices include tax). */
    val net: Long,
    val tax: Long,
)

data class TaxGroup(val taxRateId: Long, val bp: Int, val base: Long, val tax: Long)

data class PricedCart(
    val lines: List<PricedLine>,
    /** Σ gross. */
    val subtotal: Long,
    val lineDiscounts: Long,
    val billDiscount: Long,
    /** Σ net. */
    val net: Long,
    val tax: Long,
    val taxGroups: List<TaxGroup>,
    /** Amount due before cash rounding. */
    val total: Long,
    val pricesIncludeTax: Boolean,
) {
    val discount: Long get() = lineDiscounts + billDiscount

    companion object {
        val EMPTY = PricedCart(emptyList(), 0, 0, 0, 0, 0, emptyList(), 0, true)
    }
}

/** Implements references/money.md §3. Pure and deterministic. */
object PricingEngine {

    fun lineGross(unitPrice: Long, qty: Long): Long = Rounding.mulDivHalfUp(unitPrice, qty, 1000L)

    /** Discount amount for [base] (>= 0), capped at [base]. */
    fun discountAmount(base: Long, discount: Discount): Long = when (discount) {
        Discount.None -> 0L
        is Discount.Amount -> minOf(discount.minor, base)
        is Discount.Percent -> minOf(Rounding.mulDivHalfUp(base, discount.bp.toLong(), 10_000L), base)
    }

    fun taxExclusive(base: Long, bp: Int): Long = Rounding.mulDivHalfUp(base, bp.toLong(), 10_000L)

    fun taxInclusive(base: Long, bp: Int): Long =
        Rounding.mulDivHalfUp(base, bp.toLong(), 10_000L + bp.toLong())

    fun price(
        lines: List<PriceLine>,
        billDiscount: Discount = Discount.None,
        pricesIncludeTax: Boolean,
    ): PricedCart {
        if (lines.isEmpty()) return PricedCart.EMPTY.copy(pricesIncludeTax = pricesIncludeTax)
        val n = lines.size
        val gross = LongArray(n)
        val lineDisc = LongArray(n)
        val afterLine = LongArray(n)
        var subtotal = 0L
        var lineDiscounts = 0L
        var billBase = 0L
        for (i in 0 until n) {
            val l = lines[i]
            gross[i] = l.fixedGross ?: lineGross(l.unitPrice, l.qty)
            lineDisc[i] = discountAmount(gross[i], l.discount)
            afterLine[i] = gross[i] - lineDisc[i]
            subtotal = Checked.add(subtotal, gross[i])
            lineDiscounts = Checked.add(lineDiscounts, lineDisc[i])
            billBase = Checked.add(billBase, afterLine[i])
        }
        val billDisc = discountAmount(billBase, billDiscount)
        val billAlloc = Rounding.allocate(billDisc, afterLine)
        val net = LongArray(n) { afterLine[it] - billAlloc[it] }
        var netTotal = 0L
        for (v in net) netTotal = Checked.add(netTotal, v)

        // Tax per rate group, then back to lines by largest remainder.
        val lineTax = LongArray(n)
        val groups = ArrayList<TaxGroup>(2)
        val seen = LinkedHashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            val id = lines[i].taxRateId ?: continue
            if (lines[i].taxBp == 0) continue
            seen.getOrPut(id) { ArrayList() }.add(i)
        }
        var taxTotal = 0L
        for ((rateId, idx) in seen) {
            val bp = lines[idx[0]].taxBp
            require(idx.all { lines[it].taxBp == bp }) { "tax rate $rateId has mixed percentages" }
            var base = 0L
            for (i in idx) base = Checked.add(base, net[i])
            val tax = if (pricesIncludeTax) taxInclusive(base, bp) else taxExclusive(base, bp)
            val alloc = Rounding.allocate(tax, LongArray(idx.size) { maxOf(net[idx[it]], 0L) })
            for (k in idx.indices) lineTax[idx[k]] = alloc[k]
            groups.add(TaxGroup(rateId, bp, base, tax))
            taxTotal = Checked.add(taxTotal, tax)
        }

        val priced = List(n) { PricedLine(gross[it], lineDisc[it], billAlloc[it], net[it], lineTax[it]) }
        val total = if (pricesIncludeTax) netTotal else Checked.add(netTotal, taxTotal)
        return PricedCart(
            lines = priced,
            subtotal = subtotal,
            lineDiscounts = lineDiscounts,
            billDiscount = billDisc,
            net = netTotal,
            tax = taxTotal,
            taxGroups = groups,
            total = total,
            pricesIncludeTax = pricesIncludeTax,
        )
    }
}
