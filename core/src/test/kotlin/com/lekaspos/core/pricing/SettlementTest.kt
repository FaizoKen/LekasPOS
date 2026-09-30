package com.lekaspos.core.pricing

import com.lekaspos.core.pricing.Settlement.Reason
import com.lekaspos.core.pricing.Settlement.Result
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementTest {

    @Test
    fun cashSettlesWithRoundingUp() {
        assertEquals(Result.Settled(applied = 1005, rounding = 2, change = 0), Settlement.cash(1003, 1005, 5))
        assertEquals(Result.Settled(applied = 1005, rounding = 2, change = 995), Settlement.cash(1003, 2000, 5))
    }

    @Test
    fun cashSettlesWithRoundingDown() {
        assertEquals(Result.Settled(applied = 1000, rounding = -2, change = 0), Settlement.cash(1002, 1000, 5))
    }

    @Test
    fun partialCashLeavesTheRestDue() {
        assertEquals(Result.Partial(applied = 500, remaining = 503), Settlement.cash(1003, 500, 5))
    }

    @Test
    fun cashBetweenExactAndRoundedAmountIsRejected() {
        assertEquals(Result.Rejected(Reason.CASH_BELOW_ROUNDED_DUE, 1005), Settlement.cash(1003, 1004, 5))
    }

    @Test
    fun cashWithoutRoundingStep() {
        assertEquals(Result.Settled(applied = 1003, rounding = 0, change = 0), Settlement.cash(1003, 1003, 0))
    }

    @Test
    fun exactTendersNeverGiveChange() {
        assertEquals(Result.Settled(1003, 0, 0), Settlement.exact(1003, 1003))
        assertEquals(Result.Partial(500, 503), Settlement.exact(1003, 500))
        assertEquals(Result.Rejected(Reason.EXCEEDS_DUE, 1003), Settlement.exact(1003, 2000))
    }

    @Test
    fun splitTenderRoundsOnlyTheCashPart() {
        val card = Settlement.exact(1003, 500) as Result.Partial
        val cash = Settlement.cash(card.remaining, 505, 5)
        assertEquals(Result.Settled(applied = 505, rounding = 2, change = 0), cash)
        // applied amounts add up to the rounded total
        assertEquals(1003 + 2, card.applied + (cash as Result.Settled).applied)
    }

    @Test
    fun invalidTendersAreRejected() {
        assertEquals(Reason.NOTHING_DUE, (Settlement.cash(0, 100, 5) as Result.Rejected).reason)
        assertEquals(Reason.NOT_POSITIVE, (Settlement.cash(100, 0, 5) as Result.Rejected).reason)
        assertEquals(Reason.NOT_POSITIVE, (Settlement.exact(100, -1) as Result.Rejected).reason)
    }

    @Test
    fun aRemainderThatRoundsToNothingIsSettledByCash() {
        val wallet = Settlement.exact(5002, 5000) as Result.Partial
        assertEquals(Result.Settled(applied = 0, rounding = -2, change = 0), Settlement.cash(wallet.remaining, 0, 5))
        assertEquals(Result.Settled(applied = 0, rounding = -2, change = 100), Settlement.cash(wallet.remaining, 100, 5))
        // 3 sen rounds up to 5: cash is still needed.
        assertEquals(Result.Settled(applied = 5, rounding = 2, change = 0), Settlement.cash(3, 5, 5))
        assertEquals(Reason.NOT_POSITIVE, (Settlement.cash(3, 0, 5) as Result.Rejected).reason)
    }

    @Test
    fun cashRefundMirrorsRounding() {
        assertEquals(Result.Settled(applied = -1005, rounding = -2, change = 0), Settlement.cashRefund(1003, 5))
        assertEquals(Result.Settled(applied = -1000, rounding = 2, change = 0), Settlement.cashRefund(1002, 5))
    }
}
