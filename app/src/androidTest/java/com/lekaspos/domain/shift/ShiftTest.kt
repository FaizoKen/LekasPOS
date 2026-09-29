package com.lekaspos.domain.shift

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CashMoveKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShiftTest {

    private lateinit var graph: AppGraph
    private val cash = PaymentMethod(Seed.Ids.PM_CASH, "Cash", PaymentKind.CASH, true, 1)

    @Before
    fun setUp() {
        graph = TestGraph.create()
        runBlocking {
            graph.cart.load()
            graph.staff.load()
        }
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    private fun refused(reason: ActionRefused.Reason, block: suspend () -> Unit) {
        val e = assertFailsWith<ActionRefused> { runBlocking { block() } }
        assertEquals(reason, e.reason)
    }

    /** Sells one [productId] through the real cart and checkout, paid in cash (or by card). */
    private suspend fun sell(productId: Long, byCard: Boolean = false): CheckoutService.Done {
        graph.cart.addProduct(TestDb.sellable(graph.db(), productId))
        val total = graph.cart.state.value.priced.total
        if (byCard) return graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CARD, PaymentKind.CARD, "Card", false, total, total, 0L)), 0L)
        val s = Settlement.cash(total, 10_000L, 5L) as Settlement.Result.Settled
        return graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, 10_000L, s.change)), s.rounding)
    }

    private suspend fun shiftOf(saleId: Long): Long? = graph.db().read { r ->
        r.queryList("SELECT shift_id FROM sale WHERE id = ?", arrayOf(saleId.toString())) { if (it.isNull(0)) null else it.getLong(0) }.single()
    }

    @Test
    fun expectedCashFollowsSalesRefundsVoidsAndCashMovements() = runBlocking {
        val db = graph.db()
        val milo = TestDb.product(db, "Milo", 1_003L)
        val roti = TestDb.product(db, "Roti", 350L)
        val shift = graph.shifts.open(10_000L)
        assertEquals(shift.id, graph.shifts.current.value?.id)

        val a = sell(milo) // 10.03 → 10.05 in cash
        val b = sell(roti) // 3.50 in cash
        val c = sell(milo, byCard = true) // 10.03 by card
        assertEquals(shift.id, shiftOf(a.saleId))
        assertEquals(shift.id, shiftOf(c.saleId))
        val line = graph.sales.refundInfo(a.saleId)!!.lines.single().id
        val refund = graph.sales.refund(a.saleId, mapOf(line to 1_000L), true, "damaged", cash) // −10.05 cash
        graph.sales.void(refund.id, "mistake") // the 10.05 comes back
        graph.sales.void(b.saleId, "wrong item") // 3.50 goes back out
        graph.shifts.moveCash(CashMoveKind.CASH_IN, 2_000L, "change from bank")
        graph.shifts.moveCash(CashMoveKind.CASH_OUT, 500L, "ice delivery")
        graph.shifts.moveCash(CashMoveKind.DROP, 5_000L, null)

        val report = assertNotNull(graph.shifts.report(shift.id))
        assertEquals(3, report.sales)
        assertEquals(1_005L + 350L + 1_003L, report.salesTotal)
        assertEquals(1, report.refunds)
        assertEquals(-1_005L, report.refundsTotal)
        assertEquals(2, report.voids)
        assertEquals(1_355L, report.cash.cashSales)
        assertEquals(-1_005L, report.cash.cashRefunds)
        assertEquals(-1_005L + 350L, report.cash.voided) // voided refund + voided sale
        // 100.00 + 13.55 − 10.05 − (−10.05 + 3.50) + 20.00 − 5.00 − 50.00
        val expected = 10_000L + 1_355L - 1_005L - (-1_005L + 350L) + 2_000L - 500L - 5_000L
        assertEquals(expected, report.cash.expected)
        val card = report.methods.single { it.kind == PaymentKind.CARD }
        assertEquals(1_003L, card.amount)
        val cashMethod = report.methods.single { it.kind == PaymentKind.CASH }
        assertEquals(1_355L - 1_005L - (-1_005L + 350L), cashMethod.amount)

        val closed = graph.shifts.close(expected - 30L, "short 30 sen")
        assertEquals(-30L, closed.cash.difference(closed.counted!!))
        assertNull(graph.shifts.current.value)
        val row = assertNotNull(db.read { ShiftDao.get(it, shift.id) })
        assertFalse(row.open)
        assertEquals(expected, row.expectedCash)
        assertEquals(expected - 30L, row.countedCash)
        val closeAudit = db.read { AuditDao.byAction(it, AuditAction.SHIFT_CLOSE, null) }.single()
        assertEquals(-30L, closeAudit.amount)
        assertEquals(3L, db.read { r -> r.long("SELECT COUNT(*) FROM cash_movement WHERE shift_id = ?", shift.id) })
    }

    @Test
    fun aVoidInALaterShiftComesOutOfThatShiftsDrawer() = runBlocking {
        val db = graph.db()
        db.syncEnabled = true
        val milo = TestDb.product(db, "Milo", 1_000L)
        val first = graph.shifts.open(0L)
        val sale = sell(milo)
        graph.shifts.close(1_000L, null)
        val second = graph.shifts.open(5_000L)
        graph.sales.void(sale.saleId, "customer changed mind")

        assertEquals(1_000L, graph.shifts.report(first.id)!!.cash.expected) // unchanged
        val later = assertNotNull(graph.shifts.report(second.id))
        assertEquals(4_000L, later.cash.expected)
        assertEquals(1, later.voids)
        assertEquals(0, later.sales)
        val events = db.read { r -> r.queryList("SELECT entity FROM outbox") { it.getInt(0) } }
        assertEquals(3, events.count { it == Entity.SHIFT }) // two opens and a close, as LWW events
        assertTrue(events.contains(Entity.SALE_VOID))
    }

    @Test
    fun aRequiredShiftBlocksPaymentsUntilOpened() = runBlocking {
        val db = graph.db()
        graph.settings.load()
        graph.settings.saveStore(graph.settings.store.value.copy(shiftRequired = true))
        val milo = TestDb.product(db, "Milo", 1_000L)
        graph.cart.addProduct(TestDb.sellable(db, milo))
        refused(ActionRefused.Reason.NEEDS_SHIFT) {
            graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CARD, PaymentKind.CARD, "Card", false, 1_000L, 1_000L, 0L)), 0L)
        }
        assertFalse(graph.cart.state.value.cart.isEmpty) // the bill is untouched
        refused(ActionRefused.Reason.NEEDS_SHIFT) { graph.shifts.moveCash(CashMoveKind.CASH_IN, 100L, null) }
        graph.shifts.open(0L)
        refused(ActionRefused.Reason.SHIFT_OPEN) { graph.shifts.open(0L) }
        graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CARD, PaymentKind.CARD, "Card", false, 1_000L, 1_000L, 0L)), 0L)
        assertTrue(graph.cart.state.value.cart.isEmpty)
        assertEquals(1L, db.read { AuditDao.countByAction(it, AuditAction.SHIFT_OPEN) })
    }

    @Test
    fun cashMovementsNeedPermissionOrAManager() = runBlocking {
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        val manager = graph.staffAdmin.save(null, "Ah Kow", Seed.Ids.ROLE_MANAGER, true)
        graph.staffAdmin.setPin(manager, "5555")
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        graph.shifts.open(10_000L) // any staff member opens their shift
        refused(ActionRefused.Reason.NOT_ALLOWED) { graph.shifts.moveCash(CashMoveKind.CASH_OUT, 1_000L, "lunch") }
        val approval = assertNotNull(graph.permissions.approve(manager, "5555", Perm.CASH_MOVE).second)
        graph.shifts.moveCash(CashMoveKind.CASH_OUT, 1_000L, "lunch", approval)
        val entry = graph.db().read { AuditDao.byAction(it, AuditAction.CASH_OUT, null) }.single()
        assertEquals(cashier, entry.staffId)
        assertEquals(manager, entry.approvedBy)
        // A cashier's close is blind: no SHIFT_REPORT permission.
        assertFalse(graph.permissions.allowed(Perm.SHIFT_REPORT))
        graph.shifts.close(9_000L, null)
        Unit
    }
}
