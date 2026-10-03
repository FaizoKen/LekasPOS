package com.lekaspos.domain.promo

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.pricing.Promotion
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.promo.PromotionDao
import com.lekaspos.data.promo.PromotionRow
import com.lekaspos.domain.Approval
import java.util.TimeZone
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Promotions (Phase 8, references/money.md §11). A shop has a handful, so they live in memory:
 * pricing runs on every scan and reads [active] without touching the disk. Loaded with the open
 * bill, reloaded after edits and after sync brings changes; [version] tells the bill to reprice.
 */
class PromotionService(private val graph: AppGraph) {

    @Volatile
    private var rows: List<PromotionRow> = emptyList()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** One load at a time: a sync reload that read before an edit's commit published its older list last. */
    private val loadLock = Mutex()

    suspend fun load() {
        loadLock.withLock {
            rows = graph.db().read { PromotionDao.list(it) }
            _version.update { it + 1 }
        }
        graph.cart.reprice()
    }

    fun all(): List<PromotionRow> = rows

    /** Promotions running today, as the pricing rules take them. Memory only. */
    fun active(now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): List<Promotion> {
        val today = Days.epochDay(now, tz)
        return rows.mapNotNull { r -> if (r.runsOn(today) && r.productIds.isNotEmpty()) toCore(r) else null }
    }

    /** Active promotions that include [productId] (price check). */
    fun forProduct(productId: Long): List<Promotion> = active().filter { productId in it.productIds }

    suspend fun save(before: PromotionRow?, p: PromotionRow, approval: Approval? = null): Long {
        val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS, approval)
        require(toCore(p) != null) { "invalid promotion" }
        val now = System.currentTimeMillis()
        // Write and reload together, also when the screen closes meanwhile (the till kept the old deal).
        return withContext(NonCancellable) { saveNow(before, p, actor, now) }
    }

    private suspend fun saveNow(before: PromotionRow?, p: PromotionRow, actor: com.lekaspos.domain.Actor, now: Long): Long {
        val id = graph.db().write { tx ->
            val id = if (before == null) {
                PromotionDao.insert(tx, p, now)
            } else {
                PromotionDao.update(tx, before, p.copy(id = before.id), now)
                before.id
            }
            AuditDao.log(
                tx, AuditAction.PROMOTION_CHANGE, actor.staffId, now, Entity.PROMOTION, id,
                amount = p.groupPrice, detail = p.name, approvedBy = actor.approvedBy,
            )
            id
        }
        load()
        return id
    }

    suspend fun delete(id: Long, approval: Approval? = null) = withContext(NonCancellable) {
        val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS, approval)
        graph.db().write { tx ->
            val now = System.currentTimeMillis()
            PromotionDao.delete(tx, id, now)
            AuditDao.log(
                tx, AuditAction.PROMOTION_CHANGE, actor.staffId, now, Entity.PROMOTION, id,
                detail = "deleted", approvedBy = actor.approvedBy,
            )
        }
        load()
    }

    companion object {
        /** The pricing form of a stored promotion, or null when its numbers are not valid. */
        fun toCore(r: PromotionRow): Promotion? = try {
            Promotion(r.id, r.name, r.kind, r.buyQty, r.freeQty, r.groupPrice, r.productIds.toSet())
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
