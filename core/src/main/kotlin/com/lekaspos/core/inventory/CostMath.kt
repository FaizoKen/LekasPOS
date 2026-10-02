package com.lekaspos.core.inventory

import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding

/**
 * Stock values and costing (D-033): a product's cost is the moving weighted average of what is
 * on hand and what was received. Quantities are milli-units of the product's base unit, costs
 * are minor units per base unit, totals are minor units.
 */
object CostMath {

    /** Value of [qty] units at [unitCost] each, half-up. */
    fun lineTotal(qty: Long, unitCost: Long): Long = Rounding.mulDivHalfUp(unitCost, qty, 1000L)

    /** Cost per unit implied by paying [total] for [qty] units, half-up. */
    fun unitCost(total: Long, qty: Long): Long {
        require(qty > 0L) { "qty must be > 0" }
        return Rounding.mulDivHalfUp(total, 1000L, qty)
    }

    /**
     * Average unit cost after receiving [addQty] units for [addTotal] while [onHand] units
     * costing [onHandCost] each are in stock. Stock at or below zero has nothing to average
     * with, so the received cost is used as it is; so does stock whose cost was never entered
     * (cost 0: products imported or added without a cost — averaging with it halved the cost of
     * the first delivery and overstated profit for months, 2026-10 review). Only one rounding.
     */
    fun movingAverage(onHand: Long, onHandCost: Long, addQty: Long, addTotal: Long): Long {
        require(addQty > 0L) { "received qty must be > 0" }
        require(addTotal >= 0L && onHandCost >= 0L) { "costs must be >= 0" }
        if (onHand <= 0L || onHandCost == 0L) return unitCost(addTotal, addQty)
        val value = Checked.add(Checked.mul(onHand, onHandCost), Checked.mul(addTotal, 1000L))
        return Rounding.roundHalfUp(value, Checked.add(onHand, addQty))
    }

    /** Value of a counting difference (counted − expected) at [unitCost]; negative = loss. */
    fun varianceValue(counted: Long, expected: Long, unitCost: Long): Long =
        lineTotal(Checked.sub(counted, expected), unitCost)
}
