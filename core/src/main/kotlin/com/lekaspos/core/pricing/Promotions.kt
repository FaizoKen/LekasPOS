package com.lekaspos.core.pricing

import com.lekaspos.core.model.PromoKind
import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding

/**
 * A promotion on one or more products (references/money.md §11, Phase 8). The products share
 * the deal ("any 3 Milo flavours for RM10").
 */
data class Promotion(
    val id: Long,
    val name: String,
    val kind: Int,
    /** MULTI_PRICE: units per group (1: a special price for each unit). BUY_GET_FREE: units bought per set. */
    val buyQty: Int,
    /** BUY_GET_FREE: units free per set. */
    val freeQty: Int = 0,
    /** MULTI_PRICE: price of one group, minor units. */
    val groupPrice: Long = 0L,
    val productIds: Set<Long>,
) {
    init {
        when (kind) {
            PromoKind.MULTI_PRICE -> {
                // 1: a special price with dates ("now RM3.99"), 2026-10. Older tills ignore it (their
                // check refused it: such a promotion was skipped, never applied wrongly).
                require(buyQty >= 1) { "a multi-buy needs at least 1 unit" }
                require(groupPrice >= 0L) { "group price must be >= 0" }
            }
            PromoKind.BUY_GET_FREE -> require(buyQty >= 1 && freeQty >= 1) { "buy X get Y needs X >= 1 and Y >= 1" }
            else -> throw IllegalArgumentException("unknown promotion kind $kind")
        }
        require(buyQty <= MAX_UNITS && freeQty <= MAX_UNITS) { "promotion quantities are too large" }
    }

    private companion object {
        const val MAX_UNITS = 1_000
    }
}

/** A cart line as promotions see it; [eligible] = whole units of an unchanged piece item. */
data class PromoLine(val productId: Long?, val qty: Long, val unitPrice: Long, val eligible: Boolean)

/** A promotion that applied to a line and the amount it took off that line. */
data class AppliedPromo(val promotionId: Long, val name: String, val discount: Long)

/**
 * Applies promotions to a bill. Pure and deterministic:
 *  - only [PromoLine.eligible] lines count, in whole units (qty 1000 = one unit);
 *  - a product in several promotions takes the one with the lowest id;
 *  - units are grouped dearest first (the customer gets the best deal); for "buy X get Y" the
 *    cheapest units of each set are the free ones, and their price is the set's saving;
 *  - a group never costs more than its regular price (the saving is never negative);
 *  - a group's saving is shared over its units by price (largest remainder), so every line's
 *    saving is at most its own gross.
 */
object Promotions {

    fun apply(lines: List<PromoLine>, promotions: List<Promotion>): List<AppliedPromo?> {
        val out = arrayOfNulls<AppliedPromo>(lines.size)
        if (promotions.isEmpty() || lines.isEmpty()) return out.toList()
        val byId = promotions.sortedBy { it.id }
        val promoOf = HashMap<Long, Promotion>()
        for (p in byId) for (id in p.productIds) if (id !in promoOf) promoOf[id] = p
        val discount = LongArray(lines.size)
        for (p in byId) {
            val runs = ArrayList<Run>()
            for ((i, l) in lines.withIndex()) {
                val pid = l.productId ?: continue
                if (!l.eligible || promoOf[pid] !== p || l.qty <= 0L || l.qty % 1000L != 0L) continue
                runs.add(Run(i, l.unitPrice, l.qty / 1000L))
            }
            if (runs.isEmpty()) continue
            runs.sortWith(compareByDescending<Run> { it.price }.thenBy { it.line })
            when (p.kind) {
                PromoKind.MULTI_PRICE -> multiPrice(runs, p.buyQty.toLong(), p.groupPrice, discount)
                PromoKind.BUY_GET_FREE -> buyGetFree(runs, p.buyQty.toLong(), p.freeQty.toLong(), discount)
            }
            for (r in runs) if (discount[r.line] > 0L) out[r.line] = AppliedPromo(p.id, p.name, discount[r.line])
        }
        return out.toList()
    }

    private class Run(val line: Int, val price: Long, val count: Long)

    /** Walks the units of [runs] in order, [size] at a time. */
    private class Cursor(private val runs: List<Run>) {
        private var run = 0
        private var used = 0L
        val left: Long get() = if (run >= runs.size) 0L else runs[run].count - used
        val current: Run get() = runs[run]

        fun skip(units: Long) {
            used += units
            if (used == runs[run].count) {
                run++
                used = 0L
            }
        }

        /** The next [size] units as (run, count) pieces, possibly across runs. */
        fun take(size: Long): List<Pair<Run, Long>> {
            val parts = ArrayList<Pair<Run, Long>>(2)
            var need = size
            while (need > 0L) {
                val n = minOf(need, left)
                parts.add(runs[run] to n)
                skip(n)
                need -= n
            }
            return parts
        }
    }

    private fun total(runs: List<Run>): Long = runs.fold(0L) { acc, r -> Checked.add(acc, r.count) }

    private fun multiPrice(runs: List<Run>, n: Long, price: Long, discount: LongArray) {
        var groups = total(runs) / n
        val c = Cursor(runs)
        while (groups > 0L) {
            if (c.left >= n) { // whole groups within one line: same saving each
                val r = c.current
                val k = minOf(c.left / n, groups)
                val each = Checked.mul(n, r.price) - price
                if (each > 0L) discount[r.line] = Checked.add(discount[r.line], Checked.mul(k, each))
                c.skip(k * n)
                groups -= k
                continue
            }
            val parts = c.take(n) // a group that spans lines
            val weights = LongArray(parts.size) { Checked.mul(parts[it].first.price, parts[it].second) }
            val regular = weights.fold(0L) { acc, w -> Checked.add(acc, w) }
            val saving = regular - price
            if (saving > 0L) {
                val shares = Rounding.allocate(saving, weights)
                for ((i, part) in parts.withIndex()) {
                    val line = part.first.line
                    discount[line] = Checked.add(discount[line], shares[i])
                }
            }
            groups--
        }
    }

    private fun buyGetFree(runs: List<Run>, buy: Long, free: Long, discount: LongArray) {
        val size = buy + free
        var sets = total(runs) / size
        val c = Cursor(runs)
        while (sets > 0L) {
            if (c.left >= size) { // whole sets within one line: `free` of its units are free per set
                val r = c.current
                val k = minOf(c.left / size, sets)
                discount[r.line] = Checked.add(discount[r.line], Checked.mul(Checked.mul(k, free), r.price))
                c.skip(k * size)
                sets -= k
                continue
            }
            // A set across lines, dearest first: the last `free` units are the cheapest. Their price is
            // the set's saving, shared over the set's lines by price like a multi-buy: kept whole on
            // the free line, a return of the paid lines refunded their full price and the customer
            // kept the free item for nothing (2026-10 review).
            val parts = c.take(size)
            var paid = buy
            var saving = 0L
            for ((r, count) in parts) {
                val charged = minOf(paid, count)
                paid -= charged
                saving = Checked.add(saving, Checked.mul(count - charged, r.price))
            }
            if (saving > 0L) {
                val weights = LongArray(parts.size) { Checked.mul(parts[it].first.price, parts[it].second) }
                val shares = Rounding.allocate(saving, weights)
                for ((i, part) in parts.withIndex()) {
                    val line = part.first.line
                    discount[line] = Checked.add(discount[line], shares[i])
                }
            }
            sets--
        }
    }
}
