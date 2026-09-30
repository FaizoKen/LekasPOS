package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.data.stock.StockDao

/**
 * Price check (Phase 8): what a product costs and how many are in stock, without touching the
 * bill. A barcode (including pack barcodes and scale labels) resolves as when selling; anything
 * else is a name/SKU search.
 */
class PriceCheck(private val graph: AppGraph) {

    data class Pack(val qty: Long, val price: Long)

    data class Info(
        val productId: Long,
        val name: String,
        val unit: String,
        val sellMode: Int,
        /** Price per selling unit (minor units). */
        val price: Long,
        /** On hand in milli-units, or null when stock is not tracked. */
        val stock: Long?,
        /** Pack barcodes with their own price (e.g. a carton of 24). */
        val packs: List<Pack>,
        val active: Boolean,
    )

    suspend fun lookup(query: String, limit: Int = 5): List<Info> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val templates = graph.settings.store.value.templates()
        return graph.db().read { r ->
            val products: List<SellableProduct> = when (val res = BarcodeLookup.resolve(r, q, templates)) {
                is Resolution.Plain -> listOf(res.hit.product)
                is Resolution.Scale -> listOf(res.product)
                is Resolution.NotFound ->
                    ProductDao.search(r, q, limit).mapNotNull { ProductDao.sellableById(r, it.id) }
            }
            products.map { p ->
                val packs = ProductDao.barcodes(r, p.id)
                    .filter { it.packQty != 1000L || it.packPrice != null }
                    .map { Pack(it.packQty, it.packPrice ?: PricingEngine.lineGross(p.price, it.packQty)) }
                Info(p.id, p.name, p.unit, p.sellMode, p.price, if (p.trackStock) StockDao.level(r, p.id) else null, packs, p.active)
            }
        }
    }
}
