package com.lekaspos.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.long
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupTest {

    private val name = "test-${UUID.randomUUID()}.db"
    private lateinit var graph: AppGraph
    private val ctx get() = TestDb.context
    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

    @Before
    fun setUp() {
        Restore.cancelStaged(ctx)
        graph = TestGraph.create(name)
    }

    @After
    fun tearDown() {
        Restore.cancelStaged(ctx)
        TestGraph.destroy(graph)
    }

    private fun sell(n: Int) = runBlocking {
        val db = graph.db()
        val p = TestDb.product(db, "Kopi", 250L)
        repeat(n) { db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L)), tz) } }
    }

    private fun backupBytes(): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        BackupFiles.write(graph.db(), out, File(ctx.cacheDir, "backup-test"), "test", "manual")
        out.toByteArray()
    }

    @Test
    fun aBackupTakenWhileSellingIsConsistentAndRestoresAsTheSameTill() = runBlocking {
        sell(20)
        val db = graph.db()
        // Keep selling while the backup is taken: the copy must be a clean state.
        val more = async(kotlinx.coroutines.Dispatchers.IO) { sell(30) }
        val bytes = backupBytes()
        more.await()
        val header = assertNotNull(BackupFiles.readHeader(ByteArrayInputStream(bytes)))
        assertEquals(Meta.get(db.sqlite, Meta.DEVICE_UUID), header.deviceUuid)
        assertTrue(header.sales in 20L..50L)
        val (device, store) = db.read { Meta.get(it, Meta.DEVICE_NO) to Meta.get(it, Meta.STORE_UUID) }
        db.writeBlocking { tx -> PrintJobDao.enqueue(tx, PrintJobKind.RECEIPT, 1L, 1, System.currentTimeMillis()) }

        Restore.stage(ctx, ByteArrayInputStream(bytes), Restore.Mode.REPLACE)
        TestGraph.close(graph)
        graph = TestGraph.reopen(name) // the "restart": the staged restore goes in before opening
        val restored = graph.db()
        assertEquals(header.sales, restored.read { it.long("SELECT COUNT(*) FROM sale") })
        assertEquals(device, restored.read { Meta.get(it, Meta.DEVICE_NO) })
        assertEquals(store, restored.read { Meta.get(it, Meta.STORE_UUID) })
        assertEquals(0L, restored.read { it.long("SELECT COUNT(*) FROM print_job") }) // stale jobs never print
        // The data that was replaced was kept as a backup first.
        val kept = Restore.backupDir(ctx).listFiles { f -> f.name.startsWith(Restore.REASON_REPLACED) }.orEmpty().maxByOrNull { it.lastModified() }
        val keptHeader = assertNotNull(kept?.inputStream()?.use { BackupFiles.readHeader(it) })
        assertEquals(50L, keptHeader.sales)
    }

    @Test
    fun aRestoreAsANewTillGetsItsOwnIdentity() = runBlocking {
        sell(3)
        val db = graph.db()
        db.syncEnabled = true
        sell(1) // leaves an outbox event behind
        val before = db.read { Triple(Meta.get(it, Meta.DEVICE_NO), Meta.get(it, Meta.DEVICE_UUID), Meta.get(it, Meta.STORE_UUID)) }
        val bytes = backupBytes()
        Restore.stage(ctx, ByteArrayInputStream(bytes), Restore.Mode.NEW_DEVICE)
        TestGraph.close(graph)
        graph = TestGraph.reopen(name)
        val r = graph.db()
        val after = r.read { Triple(Meta.get(it, Meta.DEVICE_NO), Meta.get(it, Meta.DEVICE_UUID), Meta.get(it, Meta.STORE_UUID)) }
        assertNotEquals(before.first, after.first)
        assertNotEquals(before.second, after.second)
        assertEquals(before.third, after.third) // same store
        assertEquals(r.deviceNo.toString(), after.first)
        assertEquals(0L, r.read { it.long("SELECT COUNT(*) FROM outbox") })
        assertNull(r.read { Meta.get(it, Meta.RECEIPT_PREFIX) })
        assertEquals(4L, r.read { it.long("SELECT COUNT(*) FROM sale") })
        // New sales get this till's receipt numbers and ids.
        val p = TestDb.product(r, "Teh", 150L)
        val sale = r.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(r, listOf(p to 1_000L)), tz) }
        assertEquals(r.deviceNo, com.lekaspos.core.id.Ids.deviceOf(sale.id))
    }

    @Test
    fun filesThatAreNotBackupsAreRefused() {
        assertNull(BackupFiles.readHeader(ByteArrayInputStream("hello".toByteArray())))
        assertFailsWith<Exception> { Restore.stage(ctx, ByteArrayInputStream("not a zip".toByteArray()), Restore.Mode.REPLACE) }
        // A backup whose database was cut short is caught before anything is replaced.
        sell(2)
        val bytes = backupBytes()
        val cut = bytes.copyOf(bytes.size / 2)
        assertFailsWith<Exception> { Restore.stage(ctx, ByteArrayInputStream(cut), Restore.Mode.REPLACE) }
        Restore.cancelStaged(ctx)
        assertEquals(2L, runBlocking { graph.db().read { it.long("SELECT COUNT(*) FROM sale") } })
    }

    @Test
    fun theBackupServiceKeepsSevenAutomaticBackups() = runBlocking {
        sell(1)
        val service = graph.backups
        service.dir.listFiles()?.forEach { it.delete() }
        repeat(9) { service.backupNow(auto = true) }
        val autos = service.list().filter { it.auto }
        assertEquals(7, autos.size)
        assertTrue(autos.all { it.header != null })
        assertTrue(!service.backupIfDue()) // one was just made
        service.dir.listFiles()?.forEach { it.delete() }
        Unit
    }
}
