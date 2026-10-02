package com.lekaspos.core.inventory

import com.lekaspos.core.model.MovementKind
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class InventoryMathTest {

    @Test
    fun lineTotalsAndUnitCosts() {
        assertEquals(1_080L, CostMath.lineTotal(24_000L, 45L)) // 24 pcs × 0.45
        assertEquals(326L, CostMath.lineTotal(253L, 1_290L)) // 0.253 kg × 12.90
        assertEquals(45L, CostMath.unitCost(1_080L, 24_000L))
        assertEquals(333L, CostMath.unitCost(1_000L, 3_000L)) // 3.333 → 3.33
        assertFailsWith<IllegalArgumentException> { CostMath.unitCost(100L, 0L) }
    }

    @Test
    fun movingAverageWeighsStockOnHand() {
        // 10 on hand at 1.00, 30 received for 36.00 (1.20 each) → (10 + 36) / 40 = 1.15
        assertEquals(115L, CostMath.movingAverage(10_000L, 100L, 30_000L, 3_600L))
        // Nothing (or less than nothing) on hand: the new cost is the received cost.
        assertEquals(120L, CostMath.movingAverage(0L, 100L, 30_000L, 3_600L))
        assertEquals(120L, CostMath.movingAverage(-5_000L, 100L, 30_000L, 3_600L))
        // One rounding at the end: (1 × 0.99 + 2 × 1.00) / 3 = 0.99667 → 1.00
        assertEquals(100L, CostMath.movingAverage(1_000L, 99L, 2_000L, 200L))
        // Weighed goods: 0.5 kg at 8.00 + 1.5 kg for 13.50 → 21.50 / 2 kg = 10.75
        assertEquals(1_075L, CostMath.movingAverage(500L, 1_600L, 1_500L, 1_350L))
    }

    @Test
    fun stockWhoseCostWasNeverEnteredTakesTheDeliveryCost() {
        // 100 imported without a cost, then 100 received for 150.00: the cost is 1.50, not 0.75.
        assertEquals(150L, CostMath.movingAverage(100_000L, 0L, 100_000L, 15_000L))
        // Free goods on their own invoice line still lower a known cost.
        assertEquals(50L, CostMath.movingAverage(100_000L, 100L, 100_000L, 0L))
    }

    @Test
    fun varianceValue() {
        assertEquals(-250L, CostMath.varianceValue(counted = 8_000L, expected = 10_000L, unitCost = 125L))
        assertEquals(0L, CostMath.varianceValue(5_000L, 5_000L, 125L))
    }

    @Test
    fun adjustmentReasons() {
        assertEquals(-3_000L, AdjustReason.DAMAGED.delta(3_000L, removing = false)) // always out
        assertEquals(2_000L, AdjustReason.FOUND.delta(2_000L, removing = true)) // always in
        assertEquals(-1_000L, AdjustReason.CORRECTION.delta(1_000L, removing = true))
        assertEquals(1_000L, AdjustReason.CORRECTION.delta(1_000L, removing = false))
        assertEquals(MovementKind.WASTE, AdjustReason.EXPIRED.kind)
        assertEquals(MovementKind.RETURN_TO_SUPPLIER, AdjustReason.RETURNED.kind)
        assertEquals("theft", AdjustReason.THEFT.encode(null))
        assertEquals("damaged: box crushed", AdjustReason.DAMAGED.encode("  box\ncrushed "))
        assertEquals(AdjustReason.DAMAGED to "box crushed", AdjustReason.decode("damaged: box crushed"))
        assertEquals(AdjustReason.LOST to null, AdjustReason.decode("lost"))
        assertEquals(null to "spoilt: rats", AdjustReason.decode("spoilt: rats")) // unknown code kept as text
        assertEquals(null to null, AdjustReason.decode(null))
        assertFailsWith<IllegalArgumentException> { AdjustReason.LOST.delta(0L, true) }
    }

    @Test
    fun receiveDraftMergesAndKeepsInvoiceAmounts() {
        var (d, k1) = ReceiveDraft().add(1L, productId = 7L, name = "Milo", unit = "pcs", qty = 24_000L, unitCost = 45L)
        assertEquals(1_080L, d.total)
        val (d2, k2) = d.add(2L, productId = 7L, name = "Milo", unit = "pcs", qty = 12_000L, unitCost = 99L)
        assertEquals(k1, k2) // same product grows, keeps its unit cost
        assertEquals(36_000L, d2.line(k1)?.qty)
        assertEquals(1_620L, d2.total)
        d = d2.add(3L, productId = 8L, name = "Roti", unit = null, qty = 10_000L, unitCost = 150L).first
        assertEquals(3_120L, d.total)
        d = d.setTotal(k1, 1_700L) // invoice says 17.00 for 36 pcs
        assertEquals(1_700L, d.line(k1)?.total)
        assertEquals(47L, d.line(k1)?.unitCost) // 0.4722 → 0.47
        d = d.setQty(3L, 12_000L)
        assertEquals(1_800L, d.line(3L)?.total)
        d = d.setUnitCost(3L, 140L)
        assertEquals(1_680L, d.line(3L)?.total)
        d = d.remove(k1)
        assertEquals(listOf(8L), d.lines.map { it.productId })
        assertFailsWith<IllegalArgumentException> { d.setQty(99L, 1_000L) }
    }

    @Test
    fun timelineRunsBackwardsFromTheCurrentLevel() {
        val events = listOf(
            StockEvent.Change(hlc = 9L, id = 1L, delta = -2_000L), // sale of 2
            StockEvent.Change(hlc = 8L, id = 2L, delta = 24_000L), // received 24
            StockEvent.Count(hlc = 7L, id = 3L, counted = 5_000L, expected = 6_000L), // counted 5 (expected 6)
            StockEvent.Change(hlc = 6L, id = 4L, delta = -1_000L),
        )
        val b = StockTimeline.balances(events, levelAfterNewest = 27_000L)
        assertEquals(listOf<Long?>(27_000L, 29_000L, 5_000L, 6_000L), b.after)
        assertEquals(7_000L, b.before)

        val old = StockTimeline.balances(listOf(StockEvent.Count(5L, 5L, 4_000L, expected = null), StockEvent.Change(4L, 6L, 1_000L)), 4_000L)
        assertEquals(listOf<Long?>(4_000L, null), old.after)
        assertNull(old.before)
    }
}
