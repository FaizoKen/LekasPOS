package com.lekaspos.domain.sale

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.refund.RefundPart
import com.lekaspos.core.refund.RefundSource
import com.lekaspos.core.refund.Refunds
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.sale.CommittedSale
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleHeader
import com.lekaspos.data.sale.SaleLineDraft
import com.lekaspos.data.sale.SaleLineFull
import com.lekaspos.data.sale.SaleQueries
import java.util.TimeZone

/** Why a sale action was refused. */
class ActionRefused(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_ALLOWED, NOT_FOUND, VOIDED, HAS_REFUNDS, NOT_A_SALE, NOTHING_TO_REFUND }
}

/**
 * Actions on stored sales: refunds/returns, voids, reprints, and the manual drawer open.
 * Each checks its permission and writes an audit entry in the same transaction (D-028).
 */
class SaleActions(private val graph: AppGraph) {

    /** A sale and, per line, what can still be returned. */
    data class RefundInfo(val header: SaleHeader, val lines: List<SaleLineFull>, val sources: Map<Long, RefundSource>)

    suspend fun refundInfo(saleId: Long): RefundInfo? = graph.db().read { r ->
        val h = SaleQueries.header(r, saleId) ?: return@read null
        val lines = SaleQueries.lines(r, saleId)
        val done = SaleQueries.refundedByLine(r, saleId)
        RefundInfo(h, lines, lines.associate { it.id to source(it, done[it.id]) })
    }

    /**
     * Returns [picks] (line id → qty in milli-units) of sale [saleId] as a refund document paid
     * out with [method]. Cash refunds mirror the cash rounding.
     */
    suspend fun refund(saleId: Long, picks: Map<Long, Long>, restock: Boolean, reason: String, method: PaymentMethod): CommittedSale {
        if (!graph.permissions.allowed(Perm.REFUND)) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val staffId = graph.staff.staffId
        val tz = TimeZone.getDefault()
        val sale = graph.db().write(reserveIds = picks.size + 16L) { tx ->
            // Re-read inside the transaction: another refund may have been made meanwhile.
            val h = SaleQueries.header(tx.db, saleId) ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (h.kind != SaleKind.SALE) throw ActionRefused(ActionRefused.Reason.NOT_A_SALE)
            if (h.voided) throw ActionRefused(ActionRefused.Reason.VOIDED)
            val lines = SaleQueries.lines(tx.db, saleId).associateBy { it.id }
            val done = SaleQueries.refundedByLine(tx.db, saleId)
            val parts = ArrayList<Pair<RefundPart, SaleLineFull>>()
            for ((lineId, qty) in picks) {
                if (qty <= 0L) continue
                val line = lines[lineId] ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
                val src = source(line, done[lineId])
                if (qty > src.remainingQty) throw ActionRefused(ActionRefused.Reason.NOTHING_TO_REFUND)
                parts.add(Refunds.part(src, qty) to line)
            }
            if (parts.isEmpty()) throw ActionRefused(ActionRefused.Reason.NOTHING_TO_REFUND)
            val now = System.currentTimeMillis()
            val draft = refundDraft(h, parts, restock, reason, method, store.cashStep, staffId, now)
            val committed = SaleDao.commit(tx, draft, tz)
            AuditDao.log(
                tx, AuditAction.REFUND, staffId, now, Entity.SALE, committed.id, draft.total, "${h.receiptNo}: $reason",
            )
            if (device.hasPrinter) {
                if (device.drawerEnabled && method.opensDrawer && draft.total != 0L) {
                    PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, committed.id, 1, now)
                }
                if (device.autoPrint) PrintJobDao.enqueue(tx, PrintJobKind.RECEIPT, committed.id, 1, now)
            }
            committed
        }
        graph.printer.wake()
        return sale
    }

    /** Voids a whole sale or refund (wrong transaction). */
    suspend fun void(saleId: Long, reason: String) {
        if (!graph.permissions.allowed(Perm.VOID)) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        val staffId = graph.staff.staffId
        graph.db().write(reserveIds = 8L) { tx ->
            val h = SaleQueries.header(tx.db, saleId) ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (h.voided) throw ActionRefused(ActionRefused.Reason.VOIDED)
            // Refunds of this sale must be voided first, or stock and totals would be reversed twice.
            if (h.kind == SaleKind.SALE && h.refunded != 0L) throw ActionRefused(ActionRefused.Reason.HAS_REFUNDS)
            val now = System.currentTimeMillis()
            SaleDao.void(tx, saleId, reason, staffId, null, null, now)
            AuditDao.log(tx, AuditAction.SALE_VOID, staffId, now, Entity.SALE, saleId, h.total, "${h.receiptNo}: $reason")
        }
    }

    /** Prints a sale again: a copy (audited) or, right after the sale, its first receipt. */
    suspend fun print(saleId: Long, copy: Boolean) {
        if (copy && !graph.permissions.allowed(Perm.REPRINT)) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        val staffId = graph.staff.staffId
        graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            PrintJobDao.enqueue(tx, if (copy) PrintJobKind.REPRINT else PrintJobKind.RECEIPT, saleId, 1, now)
            if (copy) AuditDao.log(tx, AuditAction.REPRINT, staffId, now, Entity.SALE, saleId)
        }
        graph.printer.wake()
    }

    /** Opens the cash drawer without a sale (permission + audit). */
    suspend fun openDrawer() {
        if (!graph.permissions.allowed(Perm.OPEN_DRAWER)) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        val staffId = graph.staff.staffId
        graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, null, 1, now)
            AuditDao.log(tx, AuditAction.DRAWER_OPEN, staffId, now)
        }
        graph.printer.wake()
    }

    companion object {
        fun source(l: SaleLineFull, done: RefundPart?): RefundSource = RefundSource(
            lineId = l.id, qty = l.qty, baseQty = l.baseQty, gross = l.gross, discount = l.discount,
            billDiscount = l.billDiscount, net = l.net, tax = l.tax, cost = l.cost, refunded = done,
        )

        /** Pure: the refund document for [parts] (positive amounts in, negative document out). */
        fun refundDraft(
            h: SaleHeader,
            parts: List<Pair<RefundPart, SaleLineFull>>,
            restock: Boolean,
            reason: String,
            method: PaymentMethod,
            cashStep: Long,
            staffId: Long?,
            now: Long,
        ): SaleDraft {
            val totals = Refunds.totals(parts.map { it.first }, h.pricesInclTax)
            var applied = -totals.due
            var rounding = 0L
            if (method.kind == PaymentKind.CASH && totals.due > 0L) {
                val s = Settlement.cashRefund(totals.due, cashStep)
                applied = s.applied
                rounding = s.rounding
            }
            return SaleDraft(
                kind = SaleKind.REFUND,
                refSaleId = h.id,
                staffId = staffId,
                customerId = h.customerId,
                openedAt = now,
                soldAt = now,
                pricesInclTax = h.pricesInclTax,
                subtotal = -totals.subtotal,
                discount = -totals.discount,
                tax = -totals.tax,
                rounding = rounding,
                total = applied,
                paid = applied,
                change = 0L,
                note = reason,
                lines = parts.map { (p, l) ->
                    SaleLineDraft(
                        productId = l.productId, name = l.name, qty = -p.qty, baseQty = -p.baseQty, unitPrice = l.unitPrice,
                        gross = -p.gross, discount = -p.discount, billDiscount = -p.billDiscount, net = -p.net, tax = -p.tax,
                        taxRateId = l.taxRateId, taxBp = l.taxBp, cost = -p.cost, barcode = l.barcode, unit = l.unit,
                        categoryId = l.categoryId, refLineId = l.id, trackStock = l.stockQty != 0L, restock = restock,
                    )
                },
                payments = listOf(PaymentDraft(method.id, method.kind, applied)),
            )
        }
    }
}
