package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Meta
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.util.Log
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The catalogue's "Popular" tab (D-049): what this shop sold most over the last [DAYS] days, so
 * bread, eggs and loose vegetables are one tap away without setting anything up. The products
 * themselves are read fresh every time, so a new price shows at once.
 *
 * The ranking reads a month of sales (~0.3 s for a store of 50,000 products on a low-end tablet),
 * so the tab never waits for it (D-058): the last ranking is kept in this till's `meta` and shown
 * at once; when it is older than [TTL_MS] a fresh one is made in the background for next time.
 * Only a till without a ranking from the last [MAX_AGE_MS] waits for one. Used from the main thread.
 */
class PopularItems(private val graph: AppGraph) {

    private var ranking: List<Long> = emptyList()
    private var rankedAt = 0L
    private var restored = false
    private var refresh: Job? = null

    suspend fun load(now: Long = System.currentTimeMillis()): List<ProductListItem> {
        val db = graph.db()
        if (!restored) {
            restored = true
            db.read { Meta.get(it, KEY) }?.let { kept ->
                val at = kept.substringBefore('|').toLongOrNull()
                val ids = kept.substringAfter('|', "").split(',').mapNotNull { it.toLongOrNull() }
                if (at != null && ids.isNotEmpty()) {
                    ranking = ids
                    rankedAt = at
                }
            }
        }
        val age = now - rankedAt
        if (ranking.isEmpty() || age !in 0L..MAX_AGE_MS) {
            rank(now) // nothing recent to show: wait for it (an empty one is not kept: a new shop's first sales show at once)
        } else if (age > TTL_MS && refresh?.isActive != true) {
            refresh = graph.appScope.launch(Dispatchers.Main) {
                delay(REFRESH_DELAY_MS) // not in the way of the screen that asked
                try {
                    rank(System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.w("Popular items not refreshed", e) // the kept ranking stays
                }
            }
        }
        if (ranking.isEmpty()) return emptyList()
        return db.read { ProductDao.listByIds(it, ranking) }.take(LIMIT)
    }

    private suspend fun rank(now: Long) {
        val db = graph.db()
        val today = Days.epochDay(now, TimeZone.getDefault())
        val ids = db.read { ProductDao.popularIds(it, today - DAYS + 1, today, LIMIT + SPARE) }
        ranking = ids
        rankedAt = now
        if (ids.isNotEmpty()) db.write(reserveIds = 0) { tx -> Meta.put(tx.db, KEY, "$now|" + ids.joinToString(",")) }
    }

    companion object {
        const val DAYS = 30L
        const val LIMIT = 40

        /** Room for best sellers that were since deleted or switched off. */
        private const val SPARE = 10
        private const val TTL_MS = 10L * 60L * 1000L
        private const val MAX_AGE_MS = 3L * 24L * 3600L * 1000L
        private const val REFRESH_DELAY_MS = 3_000L

        /** This till's last ranking: "<made at ms>|id,id,…" (LOCAL, D-058). */
        const val KEY = "dev.popular"
    }
}
