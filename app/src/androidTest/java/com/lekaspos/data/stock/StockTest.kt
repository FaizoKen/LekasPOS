package com.lekaspos.data.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.time.Hlc
import com.lekaspos.data.db.Db
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.testing.TestDb
import java.util.Random
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

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
