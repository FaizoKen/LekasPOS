package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.cart.Cart
import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.money.Checked
import com.lekaspos.core.pricing.PricedCart
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleLineDraft
import com.lekaspos.data.stock.LowStockItem
import com.lekaspos.data.stock.StockDao
import com.lekaspos.domain.Actor
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.util.Log
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One tender of a payment, as settled by :core `Settlement`. */
data class Tender(
    val methodId: Long,
    val kind: Int,
    val name: String,
    val opensDrawer: Boolean,
    /** What this tender contributes to the sale total (cash: the rounded amount). */
    val applied: Long,
    val tendered: Long,
    val change: Long,
)

/**
 * Completing a sale: the sale, its lines and payments, stock, summaries, the outbox event, the
 * cash-drawer pulse and the receipt print job are written in ONE transaction, which also
 * deletes the bill. Printing happens afterwards (references/architecture.md §9).
 */
class CheckoutService(private val graph: AppGraph) {

    /** [lowStock]: products of this sale that are now at or below their alert level. */
    data class Done(
        val saleId: Long,
        val receiptNo: String,
        val total: Long,
        val change: Long,
        val receiptQueued: Boolean,
        val lowStock: List<LowStockItem> = emptyList(),
        /** The bill's customer and what they owe after this sale (customers & credit). */
        val customerName: String? = null,
        val customerBalance: Long? = null,
        /** What the customer handed over (all tenders), and when: shown with the change. */
        val received: Long = total,
        val at: Long = 0L,
    )

    /** Result of the last checkout until the selling screen acknowledges it (survives rotation). */
    sealed class Outcome {
        data class Completed(val done: Done) : Outcome()
        data class Failed(val error: String) : Outcome()

        /** Refused before anything was written (e.g. over the credit limit); the bill is unchanged. */
        data class Refused(val reason: ActionRefused.Reason) : Outcome()
    }

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome

    private val _last = MutableStateFlow<Done?>(null)

    /** The last sale of this session (this process): the empty bill shows its change and a reprint. */
    val last: StateFlow<Done?> = _last

    /**
     * Completes the sale in the app scope, so leaving or rotating the screen can never cancel a
     * sale half-way; the outcome is published in [outcome].
     */
    fun start(tenders: List<Tender>, rounding: Long, approvals: List<Approval> = emptyList()) {
        graph.appScope.launch(Dispatchers.Main.immediate) {
            _outcome.value = try {
                Outcome.Completed(complete(tenders, rounding, approvals))
            } catch (e: ActionRefused) {
                Outcome.Refused(e.reason)
            } catch (e: Exception) {
                Log.e("Checkout failed", e)
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun acknowledge() {
        _outcome.value = null
    }

    /**
     * [approvals]: managers' approvals for this payment (CREDIT_SALE, CREDIT_LIMIT). A credit
     * tender charges the bill's customer in the same transaction (D-039).
     */
    suspend fun complete(tenders: List<Tender>, rounding: Long, approvals: List<Approval> = emptyList()): Done {
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val staffId = graph.staff.staffId
        graph.shifts.load()
        val shiftId = graph.shifts.currentId
        if (shiftId == null && store.shiftRequired) throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
        val credit = tenders.filter { it.kind == PaymentKind.CREDIT && it.applied != 0L }
        if (credit.isNotEmpty()) {
            if (!store.creditEnabled) throw ActionRefused(ActionRefused.Reason.CREDIT_OFF)
            graph.permissions.actor(Perm.CREDIT_SALE, approvals.firstOrNull { Perm.has(it.perm, Perm.CREDIT_SALE) })
        }
        val limitApproval = approvals.firstOrNull { Perm.has(it.perm, Perm.CREDIT_LIMIT) }
        val tz = TimeZone.getDefault()
        val lines = graph.cart.state.value.cart.items.size
        val done = graph.cart.checkout(reserveIds = lines + tenders.size + 16L) { tx, st ->
            val now = System.currentTimeMillis()
            val draft = draft(st.cart, st.priced, tenders, rounding, staffId, st.openedAt.takeIf { it > 0L } ?: now, now, shiftId, st.customerId)
            var overLimit: Actor? = null
            var charge = 0L
            if (credit.isNotEmpty()) {
                val customer = st.customerId?.let { CustomerDao.get(tx.db, it) } ?: throw ActionRefused(ActionRefused.Reason.NEEDS_CUSTOMER)
                charge = credit.sumOf { it.applied }
                if (CreditMath.overLimit(CustomerDao.balance(tx.db, customer.id), charge, customer.creditLimit)) {
                    overLimit = graph.permissions.actorOrNull(Perm.CREDIT_LIMIT, limitApproval)
                        ?: throw ActionRefused(ActionRefused.Reason.OVER_CREDIT_LIMIT)
                }
            }
            val sale = SaleDao.commit(tx, draft, tz)
            val customerId = st.customerId
            if (customerId != null) {
                for (t in credit) CustomerDao.insertCredit(tx, customerId, CreditKind.CHARGE, t.applied, sale.id, t.methodId, staffId, shiftId, null, now)
                val limitActor = overLimit
                if (limitActor != null) {
                    AuditDao.log(tx, AuditAction.CREDIT_OVER_LIMIT, staffId, now, Entity.SALE, sale.id, charge, sale.receiptNo, limitActor.approvedBy)
                }
            }
            var queued = false
            if (device.hasPrinter) {
                if (device.drawerEnabled && tenders.any { it.opensDrawer && it.applied != 0L }) {
                    PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, sale.id, 1, now)
                }
                if (device.autoPrint) {
                    PrintJobDao.enqueue(tx, PrintJobKind.RECEIPT, sale.id, store.receiptCopies, now)
                    queued = true
                }
            }
            val low = StockDao.lowAmong(tx.db, draft.lines.mapNotNull { if (it.trackStock) it.productId else null })
            val customer = customerId?.let { CustomerDao.name(tx.db, it) }
            val owes = customerId?.let { CustomerDao.balance(tx.db, it) }
            Done(sale.id, sale.receiptNo, draft.total, draft.change, queued, low, customer, owes, tenders.sumOf { it.tendered }, now)
        }
        _last.value = done
        graph.printer.wake()
        graph.syncSoon()
        return done
    }

    companion object {
        /** The stored form of a priced bill. Pure: amounts come from the pricing engine and tenders. */
        fun draft(
            cart: Cart,
            priced: PricedCart,
            tenders: List<Tender>,
            rounding: Long,
            staffId: Long?,
            openedAt: Long,
            soldAt: Long,
            shiftId: Long? = null,
            customerId: Long? = null,
        ): SaleDraft {
            require(cart.items.size == priced.lines.size) { "bill changed while paying" }
            val total = Checked.add(priced.total, rounding)
            require(tenders.sumOf { it.applied } == total) { "payments do not settle the total" }
            return SaleDraft(
                shiftId = shiftId,
                staffId = staffId,
                customerId = customerId,
                openedAt = openedAt,
                soldAt = soldAt,
                pricesInclTax = priced.pricesIncludeTax,
                subtotal = priced.subtotal,
                discount = priced.discount,
                tax = priced.tax,
                rounding = rounding,
                total = total,
                paid = total,
                change = tenders.sumOf { it.change },
                lines = cart.items.mapIndexed { i, item ->
                    val pl = priced.lines[i]
                    val taxed = item.taxRateId != null && item.taxBp > 0
                    val promo = priced.promotions.getOrNull(i)
                    SaleLineDraft(
                        productId = item.productId, name = item.name, qty = item.qty, baseQty = item.baseQty,
                        unitPrice = item.unitPrice, gross = pl.gross, discount = pl.lineDiscount,
                        billDiscount = pl.billDiscount, net = pl.net, tax = pl.tax,
                        taxRateId = if (taxed) item.taxRateId else null, taxBp = if (taxed) item.taxBp else 0,
                        cost = item.cost, barcode = item.barcode, unit = item.unit, categoryId = item.categoryId,
                        priceOverridden = item.priceOverridden, trackStock = item.trackStock,
                        promoId = promo?.promotionId, promoName = promo?.name,
                    )
                },
                payments = tenders.map { PaymentDraft(it.methodId, it.kind, it.applied, it.tendered, it.change) },
            )
        }
    }
}
