package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.time.Days
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import java.util.TimeZone

/**
 * The catalogue's "Popular" tab (D-049): what this shop sold most over the last [DAYS] days, so
 * bread, eggs and loose vegetables are one tap away without setting anything up. The ranking
 * (the heavier part) is kept for [TTL_MS]; the products themselves are read fresh every time,
 * so a new price shows at once. Used from the main thread only.
 */
class PopularItems(private val graph: AppGraph) {

    private var ranking: List<Long> = emptyList()
    private var rankedAt = 0L

    suspend fun load(now: Long = System.currentTimeMillis()): List<ProductListItem> {
        val db = graph.db()
        if (rankedAt == 0L || now - rankedAt > TTL_MS) {
            val today = Days.epochDay(now, TimeZone.getDefault())
            ranking = db.read { ProductDao.popularIds(it, today - DAYS + 1, today, LIMIT + SPARE) }
            rankedAt = now
        }
        if (ranking.isEmpty()) return emptyList()
        return db.read { ProductDao.listByIds(it, ranking) }.take(LIMIT)
    }

    companion object {
        const val DAYS = 30L
        const val LIMIT = 40

        /** Room for best sellers that were since deleted or switched off. */
        private const val SPARE = 10
        private const val TTL_MS = 10L * 60L * 1000L
    }
}
