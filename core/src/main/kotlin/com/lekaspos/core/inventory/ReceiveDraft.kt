package com.lekaspos.core.inventory

import com.lekaspos.core.money.Checked

/**
 * One line of a delivery being received. [qty] is milli-units of the product's base unit,
 * [unitCost] per base unit, [total] the line amount (what the supplier's invoice says; kept
 * as entered when the user types it, otherwise qty × unit cost).
 */
data class ReceiveLine(
    val key: Long,
    val productId: Long,
    val name: String,
    val unit: String?,
    val qty: Long,
    val unitCost: Long,
    val total: Long,
) {
    init {
        require(qty > 0L) { "qty must be > 0" }
        require(unitCost >= 0L && total >= 0L) { "costs must be >= 0" }
    }
}

/** A delivery being entered (immutable; every edit returns a new draft). */
data class ReceiveDraft(
    val supplierId: Long? = null,
    val refNo: String = "",
    val note: String = "",
    val lines: List<ReceiveLine> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    val total: Long get() = lines.fold(0L) { acc, l -> Checked.add(acc, l.total) }

    fun line(key: Long): ReceiveLine? = lines.firstOrNull { it.key == key }

    /**
     * Adds [qty] of a product at [unitCost]; a product already on the delivery grows instead
     * (keeping the unit cost that line has). Returns the new draft and the line's key.
     */
    fun add(key: Long, productId: Long, name: String, unit: String?, qty: Long, unitCost: Long): Pair<ReceiveDraft, Long> {
        val existing = lines.lastOrNull { it.productId == productId }
        if (existing != null) {
            val grown = Checked.add(existing.qty, qty)
            val next = existing.copy(qty = grown, total = CostMath.lineTotal(grown, existing.unitCost))
            return copy(lines = lines.map { if (it.key == existing.key) next else it }) to existing.key
        }
        require(lines.none { it.key == key }) { "duplicate line key $key" }
        val line = ReceiveLine(key, productId, name, unit, qty, unitCost, CostMath.lineTotal(qty, unitCost))
        return copy(lines = lines + line) to key
    }

    fun setQty(key: Long, qty: Long): ReceiveDraft = update(key) { it.copy(qty = qty, total = CostMath.lineTotal(qty, it.unitCost)) }

    fun setUnitCost(key: Long, unitCost: Long): ReceiveDraft =
        update(key) { it.copy(unitCost = unitCost, total = CostMath.lineTotal(it.qty, unitCost)) }

    /** The invoice's line amount: kept exactly; the unit cost follows from it. */
    fun setTotal(key: Long, total: Long): ReceiveDraft = update(key) { it.copy(total = total, unitCost = CostMath.unitCost(total, it.qty)) }

    fun remove(key: Long): ReceiveDraft = copy(lines = lines.filterNot { it.key == key })

    private inline fun update(key: Long, change: (ReceiveLine) -> ReceiveLine): ReceiveDraft {
        require(lines.any { it.key == key }) { "no line $key" }
        return copy(lines = lines.map { if (it.key == key) change(it) else it })
    }
}
