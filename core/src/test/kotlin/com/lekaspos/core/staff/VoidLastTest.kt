package com.lekaspos.core.staff

import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.SaleKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoidLastTest {

    private val till = 7
    private val siti = 21L
    private val now = 1_000_000_000L
    private val saleId = Ids.make(till, 500L)
    private val sale = VoidLast.Sale(saleId, SaleKind.SALE, siti, now - 60_000L, 9L)

    private fun ok(s: VoidLast.Sale = sale, staff: Long = siti, latest: Long? = saleId, shift: Long? = 9L, at: Long = now) =
        VoidLast.allowed(s, staff, till, latest, shift, at)

    @Test
    fun theSaleJustMadeByThePersonOnThisTill() {
        assertTrue(ok())
        assertTrue(ok(at = sale.soldAt + VoidLast.WINDOW_MS)) // 5 minutes exactly
        assertTrue(ok(s = sale.copy(shiftId = null), shift = null)) // a shop without shifts
    }

    @Test
    fun anythingElseNeedsAManager() {
        assertFalse(ok(at = sale.soldAt + VoidLast.WINDOW_MS + 1L), "too late")
        assertFalse(ok(at = sale.soldAt - 1L), "clock set back before the sale")
        assertFalse(ok(staff = 22L), "another person's sale")
        assertFalse(ok(s = sale.copy(staffId = null)), "nobody's sale")
        assertFalse(ok(latest = Ids.make(till, 501L)), "the till has made another sale or refund since")
        assertFalse(ok(latest = null), "nothing of this till")
        assertFalse(ok(shift = 10L), "the shift was closed since")
        assertFalse(ok(s = sale.copy(kind = SaleKind.REFUND)), "a refund")
        val other = Ids.make(till + 1, 500L)
        assertFalse(VoidLast.allowed(sale.copy(id = other), siti, till, other, 9L, now), "made on another till")
    }
}
