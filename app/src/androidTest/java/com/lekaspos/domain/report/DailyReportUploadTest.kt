package com.lekaspos.domain.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.csv.CsvReader
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Meta
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.sync.AuthNeeded
import com.lekaspos.sync.ReportFolder
import com.lekaspos.sync.SyncEngine
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.io.File
import java.io.StringReader
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The daily sales report to Google Drive (D-065), with a folder in memory instead of Drive. */
@RunWith(AndroidJUnit4::class)
class DailyReportUploadTest {

    private lateinit var graph: AppGraph
    private lateinit var upload: DailyReportUpload
    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private val folder = MemoryFolder()
    private val scheduled = ArrayList<Pair<Boolean, Boolean>>()

    private class MemoryFolder : ReportFolder {
        val files = LinkedHashMap<String, String>()
        var failure: Exception? = null

        override suspend fun put(name: String, file: File, mime: String) {
            failure?.let { throw it }
            assertEquals("text/csv", mime)
            files[name] = file.readText(Charsets.UTF_8)
        }
    }

    @Before
    fun setUp() {
        graph = TestGraph.create()
        upload = DailyReportUpload(graph, TestDb.context, folderOf = { folder }, schedule = { on, soon -> scheduled.add(on to soon) })
    }

    @After
    fun tearDown() {
        TestGraph.destroy(graph)
    }

    private fun at(ymd: Int, hour: Int = 12) = Days.startOfDay(Days.fromYmd(ymd), tz) + hour * 3_600_000L

    private fun sell(vararg days: Int) = runBlocking {
        val db = graph.db()
        val p = TestDb.product(db, "Kopi", 1_250L)
        for (ymd in days) db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L), soldAt = at(ymd)), tz) }
    }

    private fun rows(csv: String): List<List<String>> {
        val r = CsvReader(StringReader(csv))
        val out = ArrayList<List<String>>()
        while (true) out.add(r.next()?.toList() ?: break)
        return out
    }

    @Test
    fun eachFinishedDayGoesIntoItsMonthsFile() = runBlocking {
        sell(20260930, 20261001, 20261005, 20261006)
        assertTrue(upload.upload(now = at(20261006), tz = tz).isEmpty(), "off: nothing is sent")
        upload.turnOn("shop@example.com")
        assertEquals(listOf(true to false), scheduled)

        // The first upload: last month too, so the owner sees a whole month at once.
        assertEquals(listOf("2026-09 daily-sales.csv", "2026-10 daily-sales.csv"), upload.upload(now = at(20261006), tz = tz))
        val oct = rows(folder.files.getValue("2026-10 daily-sales.csv"))
        assertEquals(listOf("date", "sales", "refunds", "net_sales_ex_tax", "tax", "total", "discount", "cost", "gross_profit"), oct[0])
        // 1–5 October, days without sales too; today's sale (the 6th) is not in it yet.
        assertEquals((1..5).map { "2026-10-0$it" }, oct.drop(1).map { it[0] })
        assertEquals(listOf("1", "0", "0", "0", "1"), oct.drop(1).map { it[1] })
        assertEquals("12.50", oct[1][5])
        assertEquals(1 + 30, rows(folder.files.getValue("2026-09 daily-sales.csv")).size) // the header and every day of September

        // Later the same day: nothing to do. The next day: October again, with the 6th.
        assertTrue(upload.upload(now = at(20261006, 20), tz = tz).isEmpty())
        assertEquals(listOf("2026-10 daily-sales.csv"), upload.upload(now = at(20261007), tz = tz))
        val again = rows(folder.files.getValue("2026-10 daily-sales.csv"))
        assertEquals(listOf("2026-10-06", "1"), again.last().take(2))
        val s = upload.load()
        assertEquals(Days.fromYmd(20261006), s.lastDay)
        assertEquals(at(20261007), s.lastOk)
        assertNull(s.error)
    }

    @Test
    fun aFailedUploadIsSaidAndTriedAgain() = runBlocking {
        sell(20261005)
        upload.turnOn("shop@example.com")
        folder.failure = java.net.UnknownHostException("www.googleapis.com")
        assertFailsWith<java.net.UnknownHostException> { upload.upload(now = at(20261006), tz = tz) }
        var s = upload.load()
        assertEquals(SyncEngine.ERROR_OFFLINE, s.error)
        assertNull(s.lastDay, "nothing counts as written")
        folder.failure = object : AuthNeeded("access withdrawn") {}
        assertFailsWith<AuthNeeded> { upload.upload(now = at(20261006), tz = tz) }
        assertEquals(SyncEngine.ERROR_SIGN_IN, upload.load().error)
        folder.failure = null
        assertEquals(2, upload.upload(now = at(20261006), tz = tz).size)
        s = upload.load()
        assertNull(s.error)
        assertEquals(Days.fromYmd(20261005), s.lastDay)
    }

    @Test
    fun uploadNowWritesAgainAndOffStopsIt() = runBlocking {
        sell(20261002)
        upload.turnOn("shop@example.com")
        upload.upload(now = at(20261006), tz = tz)
        folder.files.clear()
        // "Upload now": written again although nothing new has ended.
        assertEquals(listOf("2026-09 daily-sales.csv", "2026-10 daily-sales.csv"), upload.upload(again = true, now = at(20261006, 20), tz = tz))
        upload.turnOff()
        assertEquals(false to false, scheduled.last())
        assertTrue(upload.upload(again = true, now = at(20261008), tz = tz).isEmpty())
        val s = upload.load()
        assertTrue(!s.on && s.lastDay == null && s.lastOk == null)
        // Both changes are in the activity log.
        val logged = graph.db().read { r ->
            r.rawQuery("SELECT COUNT(*) FROM audit_log WHERE detail LIKE 'daily sales report to Google Drive%'", null).use { it.moveToFirst(); it.getLong(0) }
        }
        assertEquals(2L, logged)
    }

    @Test
    fun theStartAsksForAnUploadOnlyWhenADayHasEnded() = runBlocking {
        upload.atStart(now = at(20261006), tz = tz)
        assertTrue(scheduled.isEmpty(), "off: no job")
        upload.turnOn("shop@example.com")
        upload.atStart(now = at(20261006), tz = tz)
        assertEquals(true to true, scheduled.last())
        upload.upload(now = at(20261006), tz = tz)
        upload.atStart(now = at(20261006, 20), tz = tz)
        assertEquals(true to false, scheduled.last())
        // The meta keys are this till's (a restore as a new till clears them by their prefix).
        val keys = graph.db().read { r ->
            r.rawQuery("SELECT key FROM meta WHERE key LIKE 'drive_report.%'", null).use { c ->
                val k = ArrayList<String>()
                while (c.moveToNext()) k.add(c.getString(0))
                k
            }
        }
        assertTrue(keys.all { it.startsWith(Meta.DRIVE_REPORT_PREFIX) } && Meta.DRIVE_REPORT_ACCOUNT in keys)
    }
}
