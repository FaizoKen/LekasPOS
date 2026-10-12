package com.lekaspos.core.pricing

import com.lekaspos.core.model.PromoKind
import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding

/** One price of a multi-buy: [qty] units for [price] minor units ("3 for RM4.00"). */
data class PriceTier(val qty: Int, val price: Long)

/**
 * The text form of a multi-buy's further prices (`promotion_price.prices`, schema v10, D-073): "3:400,5:600".
 * [decode] drops parts it cannot read; [Promotion] checks what is left.
 */
object PriceTiers {
    fun encode(tiers: List<PriceTier>): String = tiers.joinToString(",") { "${it.qty}:${it.price}" }

    fun decode(text: String?): List<PriceTier> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(',').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val qty = part.substring(0, colon).trim().toIntOrNull()
            val price = part.substring(colon + 1).trim().toLongOrNull()
            if (qty == null || price == null) null else PriceTier(qty, price)
        }
    }
}

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
    /**
     * MULTI_PRICE: prices for other quantities ("2 for RM3" with "3 for RM4"; 1.15.0, D-073). The bill
     * takes the mix of all the prices that costs the customer least.
     */
    val morePrices: List<PriceTier> = emptyList(),
) {
    init {
        when (kind) {
            PromoKind.MULTI_PRICE -> {
                // 1: a special price with dates ("now RM3.99"), 2026-10. Older tills ignore it (their
                // check refused it: such a promotion was skipped, never applied wrongly).
                require(buyQty >= 1) { "a multi-buy needs at least 1 unit" }
                require(groupPrice >= 0L) { "group price must be >= 0" }
                require(morePrices.size <= MAX_MORE_PRICES) { "too many prices" }
                for (t in morePrices) require(t.qty in 1..MAX_UNITS && t.price >= 0L) { "a price needs 1 to $MAX_UNITS units, amount >= 0" }
                require(morePrices.map { it.qty }.plus(buyQty).toSet().size == morePrices.size + 1) { "one price per quantity" }
            }
            PromoKind.BUY_GET_FREE -> {
                require(buyQty >= 1 && freeQty >= 1) { "buy X get Y needs X >= 1 and Y >= 1" }
                require(morePrices.isEmpty()) { "buy X get Y has one deal" }
            }
            else -> throw IllegalArgumentException("unknown promotion kind $kind")
        }
        require(buyQty <= MAX_UNITS && freeQty <= MAX_UNITS) { "promotion quantities are too large" }
    }

    /** MULTI_PRICE: every price of the deal, fewest units first. */
    val prices: List<PriceTier>
        get() = (morePrices + PriceTier(buyQty, groupPrice)).sortedBy { it.qty }

    companion object {
        const val MAX_UNITS = 1_000

        /** Prices besides the first one: "1 for RM2, 2 for RM3, 3 for RM4 … 6 for RM7" fits. */
        const val MAX_MORE_PRICES = 5
    }
}

/** A cart line as promotions see it; [eligible] = whole units of an unchanged piece item. */
data class PromoLine(val productId: Long?, val qty: Long, val unitPrice: Long, val eligible: Boolean)

/** A promotion that applied to a line and the amount it took off that line. */
data class AppliedPromo(val promotionId: Long, val name: String, val discount: Long)

/**
 * Applies promotions to a bill. Pure and deterministic:
 *  - only [PromoLine.eligible] lines count, in whole units (qty 1000 = one unit);
 *  - multi-buys on exactly the same products make one price ladder, also when they are separate
 *    promotions ("2 for RM3" and "3 for RM4"; D-073); otherwise a product in several promotions takes
 *    the one with the lowest id;
 *  - units are grouped dearest first (the customer gets the best deal); a multi-buy with several
 *    prices takes the mix that costs least ([plan]); for "buy X get Y" the cheapest units of each set
 *    are the free ones, and their price is the set's saving;
 *  - a group never costs more than its regular price (the saving is never negative);
 *  - a group's saving is shared over its units by price (largest remainder), so every line's
 *    saving is at most its own gross.
 */
object Promotions {

    /**
     * Above this many units of one deal on a bill, the cheapest price per unit takes the dearest units
     * first and only the rest is planned unit by unit (memory and time stay small for any bill).
     */
    private const val MAX_PLANNED_UNITS = 10_000L

    private const val NONE = Long.MAX_VALUE

    fun apply(lines: List<PromoLine>, promotions: List<Promotion>): List<AppliedPromo?> {
        val out = arrayOfNulls<AppliedPromo>(lines.size)
        if (promotions.isEmpty() || lines.isEmpty()) return out.toList()
        val deals = deals(promotions)
        val dealOf = HashMap<Long, Deal>()
        for (d in deals) for (id in d.lead.productIds) if (id !in dealOf) dealOf[id] = d
        val discount = LongArray(lines.size)
        val label = arrayOfNulls<Promotion>(lines.size)
        for (d in deals) {
            val runs = ArrayList<Run>()
            for ((i, l) in lines.withIndex()) {
                val pid = l.productId ?: continue
                if (!l.eligible || dealOf[pid] !== d || l.qty <= 0L || l.qty % 1000L != 0L) continue
                runs.add(Run(i, l.unitPrice, l.qty / 1000L))
            }
            if (runs.isEmpty()) continue
            runs.sortWith(compareByDescending<Run> { it.price }.thenBy { it.line })
            when (d.lead.kind) {
                PromoKind.MULTI_PRICE -> ladder(runs, d.tiers(), discount, label)
                PromoKind.BUY_GET_FREE -> {
                    buyGetFree(runs, d.lead.buyQty.toLong(), d.lead.freeQty.toLong(), discount)
                    for (r in runs) if (discount[r.line] > 0L) label[r.line] = d.lead
                }
            }
        }
        for (i in lines.indices) {
            val p = label[i] ?: continue
            if (discount[i] > 0L) out[i] = AppliedPromo(p.id, p.name, discount[i])
        }
        return out.toList()
    }

    /** One deal on the bill: a promotion, and for a multi-buy the others on exactly its products. */
    private class Deal(val lead: Promotion) {
        val members = arrayListOf(lead)

        fun tiers(): List<Tier> = members.flatMap { m -> m.prices.map { Tier(it.qty.toLong(), it.price, m) } }
    }

    /** One price of a ladder and the promotion it comes from (its name goes on the receipt). */
    private class Tier(val qty: Long, val price: Long, val promo: Promotion)

    private fun deals(promotions: List<Promotion>): List<Deal> {
        val out = ArrayList<Deal>(promotions.size)
        val ladders = HashMap<Set<Long>, Deal>()
        for (p in promotions.sortedBy { it.id }) {
            if (p.kind != PromoKind.MULTI_PRICE) {
                out.add(Deal(p))
                continue
            }
            val same = ladders[p.productIds]
            if (same != null) {
                same.members.add(p)
                continue
            }
            val d = Deal(p)
            ladders[p.productIds] = d
            out.add(d)
        }
        return out
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

    /** Adds a positive [amount] to [line]'s saving; the line's receipt name is the first deal that saved on it. */
    private fun save(discount: LongArray, label: Array<Promotion?>, line: Int, amount: Long, promo: Promotion) {
        if (amount <= 0L) return
        discount[line] = Checked.add(discount[line], amount)
        if (label[line] == null) label[line] = promo
    }

    /** Shares a group's [saving] over its [parts] by price (largest remainder). */
    private fun share(parts: List<Pair<Run, Long>>, saving: Long, discount: LongArray, label: Array<Promotion?>, promo: Promotion) {
        if (saving <= 0L) return
        val weights = LongArray(parts.size) { Checked.mul(parts[it].first.price, parts[it].second) }
        val shares = Rounding.allocate(saving, weights)
        for ((i, part) in parts.withIndex()) save(discount, label, part.first.line, shares[i], promo)
    }

    /** A multi-buy with one or more prices: groups as [plan] says, biggest groups on the dearest units. */
    private fun ladder(runs: List<Run>, tiers: List<Tier>, discount: LongArray, label: Array<Promotion?>) {
        // Bigger groups first, then the lower price, then the older promotion: every till plans the same.
        val order = tiers.sortedWith(compareByDescending<Tier> { it.qty }.thenBy { it.price }.thenBy { it.promo.id })
        val plan = plan(runs, order)
        val c = Cursor(runs)
        for ((t, tier) in order.withIndex()) {
            val n = tier.qty
            var groups = plan[t]
            while (groups > 0L) {
                if (c.left >= n) { // whole groups within one line: same saving each
                    val r = c.current
                    val k = minOf(c.left / n, groups)
                    val each = Checked.mul(n, r.price) - tier.price
                    if (each > 0L) save(discount, label, r.line, Checked.mul(k, each), tier.promo)
                    c.skip(k * n)
                    groups -= k
                    continue
                }
                val parts = c.take(n) // a group that spans lines
                val regular = parts.fold(0L) { acc, p -> Checked.add(acc, Checked.mul(p.first.price, p.second)) }
                share(parts, regular - tier.price, discount, label, tier.promo)
                groups--
            }
        }
    }

    /**
     * How many groups of each price in [order] (D-073). With the units dearest first, a plan groups the
     * first K of them and the rest pay their shelf price, so the bill is cost[K] + the shelf price of
     * the rest, where cost[K] is the cheapest way to make exactly K units out of the prices (each price
     * as often as wanted). The plan takes the K that saves most (the fewest units when two save the
     * same): 3 units with "1 for RM2, 2 for RM3, 3 for RM4" cost RM4; 5 cost 3 + 2 = RM7.
     */
    private fun plan(runs: List<Run>, order: List<Tier>): LongArray {
        val plan = LongArray(order.size)
        val units = total(runs)
        var first = 0L // units grouped before the exact plan
        if (units > MAX_PLANNED_UNITS) {
            var best = 0
            for (t in 1 until order.size) {
                val a = order[t]
                val b = order[best]
                if (Checked.mul(a.price, b.qty) < Checked.mul(b.price, a.qty)) best = t
            }
            val q = order[best].qty
            plan[best] = (units - MAX_PLANNED_UNITS + q - 1) / q
            first = plan[best] * q
        }
        val n = (units - first).toInt()
        val cost = LongArray(n + 1) { NONE }
        val pick = IntArray(n + 1)
        cost[0] = 0L
        for (k in 1..n) {
            for ((t, tier) in order.withIndex()) {
                if (tier.qty > k) continue
                val before = cost[k - tier.qty.toInt()]
                if (before == NONE) continue
                val c = Checked.add(before, tier.price)
                if (c < cost[k]) {
                    cost[k] = c
                    pick[k] = t
                }
            }
        }
        var bestK = 0
        var bestSaving = 0L
        var k = 0
        var regular = 0L
        var passed = 0L
        for (r in runs) {
            val from = maxOf(passed, first)
            val to = minOf(passed + r.count, first + n)
            var u = from
            while (u < to) {
                k++
                regular = Checked.add(regular, r.price)
                if (cost[k] != NONE && regular - cost[k] > bestSaving) {
                    bestSaving = regular - cost[k]
                    bestK = k
                }
                u++
            }
            passed += r.count
            if (passed >= first + n) break
        }
        k = bestK
        while (k > 0) {
            val t = pick[k]
            plan[t]++
            k -= order[t].qty.toInt()
        }
        return plan
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
