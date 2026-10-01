package com.lekaspos.domain.sell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.cart.CartDao
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.queryList
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The selling flow's 2026-10 review fixes: credit, audit, the bill's writer, the drawer. */
@RunWith(AndroidJUnit4::class)
class SellingFixesTest {

    private val name = "test-${UUID.randomUUID()}.db"
    private lateinit var graph: AppGraph
    private val cart get() = graph.cart

    @Before
    fun setUp() {
        graph = TestGraph.create(name)
        runBlocking { cart.load() }
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    private fun creditTender(amount: Long) =
        Tender(Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, "Customer credit", false, amount, amount, 0L)

    @Test
    fun creditToADeletedCustomerIsRefusedAndALoadedBillDropsThem() = runBlocking {
        graph.settings.saveStore(graph.settings.store.value.copy(creditEnabled = true))
        val db = graph.db()
        val ali = graph.customers.save(null, Customer(0L, "Ali"))
        cart.addProduct(TestDb.sellable(db, TestDb.product(db, "Roti", 350L)))
        assertTrue(cart.setCustomer(ali.id, ali.name))
        graph.customers.delete(ali.id) // here, or on another till and synced while the bill was open

        val e = runCatching { graph.checkout.complete(listOf(creditTender(350L)), 0L) }.exceptionOrNull()
        assertEquals(ActionRefused.Reason.NEEDS_CUSTOMER, (e as? ActionRefused)?.reason)
        assertEquals(0L, db.read { CustomerDao.balance(it, ali.id) }) // no debt nobody can see
        assertFalse(cart.state.value.cart.isEmpty) // refused before anything was written
        cart.flush()

        // Loaded again (after a restart, or a parked bill resumed): the bill no longer has the customer.
        val restarted = TestGraph.reopen(name)
        try {
            restarted.cart.load()
            assertNull(restarted.cart.state.value.customerId)
            assertEquals(1, restarted.cart.state.value.cart.items.size)
            restarted.cart.flush()
            assertNull(restarted.db().read { CartDao.loadOpen(it) }?.customerId)
        } finally {
            TestGraph.close(restarted)
        }
    }

    @Test
    fun removingTheLastLineIsAuditedLikeACancel() = runBlocking {
        val db = graph.db()
        val roti = TestDb.sellable(db, TestDb.product(db, "Roti", 350L))
        val milo = TestDb.sellable(db, TestDb.product(db, "Milo", 990L))
        // A line taken off a bill that goes on is a plain change.
        val r = cart.addProduct(roti)
        val m = cart.addProduct(milo)
        cart.remove(r)
        cart.flush()
        assertEquals(0L, db.read { AuditDao.countByAction(it, AuditAction.BILL_CANCEL) })
        // The last one ends the bill: in the audit log like "Cancel bill", with what it was worth.
        cart.remove(m)
        cart.flush()
        val entry = db.read { AuditDao.byAction(it, AuditAction.BILL_CANCEL, null) }.single()
        assertEquals(990L, entry.amount)
        assertEquals("last line removed: Milo", entry.detail)
        assertTrue(cart.state.value.cart.isEmpty)
    }

    @Test
    fun aLostBillRowIsWrittenAgainSoLaterLinesAreKept() = runBlocking {
        val db = graph.db()
        val a = TestDb.sellable(db, TestDb.product(db, "A", 100L))
        val b = TestDb.sellable(db, TestDb.product(db, "B", 200L))
        val c = TestDb.sellable(db, TestDb.product(db, "C", 300L))
        cart.addProduct(a)
        cart.flush()
        val cartId = cart.state.value.cartId
        // The bill's own row is lost (a write that failed): every later line write broke on it.
        db.write(reserveIds = 0L) { tx -> tx.update("DELETE FROM cart WHERE id = ?", cartId) }
        cart.addProduct(b) // fails: no bill row
        runCatching { cart.flush() }
        withContext(Dispatchers.Main) {} // the writer asked the main thread to write the bill whole again
        cart.addProduct(c)
        cart.flush()
        val stored = assertNotNull(db.read { CartDao.loadOpen(it) })
        assertEquals(cartId, stored.id)
        assertEquals(listOf("A", "B", "C"), stored.lines.map { it.name })
        assertEquals(600L, cart.state.value.priced.total)
    }

    @Test
    fun cashThatOnlyGivesChangeBackOpensTheDrawer() = runBlocking {
        graph.settings.saveDevice(DeviceSettings(printerAddress = "00:11:22:33:44:55"))
        val db = graph.db()
        cart.addProduct(TestDb.sellable(db, TestDb.product(db, "Kopi", 1_002L)))
        val wallet = Tender(Seed.Ids.PM_EWALLET, PaymentKind.EWALLET, "E-wallet", false, 1_000L, 1_000L, 0L)
        // 2 sen left round to 0.00 in cash; the customer hands over 5 sen and gets them back.
        val s = Settlement.cash(2L, 5L, 5L) as Settlement.Result.Settled
        assertEquals(0L, s.applied)
        val cash = Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, 5L, s.change)
        val done = graph.checkout.complete(listOf(wallet, cash), s.rounding)
        assertEquals(1_000L, done.total)
        val jobs = db.read { r -> r.queryList("SELECT kind, ref_id FROM print_job") { it.getInt(0) to it.getLong(1) } }
        assertTrue((PrintJobKind.DRAWER to done.saleId) in jobs)
    }

    @Test
    fun aManagersPinForACreditSaleIsOnRecordWithTheSale() = runBlocking {
        graph.settings.saveStore(graph.settings.store.value.copy(creditEnabled = true))
        val db = graph.db()
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468") // signs the owner in
        val ali = graph.customers.save(null, Customer(0L, "Ali"))
        val roti = TestDb.sellable(db, TestDb.product(db, "Roti", 350L))
        val role = graph.staffAdmin.saveRole(null, "Packer", Perm.REPRINT)
        val packer = graph.staffAdmin.save(null, "Ah Meng", role, true)
        graph.staffAdmin.setPin(packer, "1357")
        graph.staff.lock()
        graph.staff.signIn(packer, "1357")
        assertFalse(graph.permissions.allowed(Perm.CREDIT_SALE))

        cart.addProduct(roti)
        assertTrue(cart.setCustomer(ali.id, ali.name))
        val approval = assertNotNull(graph.permissions.approve(Seed.Ids.STAFF_OWNER, "2468", Perm.CREDIT_SALE).second)
        val done = graph.checkout.complete(listOf(creditTender(350L)), 0L, listOf(approval))
        val entry = db.read { AuditDao.byAction(it, AuditAction.APPROVAL, null) }.single { it.entity == Entity.SALE }
        assertEquals(done.saleId, entry.entityId)
        assertEquals(packer, entry.staffId)
        assertEquals(Seed.Ids.STAFF_OWNER, entry.approvedBy)
        assertEquals(Perm.CREDIT_SALE, entry.amount)
    }
}
