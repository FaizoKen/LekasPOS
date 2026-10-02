package com.lekaspos.data.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.time.Hlc
import com.lekaspos.data.db.Db
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.perf.QueryPlans
import com.lekaspos.testing.TestDb
import java.util.Random
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** references/database.md §7: stock = latest count + everything after it (by HLC). */
@RunWith(AndroidJUnit4::class)
class StockTest {

    private lateinit var db: Db
    private var p = 0L
    private val tz = TimeZone.getDefault()

    @Before
    fun setUp() {
        db = TestDb.fresh()
        p = TestDb.product(db, "Beras Wangi 5kg", 2_890)
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    private fun level() = db.readBlocking { StockDao.level(it, p) }

    private fun rebuilt(): Long {
        db.writeBlocking { tx -> StockDao.rebuild(tx, p) }
        return level()
    }

    private fun move(qty: Long) = db.writeBlocking { tx ->
        StockDao.insertMovement(tx, p, MovementKind.ADJUST, qty, null, null, null, null, System.currentTimeMillis())
    }

    @Test
    fun countBecomesTheNewBaseline() {
        move(10_000)
        db.writeBlocking { tx -> StockDao.insertCount(tx, p, 7_000, null, null, null, System.currentTimeMillis()) }
        assertEquals(7_000, level())
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 2_000L)), tz) }
        assertEquals(5_000, level())
        assertEquals(5_000, rebuilt())
    }

    @Test
    fun eventsOlderThanTheLastCountAreAlreadyInTheCount() {
        move(10_000)
        val countHlc = db.writeBlocking { tx ->
            StockDao.insertCount(tx, p, 7_000, null, null, null, System.currentTimeMillis())
            db.hlc.current()
        }
        // A sale made on another (offline) device *before* the count arrives late via sync.
        val otherDevice = db.deviceNo xor 1
        val lateHlc = Hlc.pack(Hlc.physicalOf(countHlc) - 60_000L, 0)
        db.writeBlocking { tx ->
            tx.insert(
                "INSERT INTO stock_movement(id, product_id, kind, qty, at, hlc) VALUES(?,?,?,?,?,?)",
                (otherDevice.toLong() shl 41) or 1L, p, MovementKind.WASTE, -2_000L, 0L, lateHlc,
            )
            StockDao.applyDelta(tx, p, -2_000L, lateHlc, otherDevice)
        }
        assertEquals(7_000, level(), "an event before the count must not change the counted level")
        assertEquals(7_000, rebuilt())
    }

    /**
     * 2026-10 review: a count from a till whose clock had run a year ahead made every later sale of
     * this till count as "before" it for a year, and a recount here seemed to do nothing.
     */
    @Test
    fun aCountFromATillWhoseClockRanAheadStillCountsWhatCameAfter() {
        val year = 365L * 24L * 60L * 60L * 1000L
        val ahead = Hlc.pack(System.currentTimeMillis() + year, 0)
        val other = db.deviceNo xor 1
        db.writeBlocking { tx ->
            tx.insert("INSERT INTO stock_count(id, product_id, qty, at, hlc) VALUES(?,?,?,?,?)", (other.toLong() shl 41) or 1L, p, 20_000L, 0L, ahead)
            StockDao.rebuild(tx, p)
        }
        assertEquals(20_000, level())
        // A sale here, after that count arrived: it comes off the count.
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 3_000L)), tz) }
        assertEquals(17_000, level())
        assertEquals(17_000, rebuilt())
        // A recount here wins over it, and what comes after the recount counts.
        db.writeBlocking { tx -> StockDao.insertCount(tx, p, 50_000, null, null, null, System.currentTimeMillis()) }
        assertEquals(50_000, level())
        assertEquals(50_000, rebuilt())
        move(-1_000)
        assertEquals(49_000, level())
        assertEquals(49_000, rebuilt())
    }

    @Test
    fun anOlderCountArrivingLateDoesNotReplaceANewerOne() {
        val newest = db.writeBlocking { tx ->
            StockDao.insertCount(tx, p, 9_000, null, null, null, System.currentTimeMillis())
            db.hlc.current()
        }
        db.writeBlocking { tx ->
            tx.insert(
                "INSERT INTO stock_count(id, product_id, qty, at, hlc) VALUES(?,?,?,?,?)",
                ((db.deviceNo xor 1).toLong() shl 41) or 2L, p, 1_000L, 0L, Hlc.pack(Hlc.physicalOf(newest) - 3_600_000L, 0),
            )
        }
        assertEquals(9_000, rebuilt())
    }

    @Test
    fun eventsAtTheCountsHlcAreOrderedByDevice() {
        val h = Hlc.pack(System.currentTimeMillis(), 5)
        fun id(dev: Int, seq: Long) = (dev.toLong() shl 41) or seq
        val insertMove = "INSERT INTO stock_movement(id, product_id, kind, qty, at, hlc) VALUES(?,?,?,?,?,?)"
        db.writeBlocking { tx ->
            tx.insert("INSERT INTO stock_count(id, product_id, qty, at, hlc) VALUES(?,?,?,?,?)", id(1_000, 1L), p, 5_000L, 0L, h)
            tx.insert(insertMove, id(999, 2L), p, MovementKind.ADJUST, 1_000L, 0L, h) // same HLC, lower device: before the count
            tx.insert(insertMove, id(1_001, 3L), p, MovementKind.ADJUST, 2_000L, 0L, h) // same HLC, higher device: after it
            tx.insert(insertMove, id(999, 4L), p, MovementKind.ADJUST, 4_000L, 0L, h + 1)
            tx.insert(insertMove, id(1_001, 5L), p, MovementKind.ADJUST, 8_000L, 0L, h - 1)
        }
        // Two sales, moved to the count's HLC as if made on devices either side of it.
        val early = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L)), tz) }
        val late = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 3_000L)), tz) }
        db.writeBlocking { tx ->
            tx.update("UPDATE sale_line SET id = ?, hlc = ? WHERE sale_id = ?", id(999, 6L), h, early.id)
            tx.update("UPDATE sale_line SET id = ?, hlc = ? WHERE sale_id = ?", id(1_001, 7L), h, late.id)
        }
        assertEquals(5_000L + 2_000L + 4_000L - 3_000L, rebuilt())
    }

    @Test
    fun readsAfterACountUseAnHlcRangeOfTheIndex() {
        val queries = StockDao.HOT_QUERIES.toMap()
        for (name in listOf("stock_moves_after", "stock_sales_after")) {
            val check = db.readBlocking { QueryPlans.check(it, name, queries.getValue(name)) }
            assertEquals(emptyList<String>(), check.violations, check.plan.toString())
            assertTrue(check.plan.any { it.contains("hlc>?") }, check.plan.toString())
        }
    }

    @Test
    fun switchedOffProductsRaiseNoLowStockAlert() {
        val off = TestDb.product(db, "Beras Lama", 2_000, active = false)
        db.writeBlocking { tx -> tx.update("UPDATE product SET low_stock = 5000 WHERE id IN (?, ?)", p, off) }
        assertEquals(listOf(p), db.readBlocking { StockDao.lowStock(it) }.map { it.productId })
        assertEquals(1L, db.readBlocking { StockDao.lowStockCount(it) })
        assertEquals(emptyList(), db.readBlocking { StockDao.lowAmong(it, listOf(off)) })
    }

    @Test
    fun incrementalCacheAlwaysEqualsRebuild() {
        val rnd = Random(5)
        repeat(60) {
            when (rnd.nextInt(10)) {
                0 -> db.writeBlocking { tx -> StockDao.insertCount(tx, p, rnd.nextInt(50).toLong() * 1_000L, null, null, null, 0L) }
                in 1..4 -> move((rnd.nextInt(20) - 10).toLong() * 1_000L)
                else -> db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L * (1 + rnd.nextInt(3)))), tz) }
            }
            val cached = level()
            assertEquals(cached, rebuilt())
        }
    }
}
