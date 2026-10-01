package com.lekaspos.domain.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.report.Period
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Db
import com.lekaspos.data.report.ReceiptRow
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.sale.CommittedSale
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.testing.TestDb
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The receipts export of a period lists the sales its report counts: by the business day stored
 * with each sale, whatever time zone the till had (2026-10 review).
 */
@RunWith(AndroidJUnit4::class)
class ReceiptDaysTest {

    private lateinit var db: Db
    private val utc = TimeZone.getTimeZone("UTC")
    private val myt = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private var teh = 0L

    @Before
    fun setUp() {
        db = TestDb.fresh()
        teh = TestDb.product(db, "Teh tarik", 250L)
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    private fun sell(at: Long, tz: TimeZone): CommittedSale = db.writeBlocking { tx ->
        SaleDao.commit(tx, TestDb.saleDraft(db, listOf(teh to 1_000L), soldAt = at), tz)
    }

    @Test
    fun theReceiptListOfAPeriodIsWhatItsReportCounts() {
        val oct1 = Days.fromYmd(20261001)
        val midnight = oct1 * Days.DAY_MS // 1 Oct 00:00 UTC
        val hour = 3_600_000L
        val late = sell(midnight + 23L * hour + hour / 2, utc) // 1 Oct on a till set to UTC (2 Oct in Malaysia)
        val morning = sell(midnight + 10L * hour, utc)
        val early = sell(midnight - hour, myt) // 30 Sep 23:00 UTC = 1 Oct 07:00 in Malaysia
        sell(midnight + 24L * hour + hour / 2, utc) // 2 Oct
        sell(midnight - 17L * hour, myt) // 30 Sep 15:00 in Malaysia

        val period = Period(oct1, oct1 + 1)
        val ids = HashSet<Long>()
        var after: ReceiptRow? = null
        while (true) {
            val page = db.readBlocking { ReportDao.receiptsOfDays(it, period.from, period.to, after, limit = 1) }
            if (page.isEmpty()) break
            for (r in page) {
                assertEquals(oct1, r.day)
                assertTrue(ids.add(r.id))
            }
            after = page.last()
        }
        assertEquals(setOf(late.id, morning.id, early.id), ids)
        assertEquals(3L, db.readBlocking { ReportDao.totals(it, period.from, period.to) }.saleCount)
    }
}
