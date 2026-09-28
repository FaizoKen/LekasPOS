package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.cart.Cart
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.money.Checked
import com.lekaspos.core.pricing.PricedCart
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleLineDraft
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

    data class Done(val saleId: Long, val receiptNo: String, val total: Long, val change: Long, val receiptQueued: Boolean)

    /** Result of the last checkout until the selling screen acknowledges it (survives rotation). */
    sealed class Outcome {
        data class Completed(val done: Done) : Outcome()
        data class Failed(val error: String) : Outcome()
    }

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome

    /**
     * Completes the sale in the app scope, so leaving or rotating the screen can never cancel a
     * sale half-way; the outcome is published in [outcome].
     */
    fun start(tenders: List<Tender>, rounding: Long) {
        graph.appScope.launch(Dispatchers.Main.immediate) {
            _outcome.value = try {
                Outcome.Completed(complete(tenders, rounding))
            } catch (e: Exception) {
                Log.e("Checkout failed", e)
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun acknowledge() {
        _outcome.value = null
    }

    suspend fun complete(tenders: List<Tender>, rounding: Long): Done {
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val staffId = graph.staff.staffId
        val tz = TimeZone.getDefault()
        val lines = graph.cart.state.value.cart.items.size
        val done = graph.cart.checkout(reserveIds = lines + tenders.size + 16L) { tx, st ->
            val now = System.currentTimeMillis()
            val draft = draft(st.cart, st.priced, tenders, rounding, staffId, st.openedAt.takeIf { it > 0L } ?: now, now)
            val sale = SaleDao.commit(tx, draft, tz)
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
            Done(sale.id, sale.receiptNo, draft.total, draft.change, queued)
        }
        graph.printer.wake()
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
        ): SaleDraft {
            require(cart.items.size == priced.lines.size) { "bill changed while paying" }
            val total = Checked.add(priced.total, rounding)
            require(tenders.sumOf { it.applied } == total) { "payments do not settle the total" }
            return SaleDraft(
                staffId = staffId,
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
                    SaleLineDraft(
                        productId = item.productId, name = item.name, qty = item.qty, baseQty = item.baseQty,
                        unitPrice = item.unitPrice, gross = pl.gross, discount = pl.lineDiscount,
                        billDiscount = pl.billDiscount, net = pl.net, tax = pl.tax,
                        taxRateId = if (taxed) item.taxRateId else null, taxBp = if (taxed) item.taxBp else 0,
                        cost = item.cost, barcode = item.barcode, unit = item.unit, categoryId = item.categoryId,
                        priceOverridden = item.priceOverridden, trackStock = item.trackStock,
                    )
                },
                payments = tenders.map { PaymentDraft(it.methodId, it.kind, it.applied, it.tendered, it.change) },
            )
        }
    }
}
