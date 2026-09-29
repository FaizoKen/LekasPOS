package com.lekaspos.domain.sale

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CreditKind
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
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.sale.CommittedSale
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleHeader
import com.lekaspos.data.sale.SaleLineDraft
import com.lekaspos.data.sale.SaleLineFull
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.domain.Approval
import java.util.TimeZone

/** Why a sale action was refused. */
class ActionRefused(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        NOT_ALLOWED, NOT_FOUND, VOIDED, HAS_REFUNDS, NOT_A_SALE, NOTHING_TO_REFUND,

        /** Phase 4: shifts, customers and staff. */
        NEEDS_SHIFT, SHIFT_OPEN, NEEDS_CUSTOMER, OVER_CREDIT_LIMIT, HAS_BALANCE, LAST_OWNER, CREDIT_OFF, OWNER_PIN_FIRST,
        SEED_ROLE, ROLE_IN_USE, WRONG_PIN,
    }
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
    suspend fun refund(
        saleId: Long,
        picks: Map<Long, Long>,
        restock: Boolean,
        reason: String,
        method: PaymentMethod,
        approval: Approval? = null,
    ): CommittedSale {
        val actor = graph.permissions.actor(Perm.REFUND, approval)
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val staffId = actor.staffId
        graph.shifts.load()
        val shiftId = graph.shifts.currentId
        if (shiftId == null && store.shiftRequired) throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
        val tz = TimeZone.getDefault()
        val sale = graph.db().write(reserveIds = picks.size + 16L) { tx ->
            // Re-read inside the transaction: another refund may have been made meanwhile.
            val h = SaleQueries.header(tx.db, saleId) ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (h.kind != SaleKind.SALE) throw ActionRefused(ActionRefused.Reason.NOT_A_SALE)
            if (h.voided) throw ActionRefused(ActionRefused.Reason.VOIDED)
            if (method.kind == PaymentKind.CREDIT && h.customerId == null) throw ActionRefused(ActionRefused.Reason.NEEDS_CUSTOMER)
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
            val draft = refundDraft(h, parts, restock, reason, method, store.cashStep, staffId, now).copy(shiftId = shiftId)
            val committed = SaleDao.commit(tx, draft, tz)
            AuditDao.log(
                tx, AuditAction.REFUND, staffId, now, Entity.SALE, committed.id, draft.total, "${h.receiptNo}: $reason", actor.approvedBy,
            )
            val customerId = h.customerId
            if (method.kind == PaymentKind.CREDIT && customerId != null && draft.total != 0L) {
                // Refunded to the customer's account: a negative charge lowers what they owe.
                CustomerDao.insertCredit(tx, customerId, CreditKind.CHARGE, draft.total, committed.id, method.id, staffId, shiftId, reason, now)
            }
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

    /**
     * Voids a whole sale or refund (wrong transaction). Its cash goes back out of this shift's
     * drawer (which opens) and credit charges on a customer's account are reversed.
     */
    suspend fun void(saleId: Long, reason: String, approval: Approval? = null) {
        val actor = graph.permissions.actor(Perm.VOID, approval)
        val staffId = actor.staffId
        val device = graph.settings.device.value
        graph.shifts.load()
        val shiftId = graph.shifts.currentId
        graph.db().write(reserveIds = 16L) { tx ->
            val h = SaleQueries.header(tx.db, saleId) ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (h.voided) throw ActionRefused(ActionRefused.Reason.VOIDED)
            // Refunds of this sale must be voided first, or stock and totals would be reversed twice.
            if (h.kind == SaleKind.SALE && h.refunded != 0L) throw ActionRefused(ActionRefused.Reason.HAS_REFUNDS)
            val now = System.currentTimeMillis()
            SaleDao.void(tx, saleId, reason, staffId, actor.approvedBy, shiftId, now)
            AuditDao.log(tx, AuditAction.SALE_VOID, staffId, now, Entity.SALE, saleId, h.total, "${h.receiptNo}: $reason", actor.approvedBy)
            val payments = SaleQueries.payments(tx.db, saleId)
            val customerId = h.customerId
            if (customerId != null) {
                for (p in payments) {
                    if (p.kind == PaymentKind.CREDIT && p.amount != 0L) {
                        CustomerDao.insertCredit(tx, customerId, CreditKind.CHARGE, -p.amount, saleId, p.methodId, staffId, shiftId, "void: $reason", now)
                    }
                }
            }
            if (device.hasPrinter && device.drawerEnabled && payments.any { it.opensDrawer && it.amount != 0L }) {
                PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, saleId, 1, now)
            }
        }
        graph.printer.wake()
    }

    /** Prints a sale again: a copy (audited) or, right after the sale, its first receipt. */
    suspend fun print(saleId: Long, copy: Boolean, approval: Approval? = null) {
        val actor = if (copy) graph.permissions.actor(Perm.REPRINT, approval) else null
        val staffId = actor?.staffId ?: graph.staff.staffId
        graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            PrintJobDao.enqueue(tx, if (copy) PrintJobKind.REPRINT else PrintJobKind.RECEIPT, saleId, 1, now)
            if (copy) AuditDao.log(tx, AuditAction.REPRINT, staffId, now, Entity.SALE, saleId, approvedBy = actor?.approvedBy)
        }
        graph.printer.wake()
    }

    /** Opens the cash drawer without a sale (permission + audit). */
    suspend fun openDrawer(approval: Approval? = null) {
        val actor = graph.permissions.actor(Perm.OPEN_DRAWER, approval)
        val staffId = actor.staffId
        graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, null, 1, now)
            AuditDao.log(tx, AuditAction.DRAWER_OPEN, staffId, now, approvedBy = actor.approvedBy)
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
