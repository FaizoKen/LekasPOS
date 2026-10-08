package com.lekaspos.domain.staff

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.report.Period
import com.lekaspos.core.shift.ShiftGuide
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Seed
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.domain.report.ReportService
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** D-067: a till shared by several people — what is on record, the handover, the manager's help, the staff check. */
@RunWith(AndroidJUnit4::class)
class StaffCheckTest {

    private val name = "test-${UUID.randomUUID()}.db"
    private lateinit var graph: AppGraph
    private val cart get() = graph.cart
    private val tz = TimeZone.getDefault()

    @Before
    fun setUp() {
        graph = TestGraph.create(name)
        runBlocking { cart.load() }
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    /** The owner's PIN is 2468; returns the cashier Siti (PIN 1111), signed in. */
    private suspend fun signInCashier(): Long {
        graph.staff.load()
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        return cashier
    }

    private fun product(name: String, price: Long) = TestDb.sellable(runBlocking { graph.db() }, TestDb.product(runBlocking { graph.db() }, name, price))

    private fun today(): Period {
        val day = Days.epochDay(System.currentTimeMillis(), tz)
        return Period(day, day + 1)
    }

    @Test
    fun whatIsTakenOffABillIsOnRecordAndAfterThePaymentScreenMarkedSo() = runBlocking {
        val milo = product("Milo", 1_000L)
        val roti = product("Roti", 350L)
        val key = cart.addProduct(milo)
        assertTrue(cart.setQty(key, 3_000L))
        assertTrue(cart.setQty(key, 1_000L)) // two fewer: RM20 off
        cart.changeQty(key, 1_000L) // more is no check
        val r = cart.addProduct(roti)
        cart.setPaying(true) // the payment screen showed the total …
        cart.setPaying(false) // … and was cancelled
        cart.remove(r)
        cart.flush()
        val db = graph.db()
        val before = db.read { AuditDao.byAction(it, AuditAction.LINE_REMOVE, null) }.single()
        assertEquals(2_000L, before.amount)
        assertEquals("Milo: 3 -> 1", before.detail)
        val after = db.read { AuditDao.byAction(it, AuditAction.LINE_REMOVE_AFTER_PAY, null) }.single()
        assertEquals(350L to "Roti ×1", after.amount to after.detail)

        // A bill cleared after its total was shown: the items are named.
        assertTrue(cart.clear())
        cart.flush()
        val cleared = db.read { AuditDao.byAction(it, AuditAction.BILL_CANCEL_AFTER_PAY, null) }.single()
        assertEquals(2_000L, cleared.amount)
        assertEquals("Milo ×2", cleared.detail)
        // The next bill starts afresh: cleared before any payment screen, a plain clear.
        cart.addProduct(roti)
        assertTrue(cart.clear())
        cart.flush()
        assertEquals("Roti ×1", db.read { AuditDao.byAction(it, AuditAction.BILL_CANCEL, null) }.single().detail)

        val check = db.read { ReportService.staffCheck(it, today(), tz) }.single()
        assertEquals(Seed.Ids.STAFF_OWNER, check.staffId)
        assertEquals(2L, check.checks.removed.count)
        assertEquals(2L, check.checks.cleared.count)
        assertEquals(2L, check.checks.afterPay.count)
        assertEquals(2_350L, check.checks.afterPay.amount)
        assertTrue(check.checks.warning)
    }

    /** Parking the bill and bringing it back, or a restart, does not wipe "the total was shown". */
    @Test
    fun theShownTotalStaysWithTheBill() = runBlocking {
        val milo = product("Milo", 1_000L)
        cart.addProduct(milo)
        cart.setPaying(true)
        cart.setPaying(false)
        assertTrue(cart.hold(null))
        cart.addProduct(product("Roti", 350L))
        assertTrue(cart.resume(cart.heldBills().single { it.total == 1_000L }.id))
        assertTrue(cart.state.value.payShown)
        cart.flush()
        val restarted = TestGraph.reopen(name)
        try {
            restarted.cart.load()
            assertTrue(restarted.cart.state.value.payShown)
            restarted.cart.remove(restarted.cart.state.value.cart.items.single().key)
            restarted.cart.flush()
            assertEquals(1L, restarted.db().read { AuditDao.countByAction(it, AuditAction.LINE_REMOVE_AFTER_PAY) })
        } finally {
            TestGraph.close(restarted)
        }
    }

    @Test
    fun aManagersHelpLendsTheBillsPermissionsOnly() = runBlocking {
        signInCashier()
        val p = graph.permissions
        val help = assertNotNull(p.approveHelp(Seed.Ids.STAFF_OWNER, "2468", 0L).second)
        p.startHelp(help)
        assertTrue(p.allowed(Perm.DISCOUNT))
        assertTrue(p.allowed(Perm.PRICE_OVERRIDE))
        // Money out of the drawer and the back office show, but ask for the manager's PIN again.
        for (perm in listOf(Perm.VOID, Perm.REFUND, Perm.OPEN_DRAWER, Perm.CASH_MOVE, Perm.MANAGE_PRODUCTS, Perm.SETTINGS)) {
            assertFalse(p.allowed(perm), "help lends $perm")
            assertTrue(p.shown(perm), "help shows $perm")
            assertNull(p.actorOrNull(perm))
        }
        p.endHelp()
        assertFalse(p.shown(Perm.VOID))
        // Help asked for something (an unknown barcode → "Add product") lends that too.
        p.startHelp(help, lend = Perm.MANAGE_PRODUCTS)
        assertTrue(p.allowed(Perm.MANAGE_PRODUCTS))
        assertFalse(p.allowed(Perm.VOID))
        p.endHelp()
    }

    @Test
    fun theNextCashierCountsTheDrawerAndTakesItOver() = runBlocking {
        graph.staff.load()
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        graph.shifts.open(10_000L) // the owner opens the till with RM100
        cart.addProduct(product("Milo", 1_000L))
        val sold = graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, 1_000L, 1_000L, 0L)), 0L)
        assertEquals(1_000L, sold.total)
        val ownerShift = assertNotNull(graph.shifts.current.value)
        // The cashier signs in: the owner's shift is open, so a count is due.
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        assertEquals(ownerShift.id, graph.shifts.handoverDue(cashier)?.id)
        assertNull(graph.shifts.handoverDue(Seed.Ids.STAFF_OWNER)) // the owner's own shift
        assertTrue(graph.shifts.markAsked(ownerShift.id, cashier))
        assertFalse(graph.shifts.markAsked(ownerShift.id, cashier)) // asked once

        // RM105 in the drawer (RM110 expected): the owner's shift closes RM5 short, the cashier's opens with RM105.
        val mine = graph.shifts.handover(10_500L, null)
        val db = graph.db()
        val closed = assertNotNull(db.read { ShiftDao.get(it, ownerShift.id) })
        assertEquals(11_000L, closed.expectedCash)
        assertEquals(10_500L, closed.countedCash)
        assertEquals(cashier, closed.closedBy)
        assertEquals(cashier, mine.openedBy)
        assertEquals(10_500L, mine.openingFloat)
        assertEquals(mine.id, graph.shifts.current.value?.id)
        assertNull(graph.shifts.handoverDue(cashier))
        val opened = db.read { AuditDao.byAction(it, AuditAction.SHIFT_OPEN, null) }.first()
        assertTrue(opened.detail?.startsWith("handover from ") == true, "${opened.detail}")

        // The owner signs in on the cashier's shift and chooses "Not now": on record, not asked again.
        graph.staff.lock()
        graph.staff.signIn(Seed.Ids.STAFF_OWNER, "2468")
        val due = assertNotNull(graph.shifts.handoverDue(Seed.Ids.STAFF_OWNER))
        graph.shifts.continueShift(Seed.Ids.STAFF_OWNER, due)
        assertNull(graph.shifts.handoverDue(Seed.Ids.STAFF_OWNER))

        val checks = db.read { ReportService.staffCheck(it, today(), tz) }.associateBy { it.staffId }
        val owner = assertNotNull(checks[Seed.Ids.STAFF_OWNER])
        assertEquals(1L, owner.shifts)
        assertEquals(-500L, owner.overShort) // the shift the owner opened came up short
        assertEquals(1L, owner.shortShifts)
        assertEquals(1L, owner.checks.continued)
        assertEquals(1L, owner.sales.count)
    }

    /** D-068: what a close leaves in the drawer is what the next opening count is checked against. */
    @Test
    fun theNextOpeningIsCheckedAgainstWhatWasLeft() = runBlocking {
        graph.staff.load()
        graph.shifts.open(10_000L)
        graph.shifts.close(25_000L, null, leave = 10_000L)
        assertEquals(10_000L, graph.shifts.leftInDrawer()?.amount)
        graph.shifts.open(8_000L) // RM20 less than was left
        val db = graph.db()
        val diff = db.read { AuditDao.byAction(it, AuditAction.FLOAT_DIFFERENCE, null) }.single()
        assertEquals(-2_000L, diff.amount)
        assertNull(graph.shifts.leftInDrawer())
        // The same amount as was left: nothing to record.
        graph.shifts.close(8_000L, null, leave = 8_000L)
        graph.shifts.open(8_000L)
        assertEquals(1, db.read { AuditDao.byAction(it, AuditAction.FLOAT_DIFFERENCE, null) }.size)
        // More than was counted cannot stay in the drawer.
        graph.shifts.close(5_000L, null, leave = 9_000L)
        assertEquals(5_000L, graph.shifts.leftInDrawer()?.amount)
        val owner = db.read { ReportService.staffCheck(it, today(), tz) }.single()
        assertEquals(-2_000L, owner.checks.floatDiffs.amount)
        assertTrue(owner.checks.warning)
    }

    /** D-068: a shift nobody closed is closed by the next day's first count, which starts that day's shift. */
    @Test
    fun yesterdaysShiftIsClosedByTodaysFirstCount() = runBlocking {
        graph.staff.load()
        graph.settings.saveStore(graph.settings.store.value.copy(shiftRequired = true))
        assertEquals(ShiftGuide.Ask.OPEN, graph.shifts.prompt(Seed.Ids.STAFF_OWNER).ask)
        val s = graph.shifts.open(10_000L)
        assertEquals(ShiftGuide.Ask.NONE, graph.shifts.prompt(Seed.Ids.STAFF_OWNER).ask)
        // The next morning, as far as the till can tell: it was opened a day earlier.
        graph.db().write(reserveIds = 0L) { tx -> tx.db.execSQL("UPDATE shift SET opened_at = opened_at - 86400000 WHERE id = ?", arrayOf<Any?>(s.id)) }
        val next = TestGraph.reopen(name)
        try {
            next.settings.load()
            next.staff.load()
            assertEquals(ShiftGuide.Ask.NEW_DAY, next.shifts.prompt(Seed.Ids.STAFF_OWNER).ask)
            val todays = next.shifts.handover(10_000L, null, newDay = true)
            assertTrue(todays.id != s.id)
            assertEquals(10_000L, todays.openingFloat)
            val closed = next.db().read { AuditDao.byAction(it, AuditAction.SHIFT_CLOSE, null) }.first()
            assertTrue(closed.detail?.startsWith("not closed the day before") == true, "${closed.detail}")
            assertEquals(ShiftGuide.Ask.NONE, next.shifts.prompt(Seed.Ids.STAFF_OWNER).ask)
        } finally {
            TestGraph.close(next)
        }
    }

    /** D-068: a shop whose staff sign in with PINs uses shifts, once; the owner can turn them off for good. */
    @Test
    fun aShopWithStaffUsesShifts() = runBlocking {
        graph.staff.load()
        graph.shifts.useShiftsWithStaff()
        assertFalse(graph.settings.store.value.shiftRequired) // a shop of one keeps its choice
        signInCashier()
        graph.shifts.useShiftsWithStaff()
        assertTrue(graph.settings.store.value.shiftRequired)
        assertEquals(1, graph.db().read { AuditDao.byAction(it, AuditAction.SETTINGS_CHANGE, null) }.count { it.detail?.contains("shift.required") == true })
        graph.staff.lock()
        graph.staff.signIn(Seed.Ids.STAFF_OWNER, "2468")
        graph.settings.saveStore(graph.settings.store.value.copy(shiftRequired = false))
        graph.shifts.useShiftsWithStaff()
        assertFalse(graph.settings.store.value.shiftRequired) // the owner's choice stays
    }

    @Test
    fun theStoreCanTurnTheHandoverOff() = runBlocking {
        graph.staff.load()
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        graph.shifts.open(0L)
        graph.settings.saveStore(graph.settings.store.value.copy(handoverCount = false))
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        assertNull(graph.shifts.handoverDue(cashier))
    }

    @Test
    fun theTillLocksAfterEachSaleWhenSetSo() = runBlocking {
        signInCashier()
        graph.settings.saveDevice(graph.settings.device.value.copy(lockAfterSale = true))
        cart.addProduct(product("Milo", 1_000L))
        val s = Settlement.cash(1_000L, 1_000L, 5L) as Settlement.Result.Settled
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            graph.checkout.start(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, 1_000L, s.change)), s.rounding)
        }
        withTimeout(10_000L) { graph.checkout.outcome.first { it != null } }
        assertFalse(graph.staff.state.value.locked) // the change is still on the screen
        withContext(kotlinx.coroutines.Dispatchers.Main) { graph.checkout.acknowledge() }
        assertTrue(graph.staff.state.value.locked)
    }

    @Test
    fun aTillThatNeverLockedNowLocksAfterFiveMinutesOnce() = runBlocking {
        // An older till with "never" stored (1.11 wrote every device setting, its default 0 too).
        graph.settings.saveDevice(graph.settings.device.value.copy(autoLockMinutes = 0))
        graph.db().write(reserveIds = 0L) { tx -> com.lekaspos.data.db.Meta.put(tx.db, "upgrade.lock_default", null) }
        val updated = TestGraph.reopen(name)
        try {
            updated.settings.load()
            assertEquals(5, updated.settings.device.value.autoLockMinutes)
            // Chosen again by the owner, it stays.
            updated.settings.saveDevice(updated.settings.device.value.copy(autoLockMinutes = 0))
        } finally {
            TestGraph.close(updated)
        }
        val again = TestGraph.reopen(name)
        try {
            again.settings.load()
            assertEquals(0, again.settings.device.value.autoLockMinutes)
        } finally {
            TestGraph.close(again)
        }
    }

    @Test
    fun stockWrittenOffIsOnRecordAtCost() = runBlocking {
        val db = graph.db()
        val id = TestDb.product(db, "Telur", 1_650L)
        db.write(reserveIds = 0L) { tx ->
            val p = com.lekaspos.data.product.ProductDao.get(tx.db, id) ?: error("no product")
            com.lekaspos.data.product.ProductDao.update(tx, p, p.copy(cost = 1_200L), System.currentTimeMillis())
        }
        graph.inventory.adjust(id, com.lekaspos.core.inventory.AdjustReason.DAMAGED, 2_000L, removing = true, note = "dropped")
        graph.inventory.adjust(id, com.lekaspos.core.inventory.AdjustReason.FOUND, 1_000L, removing = false, note = null)
        val entry = db.read { AuditDao.byAction(it, AuditAction.STOCK_WRITE_OFF, null) }.single()
        assertEquals(2_400L, entry.amount)
        assertEquals("Telur: -2 (damaged: dropped)", entry.detail)
    }
}
