package com.lekaspos.domain.inventory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.inventory.AdjustReason
import com.lekaspos.core.inventory.ReceiveDraft
import com.lekaspos.core.model.CountSessionStatus
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.MovementKind
import com.lekaspos.data.db.DerivedRebuild
import com.lekaspos.data.db.queryList
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.purchase.PurchaseDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.stock.CountSessionDao
import com.lekaspos.data.stock.HistoryEntry
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.stock.StockHistoryDao
import com.lekaspos.data.supplier.Supplier
import com.lekaspos.data.supplier.SupplierDao
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryTest {

    private lateinit var graph: AppGraph
    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

    @Before
    fun setUp() {
        graph = TestGraph.create()
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    private fun level(id: Long) = runBlocking { graph.db().read { StockDao.level(it, id) } }

    private fun cost(id: Long) = runBlocking { graph.db().read { ProductDao.get(it, id) }?.cost }

    @Test
    fun receivingAddsStockAveragesCostAndSyncsOneEvent() = runBlocking {
        val db = graph.db()
        db.syncEnabled = true
        val milo = TestDb.product(db, "Milo", 1_890L, cost = 100L)
        val roti = TestDb.product(db, "Roti", 350L, cost = 200L)
        db.writeBlocking { tx -> StockDao.insertMovement(tx, milo, MovementKind.OPENING, 10_000L, 100L, null, null, null, 1L) }
        val supplier = db.writeBlocking { tx -> SupplierDao.insert(tx, Supplier(0L, "Nestle Borong"), System.currentTimeMillis()) }
        var d = ReceiveDraft(supplierId = supplier, refNo = "INV-77")
        d = d.add(1L, milo, "Milo", "pcs", 30_000L, 120L).first // 30 at 1.20
        d = d.add(2L, roti, "Roti", "pcs", 12_000L, 150L).first
        d = d.setTotal(2L, 1_700L) // invoice amount for the bread
        graph.inventory.saveDraft(d)
        assertEquals(d, graph.inventory.loadDraft())
        val before = db.readBlocking { r -> r.queryList("SELECT seq FROM outbox") { it.getLong(0) } }.size

        val id = graph.inventory.receive(d)

        assertEquals(40_000L, level(milo))
        assertEquals(12_000L, level(roti))
        assertEquals(115L, cost(milo)) // (10 × 1.00 + 36.00) / 40
        assertEquals(142L, cost(roti)) // nothing on hand: 17.00 / 12 = 1.4167
        val row = assertNotNull(db.readBlocking { PurchaseDao.get(it, id) })
        assertEquals("Nestle Borong", row.supplierName)
        assertEquals(3_600L + 1_700L, row.total)
        assertEquals(2, row.lineCount)
        val lines = db.readBlocking { PurchaseDao.lines(it, id) }
        // Each line's movement carries the line's id (sync regenerates movements from the purchase).
        val moveIds = db.readBlocking { r -> r.queryList("SELECT id FROM stock_movement WHERE ref_id = ? ORDER BY id", arrayOf(id.toString())) { it.getLong(0) } }
        assertEquals(lines.map { it.id }, moveIds)
        val events = db.readBlocking { r -> r.queryList("SELECT entity FROM outbox") { it.getInt(0) } }.drop(before)
        assertEquals(1, events.count { it == Entity.PURCHASE })
        assertEquals(0, events.count { it == Entity.STOCK_MOVE })
        assertTrue(events.contains(Entity.PRODUCT)) // the cost updates
        assertTrue(graph.inventory.loadDraft().isEmpty)
        assertEquals(listOf(id), db.readBlocking { PurchaseDao.page(it, supplier, null, 10) }.map { it.id })
        assertEquals(listOf(id), db.readBlocking { PurchaseDao.page(it, null, null, 10) }.map { it.id })
    }

    @Test
    fun adjustmentsCountsAndHistory() = runBlocking {
        val db = graph.db()
        db.syncEnabled = true
        val p = TestDb.product(db, "Susu", 500L, cost = 300L)
        db.writeBlocking { tx -> StockDao.insertMovement(tx, p, MovementKind.OPENING, 20_000L, 300L, null, null, null, 1L) }
        graph.inventory.adjust(p, AdjustReason.DAMAGED, 3_000L, removing = false, note = "box crushed")
        assertEquals(17_000L, level(p))
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 2_000L)), tz) }
        assertEquals(15_000L, level(p))

        val session = graph.inventory.startCount("Rak A", null)
        graph.inventory.count(session, p, 14_000L) // one missing
        assertEquals(14_000L, level(p))
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L)), tz) }
        assertEquals(13_000L, level(p)) // sales after the count still count
        graph.inventory.finishCount(session)
        val s = assertNotNull(db.readBlocking { CountSessionDao.get(it, session) })
        assertEquals(CountSessionStatus.FINISHED, s.status)
        assertEquals(1, s.counted)
        val count = db.readBlocking { CountSessionDao.counts(it, session, null, 10) }.single()
        assertEquals(15_000L, count.expected)
        assertEquals(300L, count.unitCost)
        assertEquals(mapOf(p to 14_000L), db.readBlocking { CountSessionDao.countedQty(it, session) })

        // Movement log and reason text
        val moves = db.readBlocking { StockDao.movementPage(it, null, 10) }
        assertEquals(MovementKind.WASTE, moves.first().kind)
        assertEquals(AdjustReason.DAMAGED to "box crushed", AdjustReason.decode(moves.first().reason))

        // History, newest first, paged two at a time without gaps or repeats.
        val all = ArrayList<HistoryEntry>()
        var after: HistoryEntry? = null
        while (true) {
            val page = db.readBlocking { StockHistoryDao.page(it, p, after, 2) }
            all.addAll(page)
            if (page.size < 2) break
            after = page.last()
        }
        assertEquals(
            listOf(HistoryEntry.Type.SALE, HistoryEntry.Type.COUNT, HistoryEntry.Type.SALE, HistoryEntry.Type.MOVEMENT, HistoryEntry.Type.MOVEMENT),
            all.map { it.type },
        )
        assertEquals(listOf(-1_000L, 0L, -2_000L, -3_000L, 20_000L), all.map { it.delta })
        assertEquals(sale.id, all[2].refId)

        // Cached levels equal a rebuild from events.
        db.writeBlocking { tx -> DerivedRebuild.stockLevels(tx) }
        assertEquals(13_000L, level(p))
        val kinds = db.readBlocking { r -> r.queryList("SELECT entity FROM outbox") { it.getInt(0) } }
        assertTrue(kinds.contains(Entity.STOCK_MOVE) && kinds.contains(Entity.STOCK_COUNT) && kinds.contains(Entity.COUNT_SESSION))
    }

    @Test
    fun lowStockAlerts() = runBlocking {
        val db = graph.db()
        val ids = (1..5).map { i ->
            val id = TestDb.product(db, "Item $i", 100L)
            db.writeBlocking { tx ->
                tx.exec("UPDATE product SET low_stock = 5000 WHERE id = ?", id)
                StockDao.insertMovement(tx, id, MovementKind.OPENING, (i * 2L) * 1_000L, 50L, null, null, null, 1L)
            }
            id
        }
        // Stocks 2, 4, 6, 8, 10 with alert at 5 → items 1 and 2 are low.
        assertEquals(2L, db.readBlocking { StockDao.lowStockCount(it) })
        val first = db.readBlocking { StockDao.lowStockPage(it, null, 1) }
        val second = db.readBlocking { StockDao.lowStockPage(it, first.last(), 1) }
        assertEquals(listOf(ids[0], ids[1]), (first + second).map { it.productId })
        assertEquals(listOf(ids[1]), db.readBlocking { StockDao.lowAmong(it, listOf(ids[1], ids[3])) }.map { it.productId })
    }

    @Test
    fun suppliersAreEditable() = runBlocking {
        val db = graph.db()
        val id = db.writeBlocking { tx -> SupplierDao.insert(tx, Supplier(0L, "Kilang Roti", phone = "03-1234"), 1L) }
        val s = assertNotNull(db.readBlocking { SupplierDao.get(it, id) })
        db.writeBlocking { tx -> SupplierDao.update(tx, s, s.copy(contact = "Ah Seng"), 2L) }
        assertEquals("Ah Seng", db.readBlocking { SupplierDao.list(it) }.single().contact)
        db.writeBlocking { tx -> SupplierDao.delete(tx, id, 3L) }
        assertEquals(0, db.readBlocking { SupplierDao.list(it) }.size)
    }
}
