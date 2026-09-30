package com.lekaspos.domain.sell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.time.Days
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The catalogue's "Popular" tab (D-049): best sellers of the last 30 days, from the daily summary. */
@RunWith(AndroidJUnit4::class)
class PopularItemsTest {

    private val tz = TimeZone.getDefault()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = TestGraph.create()
    }

    @After
    fun tearDown() = TestGraph.destroy(graph)

    private fun sell(productId: Long, qty: Long, at: Long = System.currentTimeMillis()) = runBlocking {
        val db = graph.db()
        db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(productId to qty), soldAt = at), tz) }
    }

    @Test
    fun aShopWithoutSalesHasNoPopularTab() = runBlocking {
        TestDb.product(graph.db(), "Roti", 350L)
        assertTrue(graph.popular.load().isEmpty())
    }

    @Test
    fun bestSellersOfTheLastThirtyDaysComeFirst() = runBlocking {
        val db = graph.db()
        val roti = TestDb.product(db, "Roti", 350L)
        val telur = TestDb.product(db, "Telur", 1_650L)
        val gula = TestDb.product(db, "Gula", 280L)
        val old = TestDb.product(db, "Sold long ago", 100L)
        val gone = TestDb.product(db, "Deleted since", 100L)
        repeat(3) { sell(telur, 2_000L) } // 6
        sell(roti, 4_000L) // 4
        sell(gula, 1_000L) // 1
        sell(gone, 9_000L)
        sell(old, 50_000L, at = System.currentTimeMillis() - 45L * Days.DAY_MS) // outside the 30 days
        db.writeBlocking { tx -> ProductDao.delete(tx, gone, System.currentTimeMillis()) }

        val now = System.currentTimeMillis()
        val today = Days.epochDay(now, tz)
        val ids = db.read { ProductDao.popularIds(it, today - 29, today, 10) }
        assertEquals(listOf(gone, telur, roti, gula), ids) // the ranking still counts it; the tab leaves it out
        assertEquals(listOf("Telur", "Roti", "Gula"), graph.popular.load(now).map { it.name })
    }

    @Test
    fun aNewPriceShowsAtOnceEvenWhileTheRankingIsKept() = runBlocking {
        val db = graph.db()
        val roti = TestDb.product(db, "Roti", 350L)
        sell(roti, 1_000L)
        val now = System.currentTimeMillis()
        assertEquals(350L, graph.popular.load(now).single().price)
        db.writeBlocking { tx ->
            val p = ProductDao.get(tx.db, roti) ?: error("no product")
            ProductDao.update(tx, p, p.copy(price = 400L), System.currentTimeMillis())
        }
        assertEquals(400L, graph.popular.load(now + 1_000L).single().price)
    }
}
