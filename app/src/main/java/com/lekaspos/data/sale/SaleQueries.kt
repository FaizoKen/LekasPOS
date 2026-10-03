package com.lekaspos.data.sale

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.refund.RefundPart
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull

data class SaleHeader(
    val id: Long,
    val kind: Int,
    val receiptNo: String,
    val refSaleId: Long?,
    val staffId: Long?,
    val customerId: Long?,
    val openedAt: Long,
    val soldAt: Long,
    val lineCount: Int,
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val rounding: Long,
    val total: Long,
    val paid: Long,
    val change: Long,
    val pricesInclTax: Boolean,
    val status: Int,
    val refunded: Long,
    val note: String?,
) {
    val voided: Boolean get() = status == SaleStatus.VOIDED
}

data class SaleLineFull(
    val id: Long,
    val lineNo: Int,
    val productId: Long?,
    val refLineId: Long?,
    val name: String,
    val barcode: String?,
    val unit: String?,
    val categoryId: Long?,
    val qty: Long,
    val baseQty: Long,
    val unitPrice: Long,
    val gross: Long,
    val discount: Long,
    val billDiscount: Long,
    val net: Long,
    val taxRateId: Long?,
    val taxBp: Int,
    val tax: Long,
    val cost: Long,
    val priceOverridden: Boolean,
    val stockQty: Long,
    /** The promotion that gave this line its discount (name as it was when sold). */
    val promoName: String? = null,
    val promoId: Long? = null,
)

data class PaymentRow(
    val id: Long,
    val methodId: Long,
    val name: String?,
    val kind: Int,
    val amount: Long,
    val tendered: Long,
    val change: Long,
    val opensDrawer: Boolean,
)

/** Reads of one stored sale: receipt, sale detail screen, refunds. */
object SaleQueries {

    fun header(db: SQLiteDatabase, saleId: Long): SaleHeader? = db.queryOne(
        "SELECT id, kind, receipt_no, ref_sale_id, staff_id, customer_id, opened_at, sold_at, line_count, subtotal, " +
            "discount, tax, rounding, total, paid, change_due, prices_incl_tax, status, refunded, note FROM sale WHERE id = ?",
        args(saleId),
    ) { c ->
        SaleHeader(
            id = c.getLong(0), kind = c.getInt(1), receiptNo = c.getString(2), refSaleId = c.longOrNull(3),
            staffId = c.longOrNull(4), customerId = c.longOrNull(5), openedAt = c.getLong(6), soldAt = c.getLong(7),
            lineCount = c.getInt(8), subtotal = c.getLong(9), discount = c.getLong(10), tax = c.getLong(11),
            rounding = c.getLong(12), total = c.getLong(13), paid = c.getLong(14), change = c.getLong(15),
            pricesInclTax = c.bool(16), status = c.getInt(17), refunded = c.getLong(18), note = c.stringOrNull(19),
        )
    }

    fun lines(db: SQLiteDatabase, saleId: Long): List<SaleLineFull> = db.queryList(
        "SELECT id, line_no, product_id, ref_line_id, name, barcode, unit, category_id, qty, base_qty, unit_price, " +
            "gross, discount, bill_discount, net, tax_rate_id, tax_bp, tax, cost, price_overridden, stock_qty, promo_name, promo_id " +
            "FROM sale_line WHERE sale_id = ? ORDER BY line_no",
        args(saleId),
    ) { c ->
        SaleLineFull(
            id = c.getLong(0), lineNo = c.getInt(1), productId = c.longOrNull(2), refLineId = c.longOrNull(3),
            name = c.getString(4), barcode = c.stringOrNull(5), unit = c.stringOrNull(6), categoryId = c.longOrNull(7),
            qty = c.getLong(8), baseQty = c.getLong(9), unitPrice = c.getLong(10), gross = c.getLong(11),
            discount = c.getLong(12), billDiscount = c.getLong(13), net = c.getLong(14), taxRateId = c.longOrNull(15),
            taxBp = c.getInt(16), tax = c.getLong(17), cost = c.getLong(18), priceOverridden = c.bool(19),
            stockQty = c.getLong(20), promoName = c.stringOrNull(21), promoId = c.longOrNull(22),
        )
    }

    fun payments(db: SQLiteDatabase, saleId: Long): List<PaymentRow> = db.queryList(
        "SELECT p.id, p.method_id, m.name, p.kind, p.amount, p.tendered, p.change_given, COALESCE(m.opens_drawer, 0) " +
            "FROM payment p LEFT JOIN payment_method m ON m.id = p.method_id WHERE p.sale_id = ? ORDER BY p.id",
        args(saleId),
    ) { c ->
        PaymentRow(
            c.getLong(0), c.getLong(1), c.stringOrNull(2), c.getInt(3), c.getLong(4), c.getLong(5), c.getLong(6), c.bool(7),
        )
    }

    private const val REFUNDED_BY_LINE =
        "SELECT l.ref_line_id, -SUM(l.qty), -SUM(l.base_qty), -SUM(l.gross), -SUM(l.discount), -SUM(l.bill_discount), " +
            "-SUM(l.net), -SUM(l.tax), -SUM(l.cost) " +
            "FROM sale s CROSS JOIN sale_line l ON l.sale_id = s.id " +
            "WHERE s.ref_sale_id = ? AND s.ref_sale_id IS NOT NULL AND s.status = ${SaleStatus.COMPLETED} AND l.ref_line_id IS NOT NULL " +
            "GROUP BY l.ref_line_id"

    /** What earlier, non-voided refunds returned of each line of sale [saleId] (positive values). */
    fun refundedByLine(db: SQLiteDatabase, saleId: Long): Map<Long, RefundPart> {
        val out = HashMap<Long, RefundPart>()
        db.queryList(REFUNDED_BY_LINE, args(saleId)) { c ->
            RefundPart(
                lineId = c.getLong(0), qty = c.getLong(1), baseQty = c.getLong(2), gross = c.getLong(3),
                discount = c.getLong(4), billDiscount = c.getLong(5), net = c.getLong(6), tax = c.getLong(7),
                cost = c.getLong(8),
            )
        }.forEach { out[it.lineId] = it }
        return out
    }

    private const val REFUNDS_OF =
        "SELECT id, kind, receipt_no, sold_at, total, status, line_count FROM sale " +
            "WHERE ref_sale_id = ? AND ref_sale_id IS NOT NULL ORDER BY sold_at"

    /** Refund documents that reference sale [saleId]. */
    fun refundsOf(db: SQLiteDatabase, saleId: Long): List<SaleRow> = db.queryList(REFUNDS_OF, args(saleId)) { c ->
        SaleRow(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5), c.getInt(6))
    }

    fun receiptNo(db: SQLiteDatabase, saleId: Long): String? =
        db.queryOne("SELECT receipt_no FROM sale WHERE id = ?", args(saleId)) { it.getString(0) }

    /**
     * Which of [productIds] are sold by weight now: their returns are entered as a weight even when
     * the line was a whole kilo (2026-10 review). One primary-key lookup per line of the sale.
     */
    fun soldByWeight(db: SQLiteDatabase, productIds: Collection<Long>): Set<Long> {
        val out = HashSet<Long>()
        for (id in productIds) {
            if (db.long("SELECT sell_mode FROM product WHERE id = ?", id) == SellMode.WEIGHT.toLong()) out.add(id)
        }
        return out
    }

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "refunded_by_line" to REFUNDED_BY_LINE,
        "refunds_of" to REFUNDS_OF,
    )
}
