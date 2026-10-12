package com.lekaspos.domain.promo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.PromoKind
import com.lekaspos.core.pricing.PriceTier
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Seed
import com.lekaspos.data.promo.PromotionDao
import com.lekaspos.data.promo.PromotionRow
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Promotions end to end (Phase 8): bill, sale lines, receipt, dates, switching off. */
@RunWith(AndroidJUnit4::class)
class PromotionFlowTest {

    private lateinit var graph: AppGraph
    private val tz = TimeZone.getDefault()
    private val today get() = Days.epochDay(System.currentTimeMillis(), tz)

    @Before
    fun setUp() {
        graph = TestGraph.create()
        runBlocking { graph.cart.load() }
    }

    @After
    fun tearDown() = TestGraph.destroy(graph)

    private suspend fun pay(): CheckoutService.Done {
        val total = graph.cart.state.value.priced.total
        val s = Settlement.cash(total, total + 1_000L, 5L) as Settlement.Result.Settled
        return graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, total + 1_000L, s.change)), s.rounding)
    }

    private fun promo(name: String, products: List<Long>, start: Long? = null, end: Long? = null, active: Boolean = true) =
        PromotionRow(0L, name, PromoKind.MULTI_PRICE, 3, 0, 1_000L, products, start, end, active)

    @Test
    fun aPromotionPricesTheBillAndIsKeptOnTheSaleAndReceipt() = runBlocking {
        val db = graph.db()
        val milo = TestDb.product(db, "Milo", 390L, listOf("9556001000011"))
        TestDb.product(db, "Roti", 350L, listOf("9556001000028"))
        val id = graph.promotions.save(null, promo("Milo 3 for RM10", listOf(milo)))
        repeat(3) { graph.cart.scan("9556001000011") }
        graph.cart.scan("9556001000028")
        val priced = graph.cart.state.value.priced
        assertEquals(1_350L, priced.total) // 1170 → 1000, plus 350
        assertEquals("Milo 3 for RM10", priced.promotions[0]?.name)

        val done = pay()
        val lines = db.read { SaleQueries.lines(it, done.saleId) }
        assertEquals("Milo 3 for RM10", lines[0].promoName)
        assertEquals(170L, lines[0].discount)
        assertEquals(1_000L, lines[0].net)
        assertNull(lines[1].promoName)
        val receipt = assertNotNull(db.read { ReceiptBuilder.build(it, done.saleId, false, graph.settings.store.value, tz) })
        assertEquals("Milo 3 for RM10", receipt.items[0].promo)

        // Deleted: the next bill is at the shelf price; the sale made keeps its price.
        graph.promotions.delete(id)
        repeat(3) { graph.cart.scan("9556001000011") }
        assertEquals(1_170L, graph.cart.state.value.priced.total)
        assertEquals(1_000L, db.read { SaleQueries.lines(it, done.saleId) }[0].net)
    }

    @Test
    fun onlyRunningPromotionsApply() = runBlocking {
        val db = graph.db()
        val milo = TestDb.product(db, "Milo", 390L)
        val sellable = TestDb.sellable(db, milo)
        graph.promotions.save(null, promo("Off", listOf(milo), active = false))
        graph.promotions.save(null, promo("Ended", listOf(milo), end = today - 1))
        graph.promotions.save(null, promo("Later", listOf(milo), start = today + 1))
        graph.cart.addProduct(sellable, qty = 3_000L)
        assertEquals(1_170L, graph.cart.state.value.priced.total)
        // One that runs today reprices the open bill straight away.
        graph.promotions.save(null, promo("Today", listOf(milo), start = today, end = today))
        assertEquals(1_000L, graph.cart.state.value.priced.total)
        assertEquals("Today", graph.cart.state.value.priced.promotions[0]?.name)
    }

    /** D-073: "1 for RM2, 2 for RM3, 3 for RM4" charged RM5 for 3 — one promotion with more prices. */
    @Test
    fun morePricesAreStoredAndTheBillTakesTheCheapestMix() = runBlocking {
        val db = graph.db()
        val egg = TestDb.product(db, "Telur", 200L)
        val sellable = TestDb.sellable(db, egg)
        val row = PromotionRow(0L, "Telur murah", PromoKind.MULTI_PRICE, 2, 0, 300L, listOf(egg), morePrices = listOf(PriceTier(3, 400L)))
        val id = graph.promotions.save(null, row)
        assertEquals(listOf(PriceTier(3, 400L)), db.read { PromotionDao.get(it, id) }?.morePrices)
        graph.cart.addProduct(sellable, qty = 3_000L)
        assertEquals(400L, graph.cart.state.value.priced.total)
        graph.cart.addProduct(sellable, qty = 2_000L) // 5 = 3 + 2
        assertEquals(700L, graph.cart.state.value.priced.total)
        val done = pay()
        assertEquals("Telur murah", db.read { SaleQueries.lines(it, done.saleId) }[0].promoName)

        // The further price taken out: back to "2 for RM3" only, also after a reload from the database.
        val saved = assertNotNull(graph.promotions.all().firstOrNull { it.id == id })
        graph.promotions.save(saved, saved.copy(morePrices = emptyList()))
        graph.promotions.load()
        assertEquals(emptyList(), graph.promotions.all().first { it.id == id }.morePrices)
        graph.cart.addProduct(sellable, qty = 3_000L)
        assertEquals(500L, graph.cart.state.value.priced.total)
    }

    /** Two promotions on the same product, as a shop made them before 1.15.0, combine into one ladder. */
    @Test
    fun twoMultiBuysOnTheSameProductCombine() = runBlocking {
        val db = graph.db()
        val egg = TestDb.product(db, "Telur", 200L)
        graph.promotions.save(null, PromotionRow(0L, "2 for RM3", PromoKind.MULTI_PRICE, 2, 0, 300L, listOf(egg)))
        graph.promotions.save(null, PromotionRow(0L, "3 for RM4", PromoKind.MULTI_PRICE, 3, 0, 400L, listOf(egg)))
        graph.cart.addProduct(TestDb.sellable(db, egg), qty = 3_000L)
        assertEquals(400L, graph.cart.state.value.priced.total)
        assertEquals("3 for RM4", graph.cart.state.value.priced.promotions[0]?.name)
    }
}
