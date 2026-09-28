package com.lekaspos.domain.sell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.PaymentKind
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
        cart.deleteHeld(other.id)
        assertEquals(0, cart.state.value.heldCount)
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
}
