package com.lekaspos.domain.sell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CartSessionTest {

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

    private fun product(name: String, price: Long, code: String): Long = TestDb.product(runBlocking { graph.db() }, name, price, listOf(code))

    @Test
    fun scanningAddsAndMergesAndTheBillSurvivesARestart() = runBlocking {
        product("Milo 1kg", 1890L, "9556001234567")
        product("Roti", 350L, "9556007654321")
        assertTrue(cart.scan("9556001234567") is CartSession.ScanResult.Added)
        assertTrue(cart.scan("9556001234567") is CartSession.ScanResult.Added)
        assertTrue(cart.scan("9556007654321") is CartSession.ScanResult.Added)
        assertTrue(cart.scan("0000000000000") is CartSession.ScanResult.NotFound)
        val st = cart.state.value
        assertEquals(listOf(2000L, 1000L), st.cart.items.map { it.qty })
        assertEquals(4130L, st.priced.total)
        cart.flush()

        // The process dies; a new app instance restores the open bill from the database.
        val restarted = TestGraph.reopen(name)
        try {
            restarted.cart.load()
            val again = restarted.cart.state.value
            assertEquals(st.cart.items.map { it.name to it.qty }, again.cart.items.map { it.name to it.qty })
            assertEquals(4130L, again.priced.total)
            assertEquals(st.cartId, again.cartId)
        } finally {
            TestGraph.close(restarted)
        }
    }

    @Test
    fun scaleLabelsAndWeighedItems() = runBlocking {
        val db = graph.db()
        val prawnId = db.writeBlocking { tx ->
            val id = tx.nextId()
            ProductDao.create(
                tx, Product(id = id, name = "Udang", unit = "kg", sellMode = SellMode.WEIGHT, price = 1290L),
                listOf(Barcode(tx.nextId(), id, "1234", BarcodeKind.SCALE_PLU)), System.currentTimeMillis(),
            )
            id
        }
        val weightLabel = Gtin.withCheckDigit("20" + "01234" + "01253") // 1.253 kg
        val priceLabel = Gtin.withCheckDigit("21" + "01234" + "00326") // RM3.26
        assertTrue(cart.scan(weightLabel) is CartSession.ScanResult.Added)
        assertTrue(cart.scan(priceLabel) is CartSession.ScanResult.Added)
        val items = cart.state.value.cart.items
        assertEquals(listOf(prawnId, prawnId), items.map { it.productId })
        assertEquals(1253L, items[0].qty)
        assertEquals(326L, items[1].fixedGross)
        assertEquals(253L, items[1].qty)
        assertEquals(listOf(1616L, 326L), cart.state.value.priced.lines.map { it.gross })

        // 2026-10 review: a label with no weight or a 0.00 price went on the bill (0.001 kg, or free).
        assertTrue(cart.scan(Gtin.withCheckDigit("20" + "01234" + "00000")) is CartSession.ScanResult.NeedsWeight)
        assertTrue(cart.scan(Gtin.withCheckDigit("21" + "01234" + "00000")) is CartSession.ScanResult.NeedsWeight)
        assertEquals(2, cart.state.value.cart.items.size) // nothing added
    }

    @Test
    fun holdResumeAndDeleteHeldBills() = runBlocking {
        val a = TestDb.sellable(graph.db(), product("A", 100L, "111"))
        val b = TestDb.sellable(graph.db(), product("B", 200L, "222"))
        cart.addProduct(a)
        assertTrue(cart.hold("Ali"))
        assertEquals(1, cart.state.value.heldCount)
        assertTrue(cart.state.value.cart.isEmpty)
        cart.addProduct(b)
        val held = cart.heldBills()
        assertEquals(listOf("Ali" to 100L), held.map { it.label to it.total })
        assertTrue(cart.resume(held[0].id))
        assertEquals(listOf("A"), cart.state.value.cart.items.map { it.name })
        assertEquals(1, cart.state.value.heldCount) // bill B was held in its place
        val other = cart.heldBills().single()
        assertEquals(200L, other.total)
        assertTrue(cart.deleteHeld(other.id))
        assertEquals(0, cart.state.value.heldCount)
        // A parked bill thrown away is a cancelled bill: it is in the audit log with its amount.
        val entry = graph.db().read { AuditDao.byAction(it, AuditAction.BILL_CANCEL, null) }.single()
        assertEquals(200L, entry.amount)
    }

    /** A cashier (PIN login on); the owner's PIN is 2468. */
    private suspend fun signInCashier(): Long {
        graph.staff.load()
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        return cashier
    }

    @Test
    fun aCashierClearsABillAndThrowsAHeldBillAwayWithoutAManager() = runBlocking {
        // D-063: taking every line off never needed a manager, so clearing the bill does not either.
        val cashier = signInCashier()
        cart.addProduct(TestDb.sellable(graph.db(), product("A", 100L, "111")))
        assertTrue(cart.hold("Ali"))
        val held = cart.heldBills().single()
        assertTrue(cart.deleteHeld(held.id))
        assertEquals(0, cart.state.value.heldCount)
        cart.addProduct(TestDb.sellable(graph.db(), product("B", 250L, "222")))
        assertTrue(cart.clear())
        assertTrue(cart.state.value.cart.isEmpty)
        cart.flush()
        // Both are in the activity log under the cashier, with no approval.
        val entries = graph.db().read { AuditDao.byAction(it, AuditAction.BILL_CANCEL, null) }
        assertEquals(setOf(100L, 250L), entries.map { it.amount }.toSet())
        for (e in entries) {
            assertEquals(cashier, e.staffId)
            assertEquals(null, e.approvedBy)
        }
    }

    @Test
    fun aManagersPinHelpsWithOneBill() = runBlocking {
        val cashier = signInCashier()
        val p = graph.permissions
        assertFalse(p.allowed(Perm.DISCOUNT))
        // The cashier's own PIN adds nothing: not a helper.
        assertEquals(null, p.approveHelp(cashier, "1111", 0L).second)
        assertEquals(null, p.approveHelp(Seed.Ids.STAFF_OWNER, "0000", 0L).second) // wrong PIN
        val help = assertNotNull(p.approveHelp(Seed.Ids.STAFF_OWNER, "2468", Perm.MANAGE_PRODUCTS).second)
        p.startHelp(help)
        assertEquals(help, p.helper.value)
        assertTrue(p.allowed(Perm.DISCOUNT))
        assertFalse(p.ownRole(Perm.DISCOUNT))
        cart.addProduct(TestDb.sellable(graph.db(), product("A", 1000L, "111")))
        assertTrue(cart.setBillDiscount(Discount.Amount(100L)))
        cart.flush()
        val discount = graph.db().read { AuditDao.byAction(it, AuditAction.BILL_DISCOUNT, null) }.single()
        assertEquals(cashier, discount.staffId)
        assertEquals(Seed.Ids.STAFF_OWNER, discount.approvedBy)
        // The bill ends (here cleared): the next bill is the cashier's alone.
        assertTrue(cart.clear())
        assertEquals(null, p.helper.value)
        assertFalse(p.allowed(Perm.DISCOUNT))
        // Another bill brought back from the held ones: the help was for the bill now held.
        cart.addProduct(TestDb.sellable(graph.db(), product("B", 500L, "222")))
        assertTrue(cart.hold(null))
        cart.addProduct(TestDb.sellable(graph.db(), product("C", 700L, "333")))
        p.startHelp(help)
        assertTrue(cart.resume(cart.heldBills().single { it.total == 500L }.id))
        assertEquals(null, p.helper.value)
        assertFalse(p.allowed(Perm.DISCOUNT))
        // A lock ends a help too.
        p.startHelp(help)
        graph.staff.lock()
        assertEquals(null, p.helper.value)
        assertFalse(p.allowed(Perm.DISCOUNT))
    }

    @Test
    fun tapsOnPlusAndMinusCountFromTheQuantityTheLineHasNow() = runBlocking {
        val key = cart.addProduct(TestDb.sellable(graph.db(), product("A", 100L, "111")))
        // Five fast taps on + before the list has redrawn once: every one counts.
        repeat(5) { cart.changeQty(key, 1_000L) }
        assertEquals(6_000L, cart.state.value.cart.item(key)?.qty)
        repeat(9) { cart.changeQty(key, -1_000L) }
        assertEquals(1_000L, cart.state.value.cart.item(key)?.qty) // one is the least; "Remove" removes
        assertEquals(100L, cart.state.value.priced.total)
    }

    @Test
    fun anAbsurdQuantityIsRefusedAndNeverBreaksTheBill() = runBlocking {
        val db = graph.db()
        val gold = TestDb.sellable(db, product("Gold bar", 999_999_999L, "777"))
        val roti = TestDb.sellable(db, product("Roti", 350L, "888"))
        val g = cart.addProduct(gold)
        val r = cart.addProduct(roti)
        assertFalse(cart.setQty(g, CartSession.MAX_QTY + 1_000L))
        assertTrue(cart.setQty(g, CartSession.MAX_QTY)) // 99,999 × 9,999,999.99 still has a total
        assertTrue(cart.setQty(g, 2_000L))
        assertEquals(0L, cart.addProduct(roti, qty = CartSession.MAX_QTY)) // would grow the line past the limit
        assertEquals(1_000L, cart.state.value.cart.item(r)?.qty)
        cart.flush()

        // A line stored by an older version whose amount overflows: the bill still loads, without it.
        db.write(reserveIds = 0L) { tx -> tx.update("UPDATE cart_line SET qty = ? WHERE id = ?", 9_000_000_000_000_000L, g) }
        val restarted = TestGraph.reopen(name)
        try {
            restarted.cart.load()
            val st = restarted.cart.state.value
            assertTrue(st.loaded)
            assertEquals(listOf("Roti"), st.cart.items.map { it.name })
            assertEquals(350L, st.priced.total)
            restarted.cart.flush()
            assertEquals(1L, restarted.db().read { it.long("SELECT COUNT(*) FROM cart_line") })
        } finally {
            TestGraph.close(restarted)
        }
    }

    @Test
    fun removingTheLastLineNeverLeavesTheBillDiscountForTheNextCustomer() = runBlocking {
        val a = TestDb.sellable(graph.db(), product("A", 1000L, "444"))
        var key = cart.addProduct(a)
        assertTrue(cart.setBillDiscount(Discount.Percent(1000)))
        cart.remove(key)
        assertTrue(cart.state.value.cart.isEmpty)
        assertEquals(Discount.None, cart.state.value.cart.billDiscount)
        cart.flush()
        assertEquals(0L, graph.db().read { it.long("SELECT COUNT(*) FROM cart") }) // the bill has ended
        assertEquals(1000L, cart.state.value.cart.let { cart.addProduct(a); cart.state.value.priced.total })

        // A bill for a customer keeps the customer (it is on the screen), not the discount.
        key = cart.state.value.cart.items.single().key
        assertTrue(cart.setCustomer(77L, "Pak Abu"))
        assertTrue(cart.setBillDiscount(Discount.Percent(1000)))
        cart.remove(key)
        assertEquals(77L, cart.state.value.customerId)
        assertEquals(Discount.None, cart.state.value.cart.billDiscount)
        cart.addProduct(a)
        assertEquals(1000L, cart.state.value.priced.total)
        cart.flush()

        // … also after a restart.
        val restarted = TestGraph.reopen(name)
        try {
            restarted.cart.load()
            assertEquals(1000L, restarted.cart.state.value.priced.total)
            assertEquals(77L, restarted.cart.state.value.customerId)
        } finally {
            TestGraph.close(restarted)
        }
    }

    @Test
    fun checkoutSavesTheSaleDeletesTheBillAndQueuesDrawerAndReceipt() = runBlocking {
        graph.settings.saveDevice(DeviceSettings(printerAddress = "00:11:22:33:44:55"))
        val milo = TestDb.sellable(graph.db(), product("Milo", 1003L, "333"))
        cart.addProduct(milo)
        val st = cart.state.value
        val given = 2000L
        val s = Settlement.cash(st.priced.total, given, 5L) as Settlement.Result.Settled
        val done = graph.checkout.complete(
            listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, given, s.change)), s.rounding,
        )
        assertEquals(1005L, done.total)
        assertEquals(995L, done.change)
        assertTrue(done.receiptQueued)
        val db = graph.db()
        val header = db.readBlocking { SaleQueries.header(it, done.saleId) }
        assertEquals(1005L, header?.total)
        assertEquals(2L, header?.rounding)
        assertEquals(0L, db.readBlocking { it.long("SELECT COUNT(*) FROM cart") })
        val jobs = db.readBlocking { r -> r.queryList("SELECT kind, ref_id FROM print_job ORDER BY id") { it.getInt(0) to it.getLong(1) } }
        assertEquals(listOf(PrintJobKind.DRAWER to done.saleId, PrintJobKind.RECEIPT to done.saleId), jobs)
        val after = cart.state.value
        assertTrue(after.cart.isEmpty)
        assertTrue(after.canEdit)
    }

    @Test
    fun discountsOverridesAndCancelsAreAudited() = runBlocking {
        val a = TestDb.sellable(graph.db(), product("A", 1000L, "444"))
        val key = cart.addProduct(a)
        assertTrue(cart.setLineDiscount(key, Discount.Percent(1000)))
        assertTrue(cart.overridePrice(key, 900L))
        assertTrue(cart.setBillDiscount(Discount.Amount(100L)))
        assertEquals(710L, cart.state.value.priced.total) // 900 − 10% = 810, − 100
        assertTrue(cart.clear())
        cart.flush()
        val db = graph.db()
        for (action in listOf(AuditAction.LINE_DISCOUNT, AuditAction.PRICE_OVERRIDE, AuditAction.BILL_DISCOUNT, AuditAction.BILL_CANCEL)) {
            assertEquals(1L, db.readBlocking { AuditDao.countByAction(it, action) }, "audit action $action")
        }
        assertEquals(0L, db.readBlocking { it.long("SELECT COUNT(*) FROM cart") })
    }

    @Test
    fun theBillIsFrozenWhilePaying() = runBlocking {
        product("A", 100L, "555")
        cart.setPaying(true)
        assertEquals(CartSession.ScanResult.Busy, cart.scan("555"))
        cart.setPaying(false)
        assertTrue(cart.scan("555") is CartSession.ScanResult.Added)
    }

    @Test
    fun aPromotionThatChangesDuringPaymentAppliesOnlyAfterIt() = runBlocking {
        val a = TestDb.sellable(graph.db(), product("A", 1000L, "666"))
        cart.addProduct(a)
        val before = cart.state.value.priced
        cart.setPaying(true)
        cart.reprice()
        assertTrue(before === cart.state.value.priced) // the amount on the payment screen stays
        cart.setPaying(false)
        assertEquals(1000L, cart.state.value.priced.total)
    }
}
