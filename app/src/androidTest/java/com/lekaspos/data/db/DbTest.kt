package com.lekaspos.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.core.id.Ids
import com.lekaspos.testing.TestDb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DbTest {

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
    fun mainThreadAccessIsRefused() {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            failure.set(runCatching { db.readBlocking { it.long("SELECT 1") } }.exceptionOrNull())
        }
        assertTrue(failure.get() is IllegalStateException)
    }

    @Test
    fun concurrentWritersAreSerialized() {
        db.writeBlocking { tx -> Meta.put(tx.db, "counter", "0") }
        val threads = 4
        val perThread = 50
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                repeat(perThread) {
                    db.writeBlocking { tx ->
                        val v = Meta.getLong(tx.db, "counter") ?: 0L
                        Meta.put(tx.db, "counter", (v + 1).toString())
                    }
                }
                done.countDown()
            }.start()
        }
        done.await()
        assertEquals((threads * perThread).toLong(), db.readBlocking { Meta.getLong(it, "counter") })
    }

    @Test
    fun suspendingWriteRunsOnTheWriterThread() = runBlocking {
        val name = db.write { Thread.currentThread().name }
        assertEquals("db-writer", name)
    }

    @Test
    fun exceptionRollsBackTheWholeTransaction() {
        assertFailsWith<IllegalStateException> {
            db.writeBlocking { tx ->
                Meta.put(tx.db, "half", "done")
                error("boom")
            }
        }
        assertEquals(null, db.readBlocking { Meta.get(it, "half") })
    }

    @Test
    fun idsAreUniqueAndNeverReusedAfterReopen() {
        val first = db.writeBlocking(reserveIds = 1500) { tx -> List(1500) { tx.nextId() } } +
            db.writeBlocking(reserveIds = 1500) { tx -> List(1500) { tx.nextId() } } // needs a second block
        assertEquals(first.size, first.toSet().size)
        assertTrue(first.all { Ids.deviceOf(it) == db.deviceNo })
        val name = db.name
        db.close()
        db = Db.open(TestDb.context, name)
        val second = db.writeBlocking { tx -> List(10) { tx.nextId() } }
        assertTrue(second.min() > first.max())
    }

    @Test
    fun writeThatOutgrowsItsIdReservationFailsInsteadOfReusing() {
        assertFailsWith<IllegalStateException> {
            db.writeBlocking(reserveIds = 1) { tx -> repeat(5000) { tx.nextId() } }
        }
        // the failed transaction left nothing behind and later IDs are still fresh
        val next = db.writeBlocking { tx -> tx.nextId() }
        assertTrue(Ids.seqOf(next) > 0)
    }

    @Test
    fun hlcSurvivesReopen() {
        val last = db.writeBlocking { tx -> tx.hlcNow() }
        val name = db.name
        db.close()
        db = Db.open(TestDb.context, name)
        assertTrue(db.writeBlocking { tx -> tx.hlcNow() } > last)
    }

    @Test
    fun nestedWritesJoinTheOuterTransaction() {
        assertFailsWith<IllegalStateException> {
            db.writeBlocking { tx ->
                Meta.put(tx.db, "outer", "1")
                db.writeBlocking { inner -> Meta.put(inner.db, "inner", "1") }
                error("rollback both")
            }
        }
        db.readBlocking {
            assertEquals(null, Meta.get(it, "outer"))
            assertEquals(null, Meta.get(it, "inner"))
        }
    }
}
