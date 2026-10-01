package com.lekaspos.domain.sale

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SellMode
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.queryList
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.data.stock.StockDao
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaleActionsTest {

    private lateinit var graph: AppGraph
    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private val cash = PaymentMethod(Seed.Ids.PM_CASH, "Cash", PaymentKind.CASH, true, 1)
    private val card = PaymentMethod(Seed.Ids.PM_CARD, "Card", PaymentKind.CARD, false, 2)

    @Before
    fun setUp() {
        graph = TestGraph.create()
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    private fun refused(reason: ActionRefused.Reason, block: suspend () -> Unit) {
        val e = assertFailsWith<ActionRefused> { runBlocking { block() } }
        assertEquals(reason, e.reason)
    }

    @Test
    fun partialRefundsAddUpAndVoidsFollowTheRules() = runBlocking {
        val db = graph.db()
        val susu = TestDb.product(db, "Susu", 1003L, listOf("9556"))
        db.writeBlocking { tx -> StockDao.insertMovement(tx, susu, MovementKind.RECEIVE, 10_000L, 500L, null, null, null, System.currentTimeMillis()) }
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(susu to 3_000L)), tz) }
        assertEquals(7_000L, db.readBlocking { StockDao.level(it, susu) })

        val info = assertNotNull(graph.sales.refundInfo(sale.id))
        val lineId = info.lines.single().id
        val r1 = graph.sales.refund(sale.id, mapOf(lineId to 1_000L), restock = true, reason = "damaged", method = cash)
        val h1 = assertNotNull(db.readBlocking { SaleQueries.header(it, r1.id) })
        assertEquals(SaleKind.REFUND, h1.kind)
        assertEquals(-1_005L, h1.total) // 1003 owed, paid out as 1005 in cash
        assertEquals(-2L, h1.rounding)
        assertEquals(8_000L, db.readBlocking { StockDao.level(it, susu) })

        val r2 = graph.sales.refund(sale.id, mapOf(lineId to 2_000L), restock = false, reason = "changed mind", method = card)
        val h2 = assertNotNull(db.readBlocking { SaleQueries.header(it, r2.id) })
        assertEquals(-2_006L, h2.total) // exactly what is left: 3009 − 1003
        assertEquals(8_000L, db.readBlocking { StockDao.level(it, susu) }) // not restocked
        val done = db.readBlocking { SaleQueries.refundedByLine(it, sale.id) }.getValue(lineId)
        assertEquals(3_000L, done.qty)
        assertEquals(3_009L, done.net)

        refused(ActionRefused.Reason.NOTHING_TO_REFUND) { graph.sales.refund(sale.id, mapOf(lineId to 1_000L), true, "again", cash) }
        refused(ActionRefused.Reason.NOT_A_SALE) { graph.sales.refund(r1.id, mapOf(lineId to 1_000L), true, "x", cash) }
        refused(ActionRefused.Reason.HAS_REFUNDS) { graph.sales.void(sale.id, "wrong sale") }

        graph.sales.void(r1.id, "mistake")
        graph.sales.void(r2.id, "mistake")
        assertEquals(0L, db.readBlocking { SaleDao.refunded(it, sale.id) })
        graph.sales.void(sale.id, "wrong sale")
        refused(ActionRefused.Reason.VOIDED) { graph.sales.void(sale.id, "twice") }
        assertEquals(10_000L, db.readBlocking { StockDao.level(it, susu) })
        assertEquals(2L, db.readBlocking { AuditDao.countByAction(it, AuditAction.REFUND) })
        assertEquals(3L, db.readBlocking { AuditDao.countByAction(it, AuditAction.SALE_VOID) })
    }

    @Test
    fun copiesAndDrawerOpensAreQueuedAndAudited() = runBlocking {
        val db = graph.db()
        val a = TestDb.product(db, "A", 500L)
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(a to 1_000L)), tz) }
        graph.sales.print(sale.id, copy = false) // the first receipt
        graph.sales.print(sale.id, copy = false) // asked again (second tap, rotated screen): a copy
        graph.sales.print(sale.id, copy = true)
        graph.sales.openDrawer()
        val jobs = db.readBlocking { r -> r.queryList("SELECT kind FROM print_job ORDER BY id") { it.getInt(0) } }
        assertEquals(listOf(PrintJobKind.RECEIPT, PrintJobKind.REPRINT, PrintJobKind.REPRINT, PrintJobKind.DRAWER), jobs)
        assertEquals(2L, db.readBlocking { AuditDao.countByAction(it, AuditAction.REPRINT) })
        assertEquals(1L, db.readBlocking { AuditDao.countByAction(it, AuditAction.DRAWER_OPEN) })
    }

    @Test
    fun aReturnWorthNothingStillBlocksTheVoid() = runBlocking {
        val db = graph.db()
        val gift = TestDb.product(db, "Free gift", 0L)
        val milo = TestDb.product(db, "Milo", 1_000L)
        db.writeBlocking { tx -> StockDao.insertMovement(tx, gift, MovementKind.RECEIVE, 5_000L, 0L, null, null, null, System.currentTimeMillis()) }
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(milo to 1_000L, gift to 2_000L)), tz) }
        val lineId = assertNotNull(graph.sales.refundInfo(sale.id)).lines.single { it.productId == gift }.id
        val back = graph.sales.refund(sale.id, mapOf(lineId to 1_000L), restock = true, reason = "not wanted", method = cash)
        assertEquals(0L, db.readBlocking { SaleDao.refunded(it, sale.id) })
        assertEquals(4_000L, db.readBlocking { StockDao.level(it, gift) })

        // Voiding the sale now would put all 2 back on top of the 1 already returned.
        refused(ActionRefused.Reason.HAS_REFUNDS) { graph.sales.void(sale.id, "wrong sale") }
        graph.sales.void(back.id, "mistake")
        graph.sales.void(sale.id, "wrong sale")
        assertEquals(5_000L, db.readBlocking { StockDao.level(it, gift) })
    }

    @Test
    fun aWholeKiloOfAWeighedProductIsReturnedByWeight() = runBlocking {
        val db = graph.db()
        val ayam = TestDb.product(db, "Ayam", 1_290L)
        val roti = TestDb.product(db, "Roti", 350L)
        val udang = TestDb.product(db, "Udang", 4_500L)
        db.writeBlocking { tx -> tx.update("UPDATE product SET sell_mode = ? WHERE id = ?", SellMode.WEIGHT, ayam) }
        // 1.000 kg of chicken, 2 loaves, 0.250 kg of prawns from a product no longer sold by weight.
        val items = listOf(ayam to 1_000L, roti to 2_000L, udang to 250L)
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, items), tz) }
        val info = assertNotNull(graph.sales.refundInfo(sale.id))
        val line = info.lines.associateBy { it.productId }
        assertEquals(setOf(line.getValue(ayam).id, line.getValue(udang).id), info.weighed)
    }
}
