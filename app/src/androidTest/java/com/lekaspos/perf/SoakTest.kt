package com.lekaspos.perf

import android.app.Activity
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.db.Seed
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import com.lekaspos.ui.sell.SellActivity
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The memory budget (references/performance.md: Java heap after GC ≤ 48 MB, no growth over a
 * 1,000-sale soak) and screen leaks. Sales go through the real scan → cart → checkout path.
 * Runs on the API 21 (1 GB) and API 36 CI emulators.
 */
@RunWith(AndroidJUnit4::class)
class SoakTest {

    private lateinit var graph: AppGraph
    private val codes = ArrayList<String>()

    @Before
    fun setUp() {
        graph = TestGraph.create()
        runBlocking {
            graph.cart.load()
            val db = graph.db()
            for (i in 0 until 60) {
                val code = "2990" + i.toString().padStart(9, '0')
                TestDb.product(db, "Soak item $i", 150L + i * 35L, listOf(code))
                codes.add(code)
            }
        }
    }

    @After
    fun tearDown() = TestGraph.destroy(graph)

    private fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) {
            rt.gc()
            System.runFinalization()
            Thread.sleep(40)
        }
        return rt.totalMemory() - rt.freeMemory()
    }

    private suspend fun sale(i: Int) {
        for (k in 0 until 5) {
            val r = graph.cart.scan(codes[(i * 5 + k) % codes.size])
            assertTrue(r is CartSession.ScanResult.Added, "scan $i/$k: $r")
        }
        val total = graph.cart.state.value.priced.total
        val s = Settlement.cash(total, total + 1_000L, 5L) as Settlement.Result.Settled
        graph.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, total + 1_000L, s.change)), s.rounding)
    }

    @Test
    fun aThousandSalesKeepTheHeapFlat() = runBlocking {
        val samples = ArrayList<Long>()
        for (i in 0 until 1_000) {
            sale(i)
            if (i % 100 == 99) samples.add(usedHeapAfterGc())
        }
        val mb = samples.map { it / 1024.0 / 1024.0 }
        Log.i("LekasSoak", "heap after GC every 100 sales (MB): " + mb.joinToString { "%.1f".format(it) })
        assertTrue(samples.max() <= BUDGET, "heap ${mb.max()} MB over the 48 MB budget")
        // Growth: the lowest of the last three samples against the lowest of samples 2–4 (after warm-up).
        val early = samples.subList(1, 4).min()
        val late = samples.subList(samples.size - 3, samples.size).min()
        assertTrue(late - early <= MAX_GROWTH, "heap grew ${(late - early) / 1024} KB over 1,000 sales ($mb)")
        assertEquals(1_000L, graph.db().read { com.lekaspos.data.sale.SaleDao.count(it) })
    }

    @Test
    fun theSellingScreenIsFreedAfterRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { com.lekaspos.app.LekasApp.graph(app).settings.markSetupDone() } // no welcome screen on top
        val refs = ArrayList<WeakReference<Activity>>()
        ActivityScenario.launch(SellActivity::class.java).use { scenario ->
            repeat(5) {
                scenario.onActivity { refs.add(WeakReference(it)) }
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            usedHeapAfterGc()
            var current: Activity? = null
            scenario.onActivity { current = it }
            val leaked = refs.count { r -> r.get().let { it != null && it !== current } }
            // No strong reference from this test while the heap is saved: CI shows the real chain.
            if (leaked > 0) HeapDumps.save(app, "selling-screen")
            assertEquals(0, leaked, "old selling screens still in memory: $leaked (heap dump analysed in CI: leaks-api*)")
        }
    }

    private companion object {
        const val BUDGET = 48L * 1024L * 1024L
        const val MAX_GROWTH = 2L * 1024L * 1024L
    }
}
