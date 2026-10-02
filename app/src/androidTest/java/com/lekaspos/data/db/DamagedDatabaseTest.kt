package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.data.backup.Restore
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.testing.TestDb
import java.io.File
import java.io.RandomAccessFile
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** What happens when the store's database file is damaged (D-055, 2026-10 review). */
@RunWith(AndroidJUnit4::class)
class DamagedDatabaseTest {

    private val ctx get() = TestDb.context
    private val name = "damaged-test-${UUID.randomUUID()}.db"

    @After
    fun tearDown() {
        KeepDamagedDatabase.clearForTests(ctx)
        Restore.backupDir(ctx).listFiles { f -> f.name.startsWith("damaged-") }?.forEach { it.delete() }
        SQLiteDatabase.deleteDatabase(ctx.getDatabasePath(name))
    }

    /** Damage in a page read right after opening (meta) failed every start, and no screen could open. */
    @Test
    fun damageFoundRightAfterOpeningIsSetAsideAndAnEmptyStoreOpens() {
        val db = Db.open(ctx, name)
        val p = TestDb.product(db, "Kopi", 250L)
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L)), TimeZone.getDefault()) }
        val root = db.readBlocking { it.long("SELECT rootpage FROM sqlite_master WHERE name = 'meta'") }
        val pageSize = db.readBlocking { it.pragma("PRAGMA page_size") }?.toLong() ?: 4096L
        db.onWriterThread { it.pragma("PRAGMA wal_checkpoint(FULL)") }
        db.close()
        val file = ctx.getDatabasePath(name)
        // Everything is in the file now; a WAL left behind would answer for the damaged page.
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        assertTrue(root > 1L)
        RandomAccessFile(file, "rw").use { f ->
            f.seek((root - 1L) * pageSize)
            f.write(ByteArray(pageSize.toInt()) { 0x5A })
        }

        val fresh = Db.open(ctx, name)
        try {
            assertNotNull(KeepDamagedDatabase.problem)
            assertTrue(Restore.backupDir(ctx).listFiles().orEmpty().any { it.name.startsWith("damaged-") })
            assertFalse(fresh.readBlocking { SaleDao.any(it) }) // an empty store, ready for a restore
            assertEquals(0L, fresh.readBlocking { it.long("SELECT COUNT(*) FROM product") })
        } finally {
            fresh.close()
        }
    }
}
