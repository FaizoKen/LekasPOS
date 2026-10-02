package com.lekaspos.data.backup

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.data.db.Meta
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.domain.backup.BackupService
import com.lekaspos.domain.backup.BackupService.Protection.State
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data safety (D-048): the daily copy to a folder off the app's storage, the "is a copy of this
 * data off the phone?" status behind the selling screen's "Not backed up" pill, and the
 * database check that keeps a damaged database from rotating good backups away.
 */
@RunWith(AndroidJUnit4::class)
class DataSafetyTest {

    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private lateinit var graph: AppGraph
    private lateinit var service: BackupService
    private lateinit var dir: File
    private val ctx get() = TestDb.context

    @Before
    fun setUp() {
        graph = TestGraph.create()
        service = graph.backups
        service.dir.listFiles()?.forEach { it.delete() }
        dir = File(ctx.cacheDir, "folder-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        service.dir.listFiles()?.forEach { it.delete() }
        dir.deleteRecursively()
        TestGraph.destroy(graph)
    }

    private fun sell() = runBlocking {
        val db = graph.db()
        val p = TestDb.product(db, "Kopi", 250L)
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L)), tz) }
    }

    private fun ours(): List<File> = dir.listFiles().orEmpty().filter { BackupFolder.isOurs(it.name) }

    @Test
    fun anEmptyShopHasNothingToLoseAndASaleMakesItAtRisk() = runBlocking {
        assertEquals(State.NO_DATA, service.refreshProtection().state)
        sell()
        val p = service.refreshProtection()
        assertEquals(State.AT_RISK, p.state)
        assertNull(p.lastOffPhone)
        assertEquals(State.AT_RISK, service.protection.value.state)
    }

    @Test
    fun theDailyBackupIsCopiedToTheFolderAndProtectsTheData() = runBlocking {
        sell()
        service.folderForTests = FileBackupFolder(dir)
        assertTrue(service.backupIfDue())
        val copies = ours()
        assertEquals(1, copies.size)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        // The copy is a whole backup: its header reads, and it restores.
        assertNotNull(FileInputStream(copies[0]).use { BackupFiles.readHeader(it) })
        val p = service.refreshProtection()
        assertEquals(State.PROTECTED, p.state)
        assertNotNull(p.lastOffPhone)
        // Three days without a new copy: "Not backed up" again.
        assertEquals(State.AT_RISK, service.refreshProtection(now = (p.lastOffPhone ?: 0L) + BackupService.FRESH_MS + 1L).state)
        // Not due again the same day: no second copy.
        assertTrue(!service.backupIfDue())
        assertEquals(1, ours().size)
    }

    @Test
    fun theFolderKeepsTheNewestSevenAndNeverTouchesOtherFiles() = runBlocking {
        sell()
        dir.mkdirs()
        for (d in 1..9) File(dir, "lekaspos-2020-01-0$d-0900.lekasbak").writeText("old")
        File(dir, "photo.jpg").writeText("not ours")
        File(dir, "lekaspos-notes.txt").writeText("not ours")
        service.folderForTests = FileBackupFolder(dir)
        assertTrue(service.copyToFolderNow())
        val names = ours().map { it.name }.sortedDescending()
        assertEquals(BackupService.KEEP_FOLDER, names.size)
        assertTrue(names[0].startsWith("lekaspos-20") && !names[0].startsWith("lekaspos-2020-"), "new copy kept: $names")
        assertEquals("lekaspos-2020-01-04-0900.lekasbak", names.last()) // the three oldest went
        assertTrue(File(dir, "photo.jpg").exists())
        assertTrue(File(dir, "lekaspos-notes.txt").exists())
    }

    @Test
    fun aMissingFolderIsReportedAndNotCountedAsACopy() = runBlocking {
        sell()
        dir.parentFile?.mkdirs()
        dir.writeText("a file where the folder should be") // like a removed SD card: nothing can be written
        service.folderForTests = FileBackupFolder(dir)
        assertTrue(!service.copyToFolderNow())
        val p = service.refreshProtection()
        assertEquals(State.AT_RISK, p.state)
        assertNotNull(p.folderError)
        // Once the folder is back, the next copy clears the error.
        dir.delete()
        assertTrue(service.copyToFolderNow())
        val q = service.refreshProtection()
        assertEquals(State.PROTECTED, q.state)
        assertNull(q.folderError)
    }

    @Test
    fun aSavedBackupFileCountsAsACopyOffThePhone() = runBlocking {
        sell()
        service.export { ByteArrayOutputStream() }
        assertEquals(State.PROTECTED, service.refreshProtection().state)
    }

    /** 2026-10 review: the daily copy paused every sale while it ran, even in the middle of a rush. */
    @Test
    fun theDailyBackupWaitsForAQuietTillButNotForever() = runBlocking {
        service.backupNow(auto = true)
        val hour = 60L * 60L * 1000L
        val backup = service.dir.listFiles().orEmpty().single()
        backup.setLastModified(System.currentTimeMillis() - 25 * hour) // due
        sell() // the till sold just now
        assertEquals(true, runCatching { service.backupIfDue() }.exceptionOrNull() is BackupService.Postponed)
        assertEquals(1, service.dir.listFiles().orEmpty().size) // nothing made yet
        backup.setLastModified(System.currentTimeMillis() - 37 * hour) // overdue: busy or not
        assertTrue(service.backupIfDue())
    }

    @Test
    fun aDamagedDatabaseNeverRotatesGoodBackupsAway() = runBlocking {
        sell()
        repeat(BackupService.KEEP_AUTO) { service.backupNow(auto = true) }
        val old = System.currentTimeMillis() - 2 * BackupService.DUE_MS
        service.dir.listFiles()?.forEach { it.setLastModified(old) }
        val good = service.dir.listFiles().orEmpty().map { it.name }.toSet()
        service.folderForTests = FileBackupFolder(dir)

        service.integrityForTests = "*** in database main ***\nPage 12: btreeInitPage() returns error code 11"
        assertTrue(!service.backupIfDue())
        assertEquals(good, service.dir.listFiles().orEmpty().map { it.name }.toSet()) // not one replaced
        assertTrue(ours().isEmpty()) // and nothing damaged copied off the phone
        assertEquals(State.DAMAGED, service.protection.value.state)
        val p = service.refreshProtection()
        assertEquals(State.DAMAGED, p.state)
        assertTrue(p.damage.orEmpty().contains("btreeInitPage"))

        // The next check passes (e.g. after a restore): the warning goes and backups start again.
        service.integrityForTests = null
        assertTrue(service.backupIfDue())
        assertNull(graph.db().read { Meta.get(it, Meta.DB_PROBLEM) })
        assertEquals(State.PROTECTED, service.refreshProtection().state)
        assertEquals(1, ours().size)
    }

    /** The check itself (SQLite's quick_check) finds real damage in a database file. */
    @Test
    fun theDatabaseCheckFindsDamagedPages() {
        val file = File(ctx.cacheDir, "damaged-${UUID.randomUUID()}.db")
        val keepFile = DatabaseErrorHandler { } // the default handler deletes a corrupt file
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
                db.execSQL("CREATE INDEX t_v ON t (v)")
                db.beginTransaction()
                try {
                    repeat(2_000) { db.execSQL("INSERT INTO t (v) VALUES (?)", arrayOf<Any>("row $it ${UUID.randomUUID()}")) }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                assertEquals("ok", BackupFiles.integrity(db))
            }
            RandomAccessFile(file, "rw").use { f ->
                val page = 4096L
                f.seek(3 * page)
                f.write(ByteArray((4 * page).toInt()) { 0x5A })
            }
            val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            val check = runCatching {
                SQLiteDatabase.openDatabase(file.path, null, flags, keepFile).use { BackupFiles.integrity(it) }
            }.getOrElse { "failed: ${it.message}" }
            assertNotEquals("ok", check)
        } finally {
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
