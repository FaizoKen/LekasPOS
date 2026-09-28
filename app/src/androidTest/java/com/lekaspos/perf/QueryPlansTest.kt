package com.lekaspos.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.data.db.Db
import com.lekaspos.testing.TestDb
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plan checker itself must catch the query shapes that are slow at 1M+ rows. */
@RunWith(AndroidJUnit4::class)
class QueryPlansTest {

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
    fun registeredQueriesPassOnAnEmptyDatabase() {
        val problems = db.readBlocking { QueryPlans.check(it) }.filter { it.violations.isNotEmpty() }
        assertEquals(emptyList(), problems.map { "${it.name}: ${it.violations}" })
    }

    @Test
    fun flagsAFullScanInAHotQuery() {
        val check = db.readBlocking { QueryPlans.check(it, "bad_lookup", "SELECT id FROM sale WHERE note = ?") }
        assertTrue(check.violations.any { it.startsWith("full scan of sale") }, check.plan.toString())
    }

    @Test
    fun flagsACorrelatedScanInMaintenanceSql() {
        val sql = "SELECT s.id, (SELECT COUNT(*) FROM sale r WHERE r.total > s.total) FROM sale s"
        val check = db.readBlocking { QueryPlans.check(it, "bad_rebuild", sql, maintenance = true) }
        assertTrue(check.violations.any { it.startsWith("correlated subquery scans sale") }, check.plan.toString())
    }

    @Test
    fun allowsAPlainScanInMaintenanceSql() {
        val check = db.readBlocking { QueryPlans.check(it, "rebuild", "SELECT day, SUM(total) FROM sale GROUP BY day", maintenance = true) }
        assertEquals(emptyList(), check.violations)
    }

    @Test
    fun flagsASortWhereIndexOrderIsExpected() {
        val check = db.readBlocking {
            QueryPlans.check(it, "history_first", "SELECT id FROM sale ORDER BY total DESC LIMIT 50")
        }
        assertTrue(check.violations.any { it.startsWith("sorts instead of reading index order") || it.startsWith("full scan") }, check.plan.toString())
    }
}
