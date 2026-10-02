package com.lekaspos.data.print

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.long
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.testing.TestDb
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrintJobDaoTest {

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = TestDb.fresh()
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    @Test
    fun queueKeepsOrderAndSurvivesFailedAttempts() {
        val first = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, 7L, 1, 1_000L) }
        val second = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.RECEIPT, 7L, 2, 1_001L) }
        assertEquals(2L, db.readBlocking { PrintJobDao.pendingCount(it) })
        assertEquals(first, db.readBlocking { PrintJobDao.next(it) }?.id)

        db.writeBlocking { tx -> PrintJobDao.markAttempt(tx, first, "socket closed", 2_000L) }
        val retried = db.readBlocking { PrintJobDao.next(it) }
        assertEquals(first, retried?.id)
        assertEquals(1, retried?.attempts)

        db.writeBlocking { tx -> PrintJobDao.markDone(tx, first, 3_000L) }
        val next = db.readBlocking { PrintJobDao.next(it) }
        assertEquals(second, next?.id)
        assertEquals(2, next?.copies)

        db.writeBlocking { tx -> PrintJobDao.markFailed(tx, second, "sale missing", 3_000L) }
        assertEquals(null, db.readBlocking { PrintJobDao.next(it) })

        val test = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.TEST, null, 1, 4_000L) }
        assertEquals(1, db.writeBlocking { tx -> PrintJobDao.cancelPending(tx, 5_000L) })
        // The printer was still busy with it when the queue was cleared: it stays cleared (2026-10 review).
        assertEquals(false, db.readBlocking { PrintJobDao.isPending(it, test) })
        db.writeBlocking { tx -> PrintJobDao.markDone(tx, test, 5_500L) }
        assertEquals("cancelled", db.readBlocking { it.stringOrNull("SELECT last_error FROM print_job WHERE id = ?", test) })
        assertEquals(0L, db.readBlocking { PrintJobDao.pendingCount(it) })
        assertEquals(3, db.writeBlocking { tx -> PrintJobDao.purge(tx, 6_000L) })
        assertEquals(0L, db.readBlocking { it.long("SELECT COUNT(*) FROM print_job") })
    }

    @Test
    fun drawerPulsesGoFirstAndOldReceiptsExpire() {
        val oldReceipt = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.RECEIPT, 1L, 1, 1_000L) }
        val oldCopy = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.REPRINT, 1L, 1, 1_001L) }
        val oldShift = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.SHIFT, 5L, 1, 1_002L) }
        val newReceipt = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.RECEIPT, 2L, 1, 900_000L) }
        val drawer = db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, 2L, 1, 900_001L) }

        // The cashier is waiting to give change: the pulse jumps the queue of receipts.
        assertEquals(drawer, db.readBlocking { PrintJobDao.next(it) }?.id)
        db.writeBlocking { tx -> PrintJobDao.markDone(tx, drawer, 900_002L) }
        assertEquals(oldReceipt, db.readBlocking { PrintJobDao.next(it) }?.id)

        // Receipts from before the cut-off are dropped; copies and shift reports were asked for and wait.
        assertEquals(1, db.writeBlocking { tx -> PrintJobDao.expireReceipts(tx, 300_000L, 900_003L) })
        val error = db.readBlocking { it.stringOrNull("SELECT last_error FROM print_job WHERE id = ?", oldReceipt) }
        assertEquals("expired", error)
        assertEquals(oldCopy, db.readBlocking { PrintJobDao.next(it) }?.id)
        db.writeBlocking { tx -> PrintJobDao.markDone(tx, oldCopy, 900_004L) }
        assertEquals(oldShift, db.readBlocking { PrintJobDao.next(it) }?.id)
        db.writeBlocking { tx -> PrintJobDao.markDone(tx, oldShift, 900_005L) }
        assertEquals(newReceipt, db.readBlocking { PrintJobDao.next(it) }?.id)
        assertEquals(1L, db.readBlocking { PrintJobDao.pendingCount(it) })
    }
}
