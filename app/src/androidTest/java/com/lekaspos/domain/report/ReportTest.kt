package com.lekaspos.domain.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.csv.CsvReader
import com.lekaspos.core.csv.CsvWriter
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.report.Granularity
import com.lekaspos.core.report.Months
import com.lekaspos.core.report.MonthSplit
import com.lekaspos.core.report.Period
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.queryList
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.io.StringReader
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
class ReportTest {

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

    private fun at(ymd: Int, hour: Int = 12) = Days.startOfDay(Days.fromYmd(ymd), tz) + hour * 3_600_000L

    /** Sales on both sides of month ends (and a leap day) of [a] and [b]. */
    private fun sell(a: Long, b: Long): List<Long> = runBlocking {
        val db = graph.db()
        val ids = ArrayList<Long>()
        for ((ymd, pair) in listOf(
            20240229 to (a to 2_000L), 20251231 to (a to 1_000L), 20260101 to (b to 3_000L), 20260115 to (a to 1_000L),
            20260131 to (b to 1_000L), 20260201 to (a to 4_000L), 20260228 to (b to 2_000L), 20260301 to (a to 1_000L),
        )) {
            ids.add(db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(pair), soldAt = at(ymd)), tz) }.id)
        }
        ids
    }

    @Test
    fun theMonthTableMatchesTheDaysItIsMadeOf() = runBlocking {
        val db = graph.db()
        val a = TestDb.product(db, "Kopi", 1_250L)
        val b = TestDb.product(db, "Teh", 990L)
        val ids = sell(a, b)
        db.writeBlocking { tx -> SaleDao.void(tx, ids[2], "wrong", null, null, null, at(20260102)) }
        val byDays = db.readBlocking { r ->
            r.queryList("SELECT day, product_id, qty, net_ex FROM sum_day_product") { it.getLong(0) to longArrayOf(it.getLong(1), it.getLong(2), it.getLong(3)) }
        }.groupBy({ Months.key(it.first) to it.second[0] }, { it.second }).mapValues { (_, v) -> v.sumOf { it[1] } to v.sumOf { it[2] } }
            .filterValues { it.first != 0L || it.second != 0L }
        val months = db.readBlocking { r ->
            r.queryList("SELECT month, product_id, qty, net_ex FROM sum_month_product WHERE qty != 0 OR net_ex != 0") {
                (it.getInt(0) to it.getLong(1)) to (it.getLong(2) to it.getLong(3))
            }
        }.toMap()
        assertEquals(byDays, months)
        assertEquals(setOf(202402, 202512, 202601, 202602, 202603), months.keys.map { it.first }.toSet())
        // SQLite's calendar (the rebuild) agrees with :core's on every day, leap days included.
        for (ymd in listOf(20240229, 20241231, 20250101, 20260131, 20260201, 20261231)) {
            val sql = db.readBlocking { r -> r.queryList("SELECT CAST(strftime('%Y%m', ? * 86400, 'unixepoch') AS INTEGER)", arrayOf(Days.fromYmd(ymd).toString())) { it.getInt(0) } }.single()
            assertEquals(ymd / 100, sql)
        }
    }

    @Test
    fun splitRangesGiveTheSameAnswerAsDays() = runBlocking {
        val db = graph.db()
        val a = TestDb.product(db, "Kopi", 1_250L)
        val b = TestDb.product(db, "Teh", 990L)
        sell(a, b)
        for (p in listOf(
            Period(Days.fromYmd(20251215), Days.fromYmd(20260310)),
            Period(Days.fromYmd(20260101), Days.fromYmd(20260201)),
            Period(Days.fromYmd(20240101), Days.fromYmd(20270101)),
            Period(Days.fromYmd(20260114), Days.fromYmd(20260116)),
        )) {
            val split = db.readBlocking { ReportDao.products(it, MonthSplit.of(p), limit = 100) }.associate { it.productId to (it.qty to it.netEx) }
            val days = db.readBlocking { r ->
                r.queryList(
                    "SELECT product_id, SUM(qty), SUM(net_ex) FROM sum_day_product WHERE day >= ? AND day < ? GROUP BY product_id",
                    arrayOf(p.from.toString(), p.to.toString()),
                ) { it.getLong(0) to (it.getLong(1) to it.getLong(2)) }
            }.toMap()
            assertEquals(days, split, "products of $p")
            val cats = db.readBlocking { ReportDao.byCategory(it, MonthSplit.of(p)) }
            assertEquals(days.values.sumOf { it.second }, cats.sumOf { it.netEx })
            // The limit keeps the best sellers: by net sales, or by quantity.
            val bestByNet = days.maxByOrNull { it.value.second }?.key
            assertEquals(bestByNet, db.readBlocking { ReportDao.products(it, MonthSplit.of(p), limit = 1) }.singleOrNull()?.productId)
            val bestByQty = days.maxByOrNull { it.value.first }?.key
            assertEquals(bestByQty, db.readBlocking { ReportDao.products(it, MonthSplit.of(p), byQty = true, limit = 1) }.singleOrNull()?.productId)
        }
    }

    @Test
    fun theReportScreenTotalsBucketsAndPermission() = runBlocking {
        val db = graph.db()
        val a = TestDb.product(db, "Kopi", 1_250L, cost = 700L)
        val b = TestDb.product(db, "Teh", 990L, cost = 400L)
        sell(a, b)
        graph.staff.load()
        val jan = Period(Days.fromYmd(20260101), Days.fromYmd(20260201))
        val rep = graph.reports.build(jan)
        assertEquals(3L, rep.totals.saleCount)
        assertEquals(Granularity.DAY, rep.granularity)
        assertEquals(31, rep.buckets.size)
        assertEquals(rep.totals.total, rep.buckets.sumOf { it.total })
        assertEquals(rep.totals.netEx - rep.totals.cost, rep.totals.grossProfit)
        assertNotNull(rep.marginBp)
        assertEquals(listOf(b, a), rep.topProducts.map { it.productId }) // Teh 4 × 9.90 > Kopi 1 × 12.50
        val year = graph.reports.build(Period(Days.fromYmd(20250101), Days.fromYmd(20270101)))
        assertEquals(Granularity.MONTH, year.granularity)
        assertEquals(24, year.buckets.size)

        // A cashier may not see profit; a manager may.
        graph.staffAdmin.setPin(Seed.Ids.STAFF_OWNER, "2468")
        val cashier = graph.staffAdmin.save(null, "Siti", Seed.Ids.ROLE_CASHIER, true)
        graph.staffAdmin.setPin(cashier, "1111")
        val manager = graph.staffAdmin.save(null, "Ah Kow", Seed.Ids.ROLE_MANAGER, true)
        graph.staffAdmin.setPin(manager, "5555")
        graph.staff.lock()
        graph.staff.signIn(cashier, "1111")
        assertEquals(ActionRefused.Reason.NOT_ALLOWED, assertFailsWith<ActionRefused> { graph.reports.build(jan) }.reason)
        graph.staff.lock()
        graph.staff.signIn(manager, "5555")
        assertTrue(graph.permissions.allowed(Perm.REPORTS))
        graph.reports.build(jan)
        Unit
    }

    @Test
    fun slowMoversAndStockValue() = runBlocking {
        val db = graph.db()
        val sold = TestDb.product(db, "Laris", 500L, cost = 300L)
        val idle = TestDb.product(db, "Tak laku", 800L, cost = 450L)
        val empty = TestDb.product(db, "Habis", 800L, cost = 450L)
        val untracked = TestDb.product(db, "Servis", 800L, cost = 450L, trackStock = false)
        db.writeBlocking { tx ->
            StockDao.insertMovement(tx, sold, MovementKind.OPENING, 10_000L, 300L, null, null, null, 1L)
            StockDao.insertMovement(tx, idle, MovementKind.OPENING, 2_520L, 450L, null, null, null, 1L)
            StockDao.insertMovement(tx, untracked, MovementKind.OPENING, 5_000L, 450L, null, null, null, 1L)
        }
        val now = System.currentTimeMillis()
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(sold to 1_000L), soldAt = now), tz) }
        val today = Days.epochDay(now, tz)
        val (slow, total) = graph.reports.slowMovers(Period(today - 29, today + 1))
        assertEquals(listOf(idle), slow.map { it.productId })
        assertEquals(1L to 2_520L * 450L, total)
        assertTrue(empty !in slow.map { it.productId })
        val stock = graph.reports.stock()
        assertEquals(2L, stock.products)
        assertEquals(9L * 300L + 1_134L, stock.value) // 9 × 3.00 + 2.52 × 4.50
    }

    @Test
    fun receiptsExportEveryReceiptOnceInTimeOrder() = runBlocking {
        val db = graph.db()
        val a = TestDb.product(db, "Kopi", 1_250L)
        val base = at(20260910, 8)
        val ids = (0 until 23).map { n ->
            db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(a to 1_000L), soldAt = base + (n % 5) * 60_000L), tz) }
        }
        val fromMs = at(20260910, 0)
        val toMs = at(20260911, 0)
        val pages = ArrayList<Long>()
        var after: com.lekaspos.data.report.ReceiptRow? = null
        while (true) {
            val page = db.readBlocking { ReportDao.receipts(it, fromMs, toMs, after, limit = 7) }
            pages.addAll(page.map { it.id })
            if (page.size < 7) break
            after = page.last()
        }
        assertEquals(ids.map { it.id }.sorted(), pages.sorted())
        assertEquals(pages.size, pages.toSet().size)

        val out = StringBuilder()
        val day = Days.fromYmd(20260910)
        val n = graph.reports.export(ReportService.Export.RECEIPTS, Period(day, day + 1), out, tz)
        assertEquals(23L, n)
        val csv = CsvReader(StringReader(out.toString()))
        val header = assertNotNull(csv.next())
        assertEquals("receipt_no", header[0])
        val first = assertNotNull(csv.next())
        assertEquals("2026-09-10", first[1])
        assertEquals("12.50", first[14])
        for (kind in ReportService.Export.values()) {
            val sb = StringBuilder()
            graph.reports.export(kind, Period(day, day + 1), sb, tz)
            assertTrue(sb.startsWith(CsvWriter.BOM), "$kind has a BOM")
        }
    }
}
