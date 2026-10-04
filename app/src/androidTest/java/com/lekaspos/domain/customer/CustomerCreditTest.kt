package com.lekaspos.domain.customer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.DerivedRebuild
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.queryList
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomerCreditTest {

    private lateinit var graph: AppGraph
    private val credit = PaymentMethod(Seed.Ids.PM_CREDIT, "Customer credit", PaymentKind.CREDIT, false, 4)
    private val cash = PaymentMethod(Seed.Ids.PM_CASH, "Cash", PaymentKind.CASH, true, 1)

    @Before
    fun setUp() {
        graph = TestGraph.create()
        runBlocking {
            graph.cart.load()
            graph.staff.load()
            graph.settings.saveStore(graph.settings.store.value.copy(creditEnabled = true))
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

    private suspend fun balance(id: Long) = graph.db().read { CustomerDao.balance(it, id) }

    /** Puts [price] of goods on the bill for [customer] and pays it all on credit. */
    private suspend fun onCredit(customer: Customer, productId: Long, approvals: List<com.lekaspos.domain.Approval> = emptyList()): CheckoutService.Done {
        graph.cart.addProduct(TestDb.sellable(graph.db(), productId))
        graph.cart.setCustomer(customer.id, customer.name)
        val total = graph.cart.state.value.priced.total
        return graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, "Customer credit", false, total, total, 0L)), 0L, approvals)
    }

    @Test
    fun creditSalesChargeTheCustomerAndRepaymentsFillTheDrawer() = runBlocking {
        val db = graph.db()
        db.syncEnabled = true
        val ali = graph.customers.save(null, Customer(0L, "Ali Bakar", phone = "012-345 6789", creditLimit = 5_000L))
        val beras = TestDb.product(db, "Beras 5kg", 3_000L)
        val shift = graph.shifts.open(0L)

        val done = onCredit(ali, beras)
        assertEquals("Ali Bakar", done.customerName)
        assertEquals(3_000L, done.customerBalance)
        assertEquals(3_000L, balance(ali.id))
        val header = assertNotNull(db.read { SaleQueries.header(it, done.saleId) })
        assertEquals(ali.id, header.customerId)
        val receipt = assertNotNull(db.read { ReceiptBuilder.build(it, done.saleId, false, graph.settings.store.value, TimeZone.getDefault()) })
        assertEquals("Ali Bakar", receipt.customer)
        assertTrue(graph.cart.state.value.customerId == null) // the next bill starts without a customer

        val left = graph.customers.receivePayment(ali.id, 1_000L, cash, "part payment")
        assertEquals(2_000L, left)
        val report = assertNotNull(graph.shifts.report(shift.id))
        assertEquals(1_000L, report.cash.creditRepayments)
        assertEquals(1_000L, report.cash.expected) // no cash sale, one cash repayment
        assertEquals(3_000L, report.creditCharged)

        val events = db.read { r -> r.queryList("SELECT entity FROM outbox") { it.getInt(0) } }
        assertEquals(2, events.count { it == Entity.CREDIT }) // the charge and the repayment
        assertTrue(events.contains(Entity.CUSTOMER))
    }

    /**
     * 2026-10 review: a "repayment" of any size wiped a debt (or left credit to spend past the
     * limit) without an audit entry, and cash ignored the 5-sen rounding (the drawer was 2 sen off).
     */
    @Test
    fun repaymentsPayBackWhatIsOwedRoundedInCashAndAudited() = runBlocking {
        val db = graph.db()
        val card = PaymentMethod(Seed.Ids.PM_CARD, "Card", PaymentKind.CARD, false, 2)
        val ali = graph.customers.save(null, Customer(0L, "Ali Bakar"))
        graph.customers.adjust(ali.id, 1_003L, "notebook")
        val shift = graph.shifts.open(0L)
        refused(ActionRefused.Reason.MORE_THAN_OWED) { graph.customers.receivePayment(ali.id, 30_000L, card, null) }
        assertEquals(1_003L, balance(ali.id))
        // The whole debt in cash: RM10.05 goes into the drawer, 2 sen are cash rounding, nothing is owed.
        assertEquals(0L, graph.customers.receivePayment(ali.id, 1_003L, cash, null))
        val report = assertNotNull(graph.shifts.report(shift.id))
        assertEquals(1_005L, report.cash.creditRepayments)
        assertEquals(1_005L, report.cash.expected)
        assertEquals(1L, db.read { AuditDao.countByAction(it, AuditAction.CREDIT_PAYMENT) })
        refused(ActionRefused.Reason.MORE_THAN_OWED) { graph.customers.receivePayment(ali.id, 100L, cash, null) }
        // Rebuilt balances agree with the entries (payment 10.05, rounding +0.02).
        db.write(reserveIds = 0L) { tx -> DerivedRebuild.customerBalances(tx) }
        assertEquals(0L, balance(ali.id))
    }

    @Test
    fun goingOverTheLimitNeedsAManager() = runBlocking {
        val db = graph.db()
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        val manager = graph.staffAdmin.save(null, "Ah Kow", Seed.Ids.ROLE_MANAGER, true)
        graph.staffAdmin.setPin(manager, "5555")
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        assertTrue(graph.permissions.allowed(Perm.CREDIT_SALE))

        // A cashier may add customers, but not set or lift the limit that stops her own credit sales.
        refused(ActionRefused.Reason.NOT_ALLOWED) { graph.customers.save(null, Customer(0L, "Ali", creditLimit = 5_000L)) }
        val setLimit = assertNotNull(graph.permissions.approve(manager, "5555", Perm.CREDIT_LIMIT).second)
        val ali = graph.customers.save(null, Customer(0L, "Ali", creditLimit = 5_000L), limitApproval = setLimit)
        refused(ActionRefused.Reason.NOT_ALLOWED) { graph.customers.save(ali, ali.copy(creditLimit = 0L)) } // 0 = no limit
        graph.customers.save(ali, ali.copy(phone = "012-3456789")) // other details: no approval needed
        val limitAudit = db.read { AuditDao.byAction(it, AuditAction.CREDIT_LIMIT_CHANGE, null) }.single()
        assertEquals(cashier, limitAudit.staffId)
        assertEquals(manager, limitAudit.approvedBy)
        assertEquals(5_000L, limitAudit.amount)
        val beras = TestDb.product(db, "Beras 5kg", 3_000L)
        onCredit(ali, beras)
        refused(ActionRefused.Reason.OVER_CREDIT_LIMIT) { onCredit(ali, beras) } // 30 + 30 > 50
        assertEquals(3_000L, balance(ali.id))
        assertTrue(!graph.cart.state.value.cart.isEmpty) // refused before anything was written

        graph.cart.clear(graph.permissions.approve(manager, "5555", Perm.CANCEL_BILL).second)
        val approval = assertNotNull(graph.permissions.approve(manager, "5555", Perm.CREDIT_LIMIT).second)
        onCredit(ali, beras, listOf(approval))
        assertEquals(6_000L, balance(ali.id))
        val audit = db.read { AuditDao.byAction(it, AuditAction.CREDIT_OVER_LIMIT, null) }.single()
        assertEquals(cashier, audit.staffId)
        assertEquals(manager, audit.approvedBy)
        assertEquals(3_000L, audit.amount)
    }

    @Test
    fun refundsAndVoidsGiveTheCreditBack() = runBlocking {
        val db = graph.db()
        val ali = graph.customers.save(null, Customer(0L, "Ali"))
        val gula = TestDb.product(db, "Gula", 1_000L)
        graph.cart.addProduct(TestDb.sellable(db, gula), qty = 3_000L)
        graph.cart.setCustomer(ali.id, ali.name)
        val sale = graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, "Customer credit", false, 3_000L, 3_000L, 0L)), 0L)
        assertEquals(3_000L, balance(ali.id))

        val line = graph.sales.refundInfo(sale.saleId)!!.lines.single().id
        val refund = graph.sales.refund(sale.saleId, mapOf(line to 1_000L), true, "spilt", credit)
        assertEquals(2_000L, balance(ali.id))
        graph.sales.void(refund.id, "mistake")
        assertEquals(3_000L, balance(ali.id))
        graph.sales.void(sale.saleId, "wrong customer")
        assertEquals(0L, balance(ali.id))

        // Statement: newest first, each line with the balance right after it.
        val rows = graph.customers.statement(ali.id, null, limit = 2) + graph.customers.statement(ali.id, graph.customers.statement(ali.id, null, limit = 2).last(), limit = 2)
        assertEquals(4, rows.size)
        assertEquals(listOf(0L, 3_000L, 2_000L, 3_000L), rows.map { it.balanceAfter })
        assertEquals(listOf(CreditKind.CHARGE, CreditKind.CHARGE, CreditKind.CHARGE, CreditKind.CHARGE), rows.map { it.entry.kind })
        assertEquals(sale.receiptNo, rows.last().entry.receiptNo)

        // A refund to credit needs a customer on the sale.
        val other = graph.checkout.let {
            graph.cart.addProduct(TestDb.sellable(db, gula))
            it.complete(listOf(Tender(Seed.Ids.PM_CARD, PaymentKind.CARD, "Card", false, 1_000L, 1_000L, 0L)), 0L)
        }
        val otherLine = graph.sales.refundInfo(other.saleId)!!.lines.single().id
        refused(ActionRefused.Reason.NEEDS_CUSTOMER) { graph.sales.refund(other.saleId, mapOf(otherLine to 1_000L), true, "x", credit) }
    }

    /**
     * Found in the 2026-10 review: the next-page query started from the search prefix instead of
     * the last row, so every page after the first repeated earlier customers. Names are added in
     * reverse order here, so ids and names sort in opposite directions (the case that showed it).
     */
    @Test
    fun customerPagesNeverRepeatOrSkipACustomer() = runBlocking {
        for (i in 170 downTo 1) graph.customers.save(null, Customer(0L, "Pelanggan %03d".format(i)))
        val seen = ArrayList<String>()
        var after: com.lekaspos.data.customer.CustomerItem? = null
        var pages = 0
        while (true) {
            val page = graph.customers.page("", after)
            seen.addAll(page.map { it.name })
            pages++
            if (page.size < 50 || pages > 10) break
            after = page.last()
        }
        assertEquals((1..170).map { "Pelanggan %03d".format(it) }, seen)
        assertEquals(4, pages)
        // A search prefix pages the same way.
        val p1 = graph.customers.page("Pelanggan 1", null)
        val p2 = graph.customers.page("Pelanggan 1", p1.last())
        assertEquals((100..170).map { "Pelanggan %03d".format(it) }, (p1 + p2).map { it.name })
    }

    @Test
    fun customerListSearchBalancesAndRebuild() = runBlocking {
        val db = graph.db()
        val ali = graph.customers.save(null, Customer(0L, "Ali Bakar", phone = "+60 12-345 6789"))
        val siti = graph.customers.save(null, Customer(0L, "Siti Aminah", phone = "019 888 1234"))
        for (i in 1..60) graph.customers.save(null, Customer(0L, "Customer %02d".format(i)))
        assertEquals(listOf("Ali Bakar"), graph.customers.page("ali", null).map { it.name })
        assertEquals(listOf("Siti Aminah"), graph.customers.page("0198", null).map { it.name })
        assertEquals(listOf("Ali Bakar"), graph.customers.page("+6012", null).map { it.name })
        val first = graph.customers.page("", null)
        val second = graph.customers.page("", first.last())
        assertEquals(62, (first + second).map { it.id }.toSet().size)
        assertEquals(first.map { it.nameKey }.sorted(), first.map { it.nameKey })

        graph.customers.adjust(siti.id, 1_250L, "old debt from the notebook")
        assertEquals(1_250L, balance(siti.id))
        assertEquals(1L to 1_250L, graph.customers.debtors())
        refused(ActionRefused.Reason.HAS_BALANCE) { graph.customers.delete(siti.id) }
        graph.customers.receivePayment(siti.id, 1_250L, cash, null)
        graph.customers.delete(siti.id)
        assertTrue(graph.customers.page("siti", null).isEmpty())
        assertEquals(1L, db.read { AuditDao.countByAction(it, AuditAction.CREDIT_ADJUST) })

        // The derived balances rebuild to the same values.
        graph.customers.adjust(ali.id, -300L, "overpaid last month")
        val before = db.read { r -> r.queryList("SELECT customer_id, balance FROM customer_balance ORDER BY customer_id") { it.getLong(0) to it.getLong(1) } }
        db.write(reserveIds = 0L) { tx -> DerivedRebuild.customerBalances(tx) }
        val after = db.read { r -> r.queryList("SELECT customer_id, balance FROM customer_balance ORDER BY customer_id") { it.getLong(0) to it.getLong(1) } }
        assertEquals(before, after)
        assertEquals(-300L, balance(ali.id))
    }

    @Test
    fun creditNeedsTheSwitchAndACustomer() = runBlocking {
        val db = graph.db()
        val gula = TestDb.product(db, "Gula", 1_000L)
        graph.cart.addProduct(TestDb.sellable(db, gula))
        refused(ActionRefused.Reason.NEEDS_CUSTOMER) {
            graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, "Customer credit", false, 1_000L, 1_000L, 0L)), 0L)
        }
        graph.settings.saveStore(graph.settings.store.value.copy(creditEnabled = false))
        refused(ActionRefused.Reason.CREDIT_OFF) {
            graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, "Customer credit", false, 1_000L, 1_000L, 0L)), 0L)
        }
    }

    /**
     * Deleted on this till while another till, offline, sold to them on credit: the debt arrived for a
     * customer no list showed, and could not be repaid or corrected anywhere (2026-10 review).
     */
    @Test
    fun aDeletedCustomerStillOwingIsListedAndCanOnlyBeSettled() = runBlocking {
        val db = graph.db()
        val ali = graph.customers.save(null, Customer(0L, "Ali Bakar"))
        graph.customers.delete(ali.id)
        assertTrue(graph.customers.page("", null, withRemoved = true).none { it.id == ali.id }) // owes nothing: gone
        // The other till's credit sale, as sync brings it.
        db.write(reserveIds = 1L) { tx ->
            CustomerDao.insertCredit(tx, ali.id, CreditKind.CHARGE, 3_000L, null, Seed.Ids.PM_CREDIT, null, null, null, System.currentTimeMillis())
        }
        val listed = graph.customers.page("", null, withRemoved = true).single { it.id == ali.id }
        assertTrue(listed.removed)
        assertEquals(3_000L, listed.balance)
        assertTrue(graph.customers.page("", null).none { it.id == ali.id }) // picking a customer for a bill: not offered
        assertTrue(graph.customers.isRemoved(ali.id))
        // No new debt, no edit back to life; settling it is fine.
        refused(ActionRefused.Reason.NOT_FOUND) { graph.customers.adjust(ali.id, 100L, "more") }
        refused(ActionRefused.Reason.NOT_FOUND) { graph.customers.adjust(ali.id, -3_100L, "past zero") }
        refused(ActionRefused.Reason.NOT_FOUND) { graph.customers.save(ali, ali.copy(creditLimit = 10_000L)) }
        assertEquals(1_000L, graph.customers.receivePayment(ali.id, 2_000L, cash, null))
        assertEquals(0L, graph.customers.adjust(ali.id, -1_000L, "written off"))
        assertTrue(graph.customers.page("", null, withRemoved = true).none { it.id == ali.id }) // settled: gone again
    }
}
