package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.data.promo.PromotionRow
import com.lekaspos.data.stock.StockDao
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused
import java.util.TimeZone

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
        /** Promotions running today that include this product. */
        val promotions: List<PromotionRow> = emptyList(),
        /** Cost of one base unit (minor units; 0 = never entered), for a new price's margin. */
        val cost: Long = 0L,
        val taxBp: Int = 0,
    )

    /**
     * A new price for product [productId], straight from the price check: a wrong shelf price found at
     * the till, or prices raised after a delivery, scanned one by one instead of opening every product
     * (2026-10 review). Needs MANAGE_PRODUCTS (or a manager's [approval]); written like a product edit
     * (only the price, LWW, synced) and audited like one. Lines already on a bill keep their price.
     * Returns the price as it was.
     */
    suspend fun setPrice(productId: Long, price: Long, approval: Approval? = null): Long {
        require(price >= 0L) { "negative price" }
        val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS, approval)
        val c = graph.settings.store.value.currency
        return graph.db().write(reserveIds = 2L) { tx ->
            val now = System.currentTimeMillis()
            // As stored now: a product deleted meanwhile (here or on another till) is not brought back.
            val before = ProductDao.get(tx.db, productId)?.takeIf { ProductDao.isLive(tx.db, productId) }
                ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (before.sellMode == SellMode.OPEN_PRICE) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
            if (before.price != price) {
                ProductDao.update(tx, before, before.copy(price = price), now)
                AuditDao.log(
                    tx, AuditAction.PRODUCT_PRICE_CHANGE, actor.staffId, now, Entity.PRODUCT, productId, price,
                    "${before.name}: ${MoneyFormat.format(before.price, c)} -> ${MoneyFormat.format(price, c)} (price check)",
                    actor.approvedBy,
                )
            }
            before.price
        }
    }

    suspend fun lookup(query: String, limit: Int = 5): List<Info> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val templates = graph.settings.store.value.templates()
        val promos = graph.promotions.all()
        val running = graph.promotions.active()
        val today = Days.epochDay(System.currentTimeMillis(), TimeZone.getDefault())
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
                // The deal the till really gives: products sold by the piece only, and of several
                // running deals the one the pricing takes (the lowest id) — it listed deals that never
                // applied (2026-10 review).
                val winner = running.filter { p.id in it.productIds }.minOfOrNull { it.id }
                val deals = if (p.sellMode != SellMode.UNIT) emptyList() else promos.filter { it.id == winner }
                val stock = if (p.trackStock) StockDao.level(r, p.id) else null
                Info(p.id, p.name, p.unit, p.sellMode, p.price, stock, packs, p.active, deals, p.cost, p.taxBp)
            }
        }
    }

}
