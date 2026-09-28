package com.lekaspos.data.print

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.long
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

        db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.TEST, null, 1, 4_000L) }
        assertEquals(1, db.writeBlocking { tx -> PrintJobDao.cancelPending(tx, 5_000L) })
        assertEquals(0L, db.readBlocking { PrintJobDao.pendingCount(it) })
        assertEquals(3, db.writeBlocking { tx -> PrintJobDao.purge(tx, 6_000L) })
        assertEquals(0L, db.readBlocking { it.long("SELECT COUNT(*) FROM print_job") })
    }
}
