package com.lekaspos.data.sale

import com.lekaspos.core.model.SaleKind

/**
 * A finished bill ready to be committed. All amounts were computed by :core PricingEngine /
 * Settlement; the DAO stores them as given and never recalculates money.
 */
data class SaleDraft(
    val kind: Int = SaleKind.SALE,
    val refSaleId: Long? = null,
    val shiftId: Long? = null,
    val staffId: Long? = null,
    val customerId: Long? = null,
    val openedAt: Long,
    val soldAt: Long,
    val pricesInclTax: Boolean,
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val rounding: Long,
    val total: Long,
    val paid: Long,
    val change: Long,
    val note: String? = null,
    val lines: List<SaleLineDraft>,
    val payments: List<PaymentDraft>,
)

data class SaleLineDraft(
    val productId: Long?,
    val name: String,
    val qty: Long,
    /** Quantity in the product's base unit (packs × pack size, derived weight, …). */
    val baseQty: Long,
    val unitPrice: Long,
    val gross: Long,
    val discount: Long = 0L,
    val billDiscount: Long = 0L,
    val net: Long,
    val tax: Long = 0L,
    val taxRateId: Long? = null,
    val taxBp: Int = 0,
    /** Total cost of the line (unit cost × base qty), for profit reports. */
    val cost: Long = 0L,
    val barcode: String? = null,
    val unit: String? = null,
    val categoryId: Long? = null,
    val refLineId: Long? = null,
    val priceOverridden: Boolean = false,
    val trackStock: Boolean = true,
    /** Refund lines only: put the goods back into stock. */
    val restock: Boolean = true,
    /** The promotion that applied to this line (its saving is in [discount]), with its name at the time. */
    val promoId: Long? = null,
    val promoName: String? = null,
)

data class PaymentDraft(
    val methodId: Long,
    val kind: Int,
    val amount: Long,
    val tendered: Long = 0L,
    val change: Long = 0L,
    val ref: String? = null,
)

data class CommittedSale(val id: Long, val receiptNo: String, val docSeq: Long, val hlc: Long, val day: Long)

data class SaleRow(
    val id: Long,
    val kind: Int,
    val receiptNo: String,
    val soldAt: Long,
    val total: Long,
    val status: Int,
    val lineCount: Int,
)

data class SaleLineRow(
    val id: Long,
    val saleId: Long,
    val lineNo: Int,
    val productId: Long?,
    val name: String,
    val qty: Long,
    val unitPrice: Long,
    val gross: Long,
    val net: Long,
    val tax: Long,
)

data class ProductSaleRow(
    val lineId: Long,
    val saleId: Long,
    val hlc: Long,
    val receiptNo: String,
    val soldAt: Long,
    val qty: Long,
    val net: Long,
    val status: Int,
)
