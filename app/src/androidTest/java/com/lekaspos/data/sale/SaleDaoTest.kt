package com.lekaspos.data.sale

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.long
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.testing.TestDb
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SaleDaoTest {

    private lateinit var db: Db
    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private var a = 0L
    private var b = 0L
    private var untracked = 0L

    @Before
    fun setUp() {
        db = TestDb.fresh()
        a = TestDb.product(db, "Roti Gardenia", 350)
        b = TestDb.product(db, "Susu Dutch Lady 1L", 1_003)
        untracked = TestDb.product(db, "Plastic bag", 20, trackStock = false)
        db.writeBlocking { tx -> StockDao.insertMovement(tx, a, MovementKind.RECEIVE, 10_000, 200, null, null, null, System.currentTimeMillis()) }
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    private fun commit(draft: SaleDraft) = db.writeBlocking { tx -> SaleDao.commit(tx, draft, tz) }

    private fun count(sql: String, vararg args: Any?) = db.readBlocking { it.long(sql, *args) }

    @Test
    fun commitStoresLinesPaymentsStockAndSummariesTogether() {
        val draft = TestDb.saleDraft(db, listOf(a to 2_000L, b to 1_000L, untracked to 1_000L))
        val sale = commit(draft)
        assertEquals(1L, count("SELECT COUNT(*) FROM sale WHERE id = ?", sale.id))
        assertEquals(3L, count("SELECT COUNT(*) FROM sale_line WHERE sale_id = ?", sale.id))
        assertEquals(draft.total, count("SELECT SUM(amount) FROM payment WHERE sale_id = ?", sale.id))
        assertEquals(8_000L, db.readBlocking { StockDao.level(it, a) })
        assertEquals(-1_000L, db.readBlocking { StockDao.level(it, b) }) // selling below zero is allowed
        assertEquals(0L, count("SELECT COUNT(*) FROM stock_level WHERE product_id = ?", untracked))

        val day = Days.epochDay(draft.soldAt, tz)
        val totals = db.readBlocking { ReportDao.totals(it, day, day + 1) }
        assertEquals(1L, totals.saleCount)
        assertEquals(draft.total, totals.total)
        assertEquals(draft.subtotal - draft.discount - draft.tax, totals.netEx)
        assertEquals(draft.lines.sumOf { it.cost }, totals.cost)
        assertEquals(draft.rounding, totals.rounding)
        val byProduct = db.readBlocking { ReportDao.topProducts(it, day, day + 1, 10) }
        assertEquals(2_000L, byProduct.first { it.productId == a }.qty)
        val byPayment = db.readBlocking { ReportDao.byPayment(it, day, day + 1) }
        assertEquals(draft.total, byPayment.single().amount)
    }

    @Test
    fun receiptNumbersArePerDeviceAndPerKind() {
        val s1 = commit(TestDb.saleDraft(db, listOf(a to 1_000L)))
        val s2 = commit(TestDb.saleDraft(db, listOf(a to 1_000L)))
        val prefix = db.readBlocking { Meta.get(it, Meta.RECEIPT_PREFIX) }!!
        assertEquals(prefix + "000001", s1.receiptNo)
        assertEquals(prefix + "000002", s2.receiptNo)
        val refund = commit(refundOf(s1.id))
        assertEquals(prefix + "R000001", refund.receiptNo)
        assertEquals(s2.id, db.readBlocking { SaleDao.byReceipt(it, s2.receiptNo) }?.id)
    }

    @Test
    fun outboxIsWrittenOnlyWhileSyncIsEnabled() {
        commit(TestDb.saleDraft(db, listOf(a to 1_000L)))
        assertEquals(0L, count("SELECT COUNT(*) FROM outbox"))
        db.syncEnabled = true
        val sale = commit(TestDb.saleDraft(db, listOf(a to 1_000L)))
        val payload = db.readBlocking { it.stringOrNull("SELECT payload FROM outbox WHERE row_id = ?", sale.id) }!!
        assertTrue(payload.contains("\"receipt_no\":\"${sale.receiptNo}\""))
        assertTrue(payload.contains("\"lines\":[{"))
        assertTrue(payload.contains("\"pays\":[{"))
    }

    @Test
    fun aFailedTransactionLeavesNoTrace() {
        val stockBefore = db.readBlocking { StockDao.level(it, a) }
        assertFailsWith<IllegalStateException> {
            db.writeBlocking { tx ->
                SaleDao.commit(tx, TestDb.saleDraft(db, listOf(a to 1_000L)), tz)
                error("power cut simulation")
            }
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM sale"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sum_day"))
        assertEquals(stockBefore, db.readBlocking { StockDao.level(it, a) })
        val next = commit(TestDb.saleDraft(db, listOf(a to 1_000L)))
        assertTrue(next.receiptNo.endsWith("000001"), "receipt sequence must not skip after a rollback")
    }

    @Test
    fun paymentsMustAddUpToTheTotal() {
        val draft = TestDb.saleDraft(db, listOf(a to 1_000L))
        assertFailsWith<IllegalArgumentException> { commit(draft.copy(payments = listOf(draft.payments[0].copy(amount = draft.total - 1)))) }
        assertEquals(0L, count("SELECT COUNT(*) FROM sale"))
    }

    private fun refundOf(saleId: Long): SaleDraft {
        val line = db.readBlocking { SaleDao.lines(it, saleId) }.first()
        val net = line.net
        val now = System.currentTimeMillis()
        return SaleDraft(
            kind = SaleKind.REFUND, refSaleId = saleId, openedAt = now, soldAt = now, pricesInclTax = true,
            subtotal = -line.gross, discount = -(line.gross - net), tax = -line.tax, rounding = 0, total = -net, paid = -net, change = 0,
            lines = listOf(
                SaleLineDraft(
                    productId = line.productId, refLineId = line.id, name = line.name, qty = -line.qty, baseQty = -line.qty,
                    unitPrice = line.unitPrice, gross = -line.gross, net = -net, tax = -line.tax,
                ),
            ),
            payments = listOf(PaymentDraft(com.lekaspos.data.db.Seed.Ids.PM_CARD, PaymentKind.CARD, -net)),
        )
    }

    @Test
    fun refundRestocksAndNetsOutInReports() {
        val sale = commit(TestDb.saleDraft(db, listOf(a to 3_000L), payKind = PaymentKind.CARD))
        assertEquals(7_000L, db.readBlocking { StockDao.level(it, a) })
        val refundDraft = refundOf(sale.id)
        commit(refundDraft)
        assertEquals(10_000L, db.readBlocking { StockDao.level(it, a) })
        assertEquals(-refundDraft.total, db.readBlocking { SaleDao.refunded(it, sale.id) })
        val day = Days.epochDay(System.currentTimeMillis(), tz)
        val t = db.readBlocking { ReportDao.totals(it, day, day + 1) }
        assertEquals(1L, t.saleCount)
        assertEquals(1L, t.refundCount)
        assertEquals(0L, t.total)
        assertEquals(-refundDraft.total, t.refundTotal)
    }

    @Test
    fun voidReversesStockAndSummariesAndIsIdempotent() {
        val keep = TestDb.saleDraft(db, listOf(b to 1_000L))
        commit(keep)
        val sale = commit(TestDb.saleDraft(db, listOf(a to 4_000L)))
        assertEquals(6_000L, db.readBlocking { StockDao.level(it, a) })
        assertTrue(db.writeBlocking { tx -> SaleDao.void(tx, sale.id, "Wrong item", null, null, null, System.currentTimeMillis()) })
        assertFalse(db.writeBlocking { tx -> SaleDao.void(tx, sale.id, "Again", null, null, null, System.currentTimeMillis()) })
        assertTrue(db.readBlocking { SaleDao.isVoided(it, sale.id) })
        assertEquals(10_000L, db.readBlocking { StockDao.level(it, a) })
        val day = sale.day
        val t = db.readBlocking { ReportDao.totals(it, day, day + 1) }
        assertEquals(1L, t.saleCount)
        assertEquals(1L, t.voidCount)
        assertEquals(keep.total, t.total)
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_void WHERE sale_id = ?", sale.id))
        assertEquals(SaleStatus.VOIDED.toLong(), count("SELECT status FROM sale WHERE id = ?", sale.id))
    }

    @Test
    fun historyPagesVisitEverySaleOnceNewestFirst() {
        val base = System.currentTimeMillis() - 3_600_000L
        val ids = (0 until 120).map { i -> commit(TestDb.saleDraft(db, listOf(a to 1_000L), soldAt = base + (i / 3) * 1_000L)).id } // ties on sold_at
        val seen = ArrayList<SaleRow>()
        var last: SaleRow? = null
        while (true) {
            val page = db.readBlocking { SaleDao.history(it, last, 50) }
            if (page.isEmpty()) break
            seen.addAll(page)
            last = page.last()
        }
        assertEquals(ids.toSet(), seen.map { it.id }.toSet())
        assertEquals(ids.size, seen.size)
        assertTrue(seen.zipWithNext().all { (x, y) -> x.soldAt > y.soldAt || (x.soldAt == y.soldAt && x.id > y.id) })
    }

    @Test
    fun productHistoryPagesVisitEveryLineOnce() {
        repeat(75) { commit(TestDb.saleDraft(db, listOf(a to 1_000L, b to 1_000L, a to 2_000L))) }
        val seen = ArrayList<Long>()
        var last: ProductSaleRow? = null
        while (true) {
            val page = db.readBlocking { SaleDao.productHistory(it, a, last, 40) }
            if (page.isEmpty()) break
            seen.addAll(page.map { it.lineId })
            last = page.last()
        }
        assertEquals(150, seen.size)
        assertEquals(150, seen.toSet().size)
    }
}
