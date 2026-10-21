package com.lekaspos.sync

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.Restore
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.long
import com.lekaspos.data.sync.SegmentCodec
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tills read the changes the manager web page made (D-083). ManagerFixtureTest left a two-till shop in
 * `files/manager-fixture`; scripts/manager/web-edits.mjs made a manager's day of changes in its folder with the web
 * page's own code — each save a sync file of the page's own till number — and scripts/ci/instrumented.sh put them in
 * `files/web-edits` (internal files). Each till, reopened from its backup file as the same till, syncs with the app's
 * own engine: every file must be read, nothing set aside, nothing failed. Their backup files after it
 * (`till-a-edited.lekasbak`, `till-b-edited.lekasbak`) go back to scripts/manager/fixture.test.mjs, which requires
 * them to hold exactly the web page's data. Skipped where web-edits.mjs did not run (everywhere but CI).
 */
@RunWith(AndroidJUnit4::class)
class ManagerEditsTest {

    private val out: File get() = File(TestDb.context.getExternalFilesDir(null) ?: TestDb.context.filesDir, "manager-fixture")
    private val edits: File get() = File(TestDb.context.filesDir, "web-edits")
    private lateinit var folder: File
    private val tills = ArrayList<AppGraph>()

    @Before
    fun setUp() {
        assumeTrue("made by scripts/manager/web-edits.mjs in CI", File(edits, "web-edits.json").exists() && File(out, "till-a.lekasbak").exists())
        folder = File(TestDb.context.cacheDir, "manager-edits-${UUID.randomUUID()}").apply { mkdirs() }
        Restore.cancelStaged(TestDb.context)
        Restore.finished(TestDb.context)
    }

    @After
    fun tearDown() {
        for (t in tills) TestGraph.destroy(t)
        if (::folder.isInitialized) folder.deleteRecursively()
    }

    /** The files of [src] into the sync folder, each `name.props` as FolderProvider's `.name.props`. */
    private fun copyIn(src: File) {
        for (f in src.listFiles().orEmpty()) {
            if (!f.isFile || f.name.endsWith(".lekasbak") || f.name == "web-edits.json") continue
            val name = if (f.name.endsWith(".props")) ".${f.name}" else f.name
            f.copyTo(File(folder, name), overwrite = true)
        }
    }

    /** A till opened from its backup file as the same till (its number, cursors and sync state kept). */
    private fun open(backup: File, name: String): AppGraph {
        val path = TestDb.context.getDatabasePath(name)
        SQLiteDatabase.deleteDatabase(path)
        path.parentFile?.mkdirs()
        ZipInputStream(FileInputStream(backup)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val target = when (e.name) {
                    "lekaspos.db" -> path
                    "lekaspos.db-wal" -> File(path.path + "-wal")
                    else -> null
                } ?: continue
                FileOutputStream(target).use { o -> z.copyTo(o) }
            }
        }
        return TestGraph.reopen(name).also { tills.add(it) }
    }

    @Test
    fun theTillsReadTheWebPagesChanges() = runBlocking {
        copyIn(out)
        copyIn(edits)
        val provider = FolderProvider(folder)
        val info = SegmentCodec.parse(File(edits, "web-edits.json").readText()) as Map<*, *>
        val web = (info["dev"] as Long).toInt()
        val kopi = (info["kopi"] as String).toLong()
        val milo = (info["milo"] as String).toLong()
        val temp = (info["temp"] as String).toLong()
        val webFiles = folder.listFiles().orEmpty().count { it.name.startsWith("seg-") && it.name.contains("-$web-") }
        assertTrue(webFiles >= 10, "the web page's files: $webFiles")

        for ((backup, name) in listOf("till-a.lekasbak" to "till-a-edited", "till-b.lekasbak" to "till-b-edited")) {
            val t = open(File(out, backup), "$name.db")
            val first = t.sync.sync(provider)
            assertTrue(first.applied >= webFiles, "$name read ${first.applied} files")
            t.sync.sync(provider) // a second round finds nothing new and fails on nothing
            t.db().read { r ->
                assertEquals(0L, r.long("SELECT COUNT(*) FROM sync_deferred"), "$name set events aside")
                assertNull(Meta.get(r, Meta.SYNC_LAST_ERROR))
                assertEquals(webFiles.toLong(), r.long("SELECT seq FROM sync_cursor WHERE dev = ?", web))
                // The new product: price after its second edit, opening stock + the delivery, its codes, colour and picture
                assertEquals(270L, r.long("SELECT price FROM product WHERE id = ? AND deleted = 0", kopi))
                assertEquals(68_000L, r.long("SELECT qty FROM stock_level WHERE product_id = ?", kopi))
                assertEquals(3L, r.long("SELECT COUNT(*) FROM product_barcode WHERE product_id = ? AND deleted = 0", kopi))
                assertEquals(9L, r.long("SELECT color FROM product_look WHERE id = ?", kopi))
                assertEquals(1L, r.long("SELECT COUNT(*) FROM product_image i JOIN product_look l ON l.image_id = i.id WHERE l.id = ?", kopi))
                assertEquals(1L, r.long("SELECT deleted FROM product WHERE id = ?", temp))
                assertEquals(1L, r.long("SELECT COUNT(*) FROM product_barcode WHERE product_id = ? AND pack_qty = 12000 AND deleted = 0", milo))
                assertEquals(1L, r.long("SELECT COUNT(*) FROM purchase WHERE ref_no = 'INV-55'"))
                assertEquals(1L, r.long("SELECT COUNT(*) FROM category WHERE name = 'Minuman Sejuk' AND deleted = 0"))
                // Its activity log entries, made by the web page's till number
                assertTrue(r.long("SELECT COUNT(*) FROM audit_log WHERE id >= ? AND id < ?", web.toLong() shl 41, (web.toLong() + 1L) shl 41) >= 6L)
                // Searchable on the till like any product (the till's own search index)
                assertEquals(1L, r.long("SELECT COUNT(*) FROM product_fts WHERE product_fts MATCH 'kaw*' AND docid = ?", kopi))
            }
            val scratch = File(TestDb.context.cacheDir, "manager-edits-tmp")
            FileOutputStream(File(out, "$name.lekasbak")).use { o -> BackupFiles.write(t.db(), o, scratch, "fixture", "manual") }
            scratch.deleteRecursively()
        }
    }
}
