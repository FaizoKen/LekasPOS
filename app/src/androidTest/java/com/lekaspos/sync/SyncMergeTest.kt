package com.lekaspos.sync

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.PromoKind
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.sync.SyncNames
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.Restore
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.DerivedRebuild
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.Schema
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.promo.PromotionDao
import com.lekaspos.data.promo.PromotionRow
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.sync.SegmentCodec
import com.lekaspos.data.sync.SyncDao
import com.lekaspos.data.sync.SyncEvent
import com.lekaspos.domain.backup.BackupService
import com.lekaspos.domain.sell.Tender
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The merge test plan (references/sync.md §12): several tills, each its own database, sharing
 * a folder as the sync store. After syncing, every till must hold exactly the same data, and the
 * derived tables must equal a full rebuild from the events.
 */
@RunWith(AndroidJUnit4::class)
class SyncMergeTest {

    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private lateinit var folder: File
    private lateinit var provider: FolderProvider
    private val tills = ArrayList<AppGraph>()

    @Before
    fun setUp() {
        folder = File(TestDb.context.cacheDir, "sync-test-${UUID.randomUUID()}").apply { mkdirs() }
        provider = FolderProvider(folder)
        // Nothing staged or half-finished by an earlier test may land on this test's databases.
        Restore.cancelStaged(TestDb.context)
        Restore.finished(TestDb.context)
    }

    @After
    fun tearDown() {
        for (t in tills) TestGraph.destroy(t)
        folder.deleteRecursively()
    }

    private fun till(): AppGraph = TestGraph.create().also { tills.add(it) }

    private fun enable(t: AppGraph, name: String = "Till") = runBlocking { t.sync.enable(provider, name) }

    /** Everyone syncs, twice (the second round spreads what the first brought in). */
    private fun syncAll(vararg ts: AppGraph) = runBlocking {
        repeat(2) { for (t in ts) t.sync.sync(provider) }
    }

    private fun product(t: AppGraph, name: String, price: Long): Long = runBlocking { TestDb.product(t.db(), name, price) }

    private fun sell(t: AppGraph, productId: Long, qty: Long = 1_000L, at: Long = System.currentTimeMillis()): Long = runBlocking {
        val db = t.db()
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(productId to qty), soldAt = at), tz) }.id
    }

    private fun editPrice(t: AppGraph, productId: Long, price: Long) = runBlocking {
        val db = t.db()
        db.writeBlocking { tx ->
            val p = ProductDao.get(tx.db, productId) ?: error("no product")
            ProductDao.update(tx, p, p.copy(price = price), System.currentTimeMillis())
        }
    }

    // ------------------------------------------------------------------ comparison

    private fun content(r: SQLiteDatabase): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        fun rows(sql: String) = r.queryList(sql) { c -> (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "∅" else c.getString(it) } }
        for (t in Schema.LWW_TABLES + Schema.EVENT_TABLES) {
            val cols = r.queryList("PRAGMA table_info($t)") { it.getString(1) }
                .filter { it !in setOf("ver_hlc", "ver_dev", "fver", "updated_at", "created_at") }
            val order = if (t == "setting") "key" else "id"
            out[t] = rows("SELECT ${cols.joinToString(", ")} FROM $t ORDER BY $order")
        }
        out["stock_level"] = rows("SELECT product_id, qty FROM stock_level WHERE qty != 0 ORDER BY product_id")
        out["customer_balance"] = rows("SELECT customer_id, balance FROM customer_balance WHERE balance != 0 ORDER BY customer_id")
        // Key columns, then totals; rows whose totals are all zero (left behind by voids) are not data.
        val sums = linkedMapOf(
            "sum_day" to ("day" to "sale_count, refund_count, void_count, gross, discount, net_ex, tax, rounding, total, cost, refund_total, items"),
            "sum_day_product" to ("day, product_id" to "qty, net_ex, tax, cost"),
            "sum_month_product" to ("month, product_id" to "qty, net_ex, tax, cost"),
            "sum_day_payment" to ("day, method_id" to "amount, count"),
            "sum_day_staff" to ("day, staff_id" to "sale_count, total, net_ex"),
        )
        for ((t, cols) in sums) {
            val keys = cols.first.split(", ").size
            out[t] = rows("SELECT ${cols.first}, ${cols.second} FROM $t ORDER BY ${cols.first}")
                .filterNot { row -> row.split("|").drop(keys).all { it == "0" || it == "∅" } }
        }
        return out
    }

    /** All tills hold the same data, and each till's derived tables equal a rebuild. */
    private fun assertConverged(vararg ts: AppGraph) = runBlocking {
        val first = ts[0].db().read { content(it) }
        for (t in ts.drop(1)) {
            val other = t.db().read { content(it) }
            for (k in first.keys) assertEquals(first[k], other[k], "table $k differs between tills")
        }
        for (t in ts) {
            val db = t.db()
            val before = db.read { content(it) }
            db.write(reserveIds = 0L) { tx -> DerivedRebuild.all(tx) }
            val after = db.read { content(it) }
            for (k in before.keys) assertEquals(before[k], after[k], "derived $k differs from a rebuild")
        }
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    fun theSecondTillJoinsTheStoreAndBothSeeEverything() {
        val a = till()
        val milo = product(a, "Milo", 1_890L)
        sell(a, milo)
        enable(a, "Counter A")
        val b = till()
        val roti = product(b, "Roti", 350L) // B was used on its own before joining
        sell(b, roti, 2_000L)
        enable(b, "Counter B")
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking {
            // B adopted A's store; each till kept its own receipt prefix and device number.
            val (sa, sb) = a.db().read { Meta.get(it, Meta.STORE_UUID) } to b.db().read { Meta.get(it, Meta.STORE_UUID) }
            assertEquals(sa, sb)
            assertNotEquals(a.db().deviceNo, b.db().deviceNo)
            val prefixes = listOf(a, b).map { t -> t.db().read { Meta.get(it, Meta.RECEIPT_PREFIX) } }
            assertEquals(2, prefixes.toSet().size)
            assertEquals(2L, a.db().read { it.long("SELECT COUNT(*) FROM sale") })
            assertTrue(folder.listFiles().orEmpty().any { it.name.startsWith(SyncNames.devicePrefix(sa ?: "")) })
        }
    }

    @Test
    fun editsMergeFieldByField() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val p = product(a, "Milo", 1_890L)
        syncAll(a, b)
        // Offline: both change the price (the later edit wins everywhere), B also renames it.
        editPrice(a, p, 1_950L)
        Thread.sleep(5)
        editPrice(b, p, 1_990L)
        runBlocking {
            val db = b.db()
            db.writeBlocking { tx ->
                val cur = ProductDao.get(tx.db, p) ?: error("no product")
                ProductDao.update(tx, cur, cur.copy(name = "Milo 1kg"), System.currentTimeMillis())
            }
        }
        // A deletes a barcode-less product B is editing: the tombstone and the edit both survive.
        val q = product(a, "Kopi", 900L)
        syncAll(a, b)
        runBlocking { a.db().writeBlocking { tx -> ProductDao.delete(tx, q, System.currentTimeMillis()) } }
        editPrice(b, q, 950L)
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking {
            val pa = a.db().read { ProductDao.get(it, p) } ?: error("no product")
            assertEquals(1_990L, pa.price)
            assertEquals("Milo 1kg", pa.name)
            val deleted = a.db().read { it.long("SELECT deleted FROM product WHERE id = ?", q) }
            assertEquals(1L, deleted)
            assertEquals(950L, a.db().read { ProductDao.get(it, q) }?.price)
        }
    }

    @Test
    fun aWeekOfOfflineSalesArrivesWholeAndStockAddsUp() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val p = product(a, "Beras", 2_500L)
        runBlocking { a.db().writeBlocking { tx -> StockDao.insertMovement(tx, p, MovementKind.OPENING, 5_000_000L, 1_800L, null, null, null, System.currentTimeMillis()) } }
        syncAll(a, b)
        // B offline for a "week": 1,200 sales (several segments), A sells too.
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        for (i in 0 until 1_200) sell(b, p, 1_000L, weekAgo + i * 500_000L)
        for (i in 0 until 50) sell(a, p)
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking {
            assertEquals(5_000_000L - 1_250_000L, a.db().read { StockDao.level(it, p) })
            assertEquals(1_250L, b.db().read { it.long("SELECT COUNT(*) FROM sale") })
            assertTrue(b.db().read { it.long("SELECT COUNT(*) FROM sync_segment") } >= 2L)
        }
    }

    @Test
    fun outOfOrderAndRepeatedSegmentsChangeNothing() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val c = till()
        enable(c)
        // A creates a product and sells it; B (having seen it) edits it and voids the sale.
        val p = product(a, "Teh", 990L)
        val sale = sell(a, p, 3_000L)
        runBlocking { a.sync.sync(provider); b.sync.sync(provider) }
        editPrice(b, p, 1_090L)
        runBlocking { b.db().writeBlocking { tx -> SaleDao.void(tx, sale, "wrong item", null, null, null, System.currentTimeMillis()) } }
        runBlocking { b.sync.sync(provider) }
        // C sees B's segments first (edit before creation, void before sale), then everything.
        val onlyB = File(folder.parentFile, folder.name + "-b").apply { mkdirs() }
        val bDev = runBlocking { b.db().deviceNo }
        for (f in folder.listFiles().orEmpty()) {
            val seg = SyncNames.parseSegment(f.name)
            if (seg == null || seg.dev == bDev || f.name.startsWith(".")) f.copyTo(File(onlyB, f.name), overwrite = true)
        }
        runBlocking { c.sync.sync(FolderProvider(onlyB)) }
        onlyB.deleteRecursively()
        syncAll(a, b, c)
        assertConverged(a, b, c)
        runBlocking {
            assertEquals(1L, c.db().read { it.long("SELECT status FROM sale WHERE id = ?", sale) }) // voided
            assertEquals(1_090L, c.db().read { ProductDao.get(it, p) }?.price)
            assertEquals(0L, c.db().read { StockDao.level(it, p) })
            // Deliver every segment again: nothing may change.
            val before = c.db().read { content(it) }
            c.db().write(reserveIds = 0L) { tx -> tx.update("DELETE FROM sync_cursor") }
            c.sync.sync(provider)
            assertEquals(before, c.db().read { content(it) })
        }
    }

    @Test
    fun aCountSubsumesOfflineSalesMadeBeforeIt() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val p = product(a, "Gula", 280L)
        syncAll(a, b)
        // B sells 3 while offline; later A counts 20 on the shelf, then B sells 2 more.
        sell(b, p, 3_000L)
        Thread.sleep(5)
        runBlocking { a.db().writeBlocking { tx -> StockDao.insertCount(tx, p, 20_000L, null, null, "shelf", System.currentTimeMillis()) } }
        Thread.sleep(5)
        sell(b, p, 2_000L)
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking { assertEquals(18_000L, a.db().read { StockDao.level(it, p) }) } // 20 counted − 2 sold after
    }

    @Test
    fun aTillRestoredFromAnOldBackupRejoinsWithoutCollisions() {
        val a = till()
        enable(a, "Counter A")
        val p = product(a, "Milo", 1_890L)
        repeat(3) { sell(a, p) }
        syncAll(a)
        val bytes = runBlocking {
            val out = ByteArrayOutputStream()
            BackupFiles.write(a.db(), out, File(TestDb.context.cacheDir, "sync-backup-test"), "test", "manual")
            out.toByteArray()
        }
        // The original goes on selling after the backup, and uploads it.
        repeat(2) { sell(a, p) }
        syncAll(a)
        // The backup is restored "as this till" on another phone: it must not reuse A's number.
        Restore.cancelStaged(TestDb.context)
        Restore.stage(TestDb.context, ByteArrayInputStream(bytes), Restore.Mode.REPLACE)
        val b = till() // opening applies the staged restore
        runBlocking {
            val db = b.db()
            val aDev = a.db().deviceNo
            assertNotEquals(aDev, db.deviceNo)
            assertTrue(!db.syncEnabled)
            assertEquals(0L, db.read { it.long("SELECT COUNT(*) FROM sync_segment") })
            assertTrue(db.read { it.long("SELECT seq FROM sync_cursor WHERE dev = ?", aDev.toLong()) } >= 1L)
        }
        enable(b, "Counter B")
        syncAll(a, b)
        repeat(2) { sell(b, p) }
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking {
            assertEquals(7L, b.db().read { it.long("SELECT COUNT(*) FROM sale") })
            assertEquals(7L, b.db().read { it.long("SELECT COUNT(DISTINCT receipt_no) FROM sale") })
        }
    }

    private fun backupOf(t: AppGraph): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        BackupFiles.write(t.db(), out, File(TestDb.context.cacheDir, "sync-backup-test"), "test", "manual")
        out.toByteArray()
    }

    /** The app is closed and started again on the same database (a staged restore goes in first). */
    private fun restart(t: AppGraph, name: String): AppGraph {
        TestGraph.close(t)
        tills.remove(t)
        return TestGraph.reopen(name).also { tills.add(it) }
    }

    /** The sync screen's own advice: "turn sync off and on again to move the store to another account". */
    @Test
    fun movingTheStoreToAnotherFolderStartsEveryTillsFileNumbersAgain() {
        val a = till()
        val b = till()
        enable(a, "Counter A")
        val p = product(a, "Milo", 1_890L)
        repeat(3) { sell(a, p) }
        enable(b, "Counter B")
        syncAll(a, b)
        sell(b, p)
        syncAll(a, b)
        assertConverged(a, b)

        // Both tills leave the folder and join a new, empty one.
        runBlocking {
            a.sync.disable()
            b.sync.disable()
        }
        provider = FolderProvider(File(folder, "moved").apply { mkdirs() })
        sell(a, p) // sold while sync was off
        enable(a, "Counter A")
        enable(b, "Counter B")
        sell(b, p)
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking {
            assertEquals(6L, a.db().read { it.long("SELECT COUNT(*) FROM sale") })
            assertEquals(6L, b.db().read { it.long("SELECT COUNT(*) FROM sale") })
            // Every till's files in the new folder start at 1: the others read them in order from there.
            val store = a.db().read { Meta.get(it, Meta.STORE_UUID).orEmpty() }
            val firsts = provider.list(SyncNames.segmentPrefix(store)).mapNotNull { SyncNames.parseSegment(it.name) }
                .groupBy { it.dev }.mapValues { e -> e.value.minOf { it.seq } }
            assertEquals(setOf(a.db().deviceNo, b.db().deviceNo), firsts.keys)
            assertTrue(firsts.values.all { it == 1L })
        }
    }

    /**
     * A backup made before Google Drive backup was first turned on is restored as "the same till"
     * on a new phone, while Drive already holds what that till sold after the backup.
     */
    @Test
    fun anOlderCopyOfATillThatPublishedSinceContinuesAsANewTill() {
        val a = till()
        val p = product(a, "Milo", 1_890L)
        repeat(2) { sell(a, p) }
        val bytes = backupOf(a)
        enable(a, "Counter")
        repeat(2) { sell(a, p) }
        syncAll(a)
        val aDev = runBlocking { a.db().deviceNo }

        Restore.cancelStaged(TestDb.context)
        Restore.stage(TestDb.context, ByteArrayInputStream(bytes), Restore.Mode.REPLACE)
        val name = "test-${UUID.randomUUID()}.db"
        var b = TestGraph.create(name).also { tills.add(it) }
        runBlocking { assertEquals(aDev, b.db().deviceNo) } // nothing in the backup says this till ever synced
        sell(b, p)

        // Turning the backup on finds the till's newer files in the folder: not as this till.
        val refused = assertFailsWith<SyncEngine.Problem> { enable(b, "Counter") }
        assertEquals(SyncEngine.Problem.Reason.OLD_COPY, refused.reason)
        runBlocking { assertTrue(!b.db().syncEnabled) }
        b = restart(b, name)
        runBlocking {
            assertNotEquals(aDev, b.db().deviceNo)
            assertTrue(!b.db().syncEnabled)
        }
        enable(b, "Counter")
        syncAll(a, b)
        sell(b, p)
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking { assertEquals(6L, b.db().read { it.long("SELECT COUNT(*) FROM sale") }) } // nothing lost on either side
    }

    /** An old backup restored over a till that syncs: what it published after the backup must not be numbered twice. */
    @Test
    fun restoringAnOldBackupOverASyncingTillMakesItANewTill() {
        val name = "test-${UUID.randomUUID()}.db"
        var a = TestGraph.create(name).also { tills.add(it) }
        val p = product(a, "Milo", 1_890L)
        sell(a, p)
        val bytes = backupOf(a) // before this till ever synced
        enable(a, "Counter")
        repeat(2) { sell(a, p) }
        syncAll(a)
        val oldDev = runBlocking { a.db().deviceNo }

        Restore.cancelStaged(TestDb.context)
        Restore.stage(TestDb.context, ByteArrayInputStream(bytes), Restore.Mode.REPLACE)
        try {
            a = restart(a, name)
            runBlocking {
                assertNotEquals(oldDev, a.db().deviceNo)
                assertTrue(!a.db().syncEnabled)
                assertEquals(1L, a.db().read { it.long("SELECT COUNT(*) FROM sale") })
            }
            sell(a, p)
            enable(a, "Counter")
            syncAll(a)
            runBlocking {
                // The two sales after the backup come back from the folder; the new one has its own number.
                assertEquals(4L, a.db().read { it.long("SELECT COUNT(*) FROM sale") })
                assertEquals(4L, a.db().read { it.long("SELECT COUNT(DISTINCT receipt_no) FROM sale") })
                assertEquals(4L, a.db().read { it.long("SELECT COUNT(DISTINCT id) FROM sale") })
            }
        } finally {
            Restore.backupDir(TestDb.context).listFiles { f -> f.name.startsWith(Restore.REASON_REPLACED) }?.forEach { it.delete() }
        }
    }

    @Test
    fun anInterruptedFirstSyncIsCompletedByTheNextOne() {
        val a = till()
        val p = product(a, "Kaya", 650L)
        sell(a, p)
        enable(a)
        runBlocking {
            // As if the app died during the first sync: the backfill is repeated from the start.
            a.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, SyncEngine.BACKFILLED, null) }
            a.sync.sync(provider)
        }
        val b = till()
        enable(b)
        syncAll(a, b)
        assertConverged(a, b)
    }

    @Test
    fun promotionsAndTheirSalesTravelBetweenTills() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val milo = product(a, "Milo", 390L)
        runBlocking {
            a.promotions.save(null, PromotionRow(0L, "Milo 3 for RM10", PromoKind.MULTI_PRICE, 3, 0, 1_000L, listOf(milo)))
        }
        syncAll(a, b)
        runBlocking {
            assertEquals(listOf("Milo 3 for RM10"), b.promotions.all().map { it.name }) // reloaded by the import
            // B sells three through its own bill: the promotion applies there too.
            b.cart.load()
            b.cart.addProduct(TestDb.sellable(b.db(), milo), qty = 3_000L)
            assertEquals(1_000L, b.cart.state.value.priced.total)
            val total = b.cart.state.value.priced.total
            val s = Settlement.cash(total, total, 5L) as Settlement.Result.Settled
            b.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, total, s.change)), s.rounding)
        }
        syncAll(a, b)
        assertConverged(a, b)
        runBlocking {
            val name = a.db().read { r -> r.queryList("SELECT promo_name FROM sale_line WHERE product_id = ?", arrayOf(milo.toString())) { it.getString(0) } }
            assertEquals(listOf("Milo 3 for RM10"), name)
        }
    }

    @Test
    fun eventsOfUnknownKindsWaitUntilTheTillKnowsThem() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val milo = product(a, "Milo", 390L)
        runBlocking {
            a.promotions.save(null, PromotionRow(0L, "Kept", PromoKind.BUY_GET_FREE, 1, 1, 0L, listOf(milo)))
            // As if B had received these while it was an older version that did not know them:
            val event = a.db().read { SyncDao.outboxBatch(it, 100) }.last { it.entity == Entity.PROMOTION }
            @Suppress("UNCHECKED_CAST")
            val payload = SegmentCodec.parse(event.payload) as Map<String, Any?>
            b.db().write(reserveIds = 0L) { tx ->
                SyncDao.defer(tx, SyncEvent(event.entity, event.op, event.rowId, event.hlc, payload))
                SyncDao.defer(tx, SyncEvent(99, EventOp.INSERT, 1L, event.hlc, mapOf("x" to 1L)))
            }
            b.sync.sync(provider)
            // The promotion (known now) is applied; the kind 99 event keeps waiting.
            assertEquals(listOf("Kept"), b.db().read { PromotionDao.list(it) }.map { it.name })
            assertEquals(listOf(99), b.db().read { SyncDao.deferred(it) }.map { it.second.entity })
        }
    }

    /** D-053: while a round runs, the status says what it is doing (the screen never looks stuck). */
    @Test
    fun theStatusSaysWhatARoundIsDoing() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val roti = product(a, "Roti", 350L)
        syncAll(a, b)
        sell(a, roti)
        val sending = ArrayList<SyncEngine.Status>()
        val watchA = object : SyncProvider by provider {
            override suspend fun put(name: String, file: File, props: Map<String, String>, replace: Boolean, fresh: Boolean): RemoteFile {
                sending.add(a.sync.status.value)
                return provider.put(name, file, props, replace, fresh)
            }
        }
        runBlocking { a.sync.sync(watchA) }
        assertTrue(sending.any { it.running && it.phase == SyncEngine.PHASE_SEND && it.total == 1L }, "no 'sending 1 of 1' while uploading: $sending")
        assertTrue(!a.sync.status.value.running)
        val receiving = ArrayList<SyncEngine.Status>()
        val watchB = object : SyncProvider by provider {
            override suspend fun get(remote: RemoteFile, dest: File) {
                receiving.add(b.sync.status.value)
                provider.get(remote, dest)
            }
        }
        runBlocking { b.sync.sync(watchB) }
        assertTrue(receiving.any { it.running && it.phase == SyncEngine.PHASE_RECEIVE && it.total >= 1L }, "no 'receiving' while downloading: $receiving")
        assertTrue(!b.sync.status.value.running)
        assertConverged(a, b)
    }

    /**
     * D-053: a round lists only the files created since the newest one it has seen; when that
     * short listing shows a gap (a file the folder listed late), it lists the whole folder.
     */
    @Test
    fun aRoundListsOnlyNewFilesAndAGapFallsBackToTheWholeFolder() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        val milo = product(a, "Milo", 390L)
        syncAll(a, b)
        val sinces = ArrayList<Long?>()
        val recording = object : SyncProvider by provider {
            override suspend fun list(prefix: String, since: Long?, keep: (RemoteFile) -> Boolean): List<RemoteFile> {
                if (prefix.startsWith("seg-")) sinces.add(since)
                return provider.list(prefix, since, keep)
            }
        }
        runBlocking { a.sync.sync(recording) }
        assertEquals(1, sinces.size)
        assertTrue(sinces.single() != null, "a normal round lists only new files")

        // B sells; its segment is made to look old, as if the folder had listed it very late.
        sell(b, milo)
        runBlocking { b.sync.sync(provider) }
        val bDev = runBlocking { b.db().deviceNo }
        val old = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L
        for (f in folder.listFiles().orEmpty()) if (SyncNames.parseSegment(f.name)?.dev == bDev) f.setLastModified(old)
        sell(b, milo, 2_000L)
        runBlocking { b.sync.sync(provider) }

        sinces.clear()
        runBlocking { a.sync.sync(recording) }
        assertEquals(2, sinces.size, "the short listing saw a gap and the whole folder was listed")
        assertTrue(sinces[0] != null && sinces[1] == null)
        runBlocking { assertEquals(2L, a.db().read { it.long("SELECT COUNT(*) FROM sale") }) } // both of B's sales
        assertConverged(a, b)
    }

    /** D-053: a committed change that queued sync events is announced once (auto sync starts); other writes are not. */
    @Test
    fun aChangeQueuedForUploadIsAnnouncedAfterItsCommit() {
        val a = till()
        enable(a)
        var announced = 0
        runBlocking { a.db().onOutboxCommit = { announced++ } }
        product(a, "Gula", 280L)
        assertEquals(1, announced)
        runBlocking { a.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, "test.note", "x") } }
        assertEquals(1, announced, "a write without sync events is not announced")
    }

    /** D-048: the only phone is lost; a new one turns on Google Drive backup and gets the whole shop back. */
    @Test
    fun aLostPhoneIsRestoredWholeFromDrive() {
        val a = till()
        enable(a, "Counter")
        val milo = product(a, "Milo", 1_890L)
        val gula = product(a, "Gula", 280L)
        val siti = runBlocking {
            a.settings.load()
            a.settings.saveStore(a.settings.store.value.copy(name = "Kedai Runcit Ali", creditEnabled = true))
            a.staffAdmin.save(null, "Aminah", Seed.Ids.ROLE_CASHIER, true)
            a.promotions.save(null, PromotionRow(0L, "Milo 3 for RM10", PromoKind.MULTI_PRICE, 3, 0, 1_000L, listOf(milo)))
            a.db().writeBlocking { tx -> StockDao.insertCount(tx, gula, 40_000L, null, null, "shelf", System.currentTimeMillis()) }
            val c = a.customers.save(null, Customer(0L, "Siti", creditLimit = 10_000L))
            a.db().writeBlocking { tx -> CustomerDao.insertCredit(tx, c.id, CreditKind.CHARGE, 2_500L, null, null, null, null, null, System.currentTimeMillis()) }
            c
        }
        repeat(5) { sell(a, gula) }
        sell(a, milo, 2_000L)
        syncAll(a)
        val before = runBlocking { a.db().read { content(it) } }
        val store = runBlocking { a.db().read { Meta.get(it, Meta.STORE_UUID) } }
        val lostDevice = runBlocking { a.db().deviceNo }
        tills.remove(a)
        TestGraph.destroy(a) // the phone is gone

        val n = till()
        assertTrue(runBlocking { n.settings.needsSetup() }) // a new phone starts at the welcome screen
        enable(n, "Counter")
        syncAll(n)
        runBlocking {
            val after = n.db().read { content(it) }
            for (k in before.keys) {
                assertTrue(after.getValue(k).containsAll(before.getValue(k)), "table $k was not restored whole")
            }
            assertEquals(before["sale"], after["sale"])
            assertEquals(before["stock_level"], after["stock_level"])
            assertEquals(store, n.db().read { Meta.get(it, Meta.STORE_UUID) })
            assertNotEquals(lostDevice, n.db().deviceNo) // its own sales never collide with the lost phone's
            assertEquals(35_000L, n.db().read { StockDao.level(it, gula) })
            assertEquals(2_500L, n.db().read { CustomerDao.balance(it, siti.id) })
            assertEquals(listOf("Milo 3 for RM10"), n.promotions.all().map { it.name })
            assertTrue(n.staffAdmin.staff().any { it.name == "Aminah" })
            n.settings.load()
            assertEquals("Kedai Runcit Ali", n.settings.store.value.name)
            assertTrue(!n.settings.needsSetup()) // no welcome screen after the restore
            assertEquals(BackupService.Protection.State.PROTECTED, n.backups.refreshProtection().state)
        }
        sell(n, milo) // and selling goes on at once
        runBlocking { assertEquals(7L, n.db().read { it.long("SELECT COUNT(*) FROM sale") }) }
    }

    @Test
    fun creditAndSettingsTravelBetweenTills() {
        val a = till()
        enable(a)
        val b = till()
        enable(b)
        runBlocking {
            a.settings.load()
            a.settings.saveStore(a.settings.store.value.copy(name = "Kedai Runcit Ali", creditEnabled = true))
            val customer = a.customers.save(null, Customer(0L, "Siti", creditLimit = 10_000L))
            a.db().writeBlocking { tx -> CustomerDao.insertCredit(tx, customer.id, CreditKind.CHARGE, 2_500L, null, null, null, null, null, System.currentTimeMillis()) }
            syncAll(a, b)
            b.db().writeBlocking { tx -> CustomerDao.insertCredit(tx, customer.id, CreditKind.PAYMENT, 1_000L, null, 1L, null, null, null, System.currentTimeMillis()) }
            syncAll(a, b)
            assertConverged(a, b)
            assertEquals(1_500L, a.db().read { CustomerDao.balance(it, customer.id) })
            b.settings.load()
            assertEquals("Kedai Runcit Ali", b.settings.store.value.name) // reloaded after the import
            assertTrue(b.settings.store.value.creditEnabled)
        }
    }
}
