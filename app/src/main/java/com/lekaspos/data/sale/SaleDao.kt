package com.lekaspos.data.sale

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.time.Days
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.sync.Outbox
import java.util.TimeZone

/**
 * Sales and refunds: the complete-sale transaction and its reversal (void), plus the paged
 * history queries. Everything a sale touches — lines, payments, stock, summaries, sync
 * outbox, receipt sequence — is written in the caller's single transaction.
 */
object SaleDao {

    // Column lists are shared with the bulk perf-data generator (same SQL, no drift).
    internal val SALE_COLS = arrayOf(
        "id", "kind", "receipt_no", "device_no", "doc_seq", "ref_sale_id", "shift_id", "staff_id",
        "customer_id", "opened_at", "sold_at", "day", "line_count", "subtotal", "discount", "tax",
        "rounding", "total", "paid", "change_due", "cost", "prices_incl_tax", "status", "refunded",
        "note", "hlc",
    )
    internal val LINE_COLS = arrayOf(
        "id", "sale_id", "line_no", "product_id", "ref_line_id", "name", "barcode", "unit", "category_id",
        "qty", "base_qty", "unit_price", "gross", "discount", "bill_discount", "net", "tax_rate_id",
        "tax_bp", "tax", "cost", "price_overridden", "stock_qty", "hlc",
    )
    internal val PAY_COLS = arrayOf(
        "id", "sale_id", "method_id", "kind", "amount", "tendered", "change_given", "ref", "shift_id", "paid_at",
    )
    internal val VOID_COLS = arrayOf("id", "sale_id", "reason", "staff_id", "approved_by", "shift_id", "at", "hlc")

    private fun insertSql(table: String, cols: Array<String>) =
        "INSERT INTO $table(${cols.joinToString(", ")}) VALUES(${cols.joinToString(",") { "?" }})"

    internal val INSERT_SALE = insertSql("sale", SALE_COLS)
    internal val INSERT_LINE = insertSql("sale_line", LINE_COLS)
    internal val INSERT_PAY = insertSql("payment", PAY_COLS)
    internal val INSERT_VOID = insertSql("sale_void", VOID_COLS)

    /** Stores a finished sale or refund. Must run inside Db.write. */
    fun commit(tx: Db.Tx, d: SaleDraft, tz: TimeZone): CommittedSale {
        require(d.lines.isNotEmpty()) { "a sale needs at least one line" }
        require(d.payments.sumOf { it.amount } == d.total) { "payments must add up to the total" }
        require(d.kind == SaleKind.SALE || d.refSaleId != null) { "a refund must reference its sale" }

        val id = tx.nextId()
        val hlc = tx.hlcNow()
        val docSeq = Meta.increment(tx.db, Meta.docSeqKey(d.kind))
        val receiptNo = ReceiptNumbers.format(ReceiptNumbers.prefix(tx), d.kind, docSeq)
        val day = Days.epochDay(d.soldAt, tz)
        val cost = d.lines.sumOf { it.cost }

        val saleValues = arrayOf<Any?>(
            id, d.kind, receiptNo, tx.deviceNo, docSeq, d.refSaleId, d.shiftId, d.staffId, d.customerId,
            d.openedAt, d.soldAt, day, d.lines.size, d.subtotal, d.discount, d.tax, d.rounding, d.total,
            d.paid, d.change, cost, d.pricesInclTax, SaleStatus.COMPLETED, 0L, d.note, hlc,
        )
        tx.insert(INSERT_SALE, *saleValues)

        val lineValues = ArrayList<Array<Any?>>(d.lines.size)
        val stock = LinkedHashMap<Long, Long>()
        val lineSums = ArrayList<Summaries.LineSum>(d.lines.size)
        for ((i, l) in d.lines.withIndex()) {
            val stockQty = if (l.trackStock && l.restock && l.productId != null) -l.baseQty else 0L
            val v = arrayOf<Any?>(
                tx.nextId(), id, i + 1, l.productId, l.refLineId, l.name, l.barcode, l.unit, l.categoryId,
                l.qty, l.baseQty, l.unitPrice, l.gross, l.discount, l.billDiscount, l.net, l.taxRateId,
                l.taxBp, l.tax, l.cost, l.priceOverridden, stockQty, hlc,
            )
            tx.insert(INSERT_LINE, *v)
            lineValues.add(v)
            val pid = l.productId
            if (stockQty != 0L && pid != null) stock[pid] = (stock[pid] ?: 0L) + stockQty
            val netEx = if (d.pricesInclTax) l.net - l.tax else l.net
            lineSums.add(Summaries.LineSum(l.productId, l.categoryId, l.baseQty, netEx, l.tax, l.cost))
        }

        val payValues = ArrayList<Array<Any?>>(d.payments.size)
        for (p in d.payments) {
            val v = arrayOf<Any?>(tx.nextId(), id, p.methodId, p.kind, p.amount, p.tendered, p.change, p.ref, d.shiftId, d.soldAt)
            tx.insert(INSERT_PAY, *v)
            payValues.add(v)
        }

        for ((productId, delta) in stock) StockDao.applyDelta(tx, productId, delta, hlc, tx.deviceNo)

        if (d.kind == SaleKind.REFUND) {
            // refund totals are negative; `refunded` on the original sale is a positive amount
            tx.update("UPDATE sale SET refunded = refunded + ? WHERE id = ?", -d.total, d.refSaleId)
        }

        Summaries.apply(
            tx,
            Summaries.Input(
                day, d.kind, d.staffId, d.subtotal, d.discount, d.tax, d.rounding, d.total, cost, lineSums,
                d.payments.map { Summaries.PaySum(it.methodId, it.kind, it.amount) },
            ),
            sign = +1,
        )

        if (tx.syncEnabled) {
            val payload = Outbox.json { w ->
                w.beginObject()
                w.name("sale")
                Outbox.writeRow(w, SALE_COLS, saleValues)
                w.name("lines").beginArray()
                for (v in lineValues) Outbox.writeRow(w, LINE_COLS, v)
                w.endArray()
                w.name("pays").beginArray()
                for (v in payValues) Outbox.writeRow(w, PAY_COLS, v)
                w.endArray()
                w.endObject()
            }
            Outbox.append(tx, Entity.SALE, EventOp.INSERT, id, hlc, payload)
        }
        return CommittedSale(id, receiptNo, docSeq, hlc, day)
    }

    /**
     * Voids a completed sale or refund: status → voided, stock effects reversed (only those that
     * came after the product's last count), summaries reversed. Returns false if already voided.
     */
    fun void(
        tx: Db.Tx,
        saleId: Long,
        reason: String,
        staffId: Long?,
        approvedBy: Long?,
        shiftId: Long?,
        at: Long,
    ): Boolean {
        val db = tx.db
        val h = db.queryOne(
            "SELECT status, kind, day, staff_id, subtotal, discount, tax, rounding, total, cost, " +
                "prices_incl_tax, ref_sale_id FROM sale WHERE id = ?",
            args(saleId),
        ) { c ->
            longArrayOf(
                c.getLong(0), c.getLong(1), c.getLong(2), c.longOrNull(3) ?: -1L, c.getLong(4), c.getLong(5),
                c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9), c.getLong(10), c.longOrNull(11) ?: -1L,
            )
        } ?: throw IllegalArgumentException("no sale $saleId")
        if (h[0].toInt() == SaleStatus.VOIDED) return false
        val kind = h[1].toInt()
        val inclTax = h[10] != 0L

        val voidId = tx.nextId()
        val hlc = tx.hlcNow()
        val voidValues = arrayOf<Any?>(voidId, saleId, reason, staffId, approvedBy, shiftId, at, hlc)
        tx.insert(INSERT_VOID, *voidValues)
        tx.update("UPDATE sale SET status = ? WHERE id = ?", SaleStatus.VOIDED, saleId)

        class VoidLine(val id: Long, val productId: Long?, val stockQty: Long, val hlc: Long, val sum: Summaries.LineSum)
        val lines = db.queryList(
            "SELECT id, product_id, category_id, base_qty, net, tax, cost, stock_qty, hlc FROM sale_line WHERE sale_id = ?",
            args(saleId),
        ) { c ->
            val net = c.getLong(4)
            val tax = c.getLong(5)
            VoidLine(
                id = c.getLong(0),
                productId = c.longOrNull(1),
                stockQty = c.getLong(7),
                hlc = c.getLong(8),
                sum = Summaries.LineSum(c.longOrNull(1), c.longOrNull(2), c.getLong(3), if (inclTax) net - tax else net, tax, c.getLong(6)),
            )
        }
        for (l in lines) {
            // reverse with the line's own version: only undone if it was applied after the last count
            if (l.productId != null && l.stockQty != 0L) {
                StockDao.applyDelta(tx, l.productId, -l.stockQty, l.hlc, Ids.deviceOf(l.id))
            }
        }
        val lineSums = lines.map { it.sum }
        val pays = db.queryList("SELECT method_id, kind, amount FROM payment WHERE sale_id = ?", args(saleId)) { c ->
            Summaries.PaySum(c.getLong(0), c.getInt(1), c.getLong(2))
        }
        if (kind == SaleKind.REFUND && h[11] >= 0L) {
            tx.update("UPDATE sale SET refunded = refunded + ? WHERE id = ?", h[8], h[11])
        }
        Summaries.apply(
            tx,
            Summaries.Input(
                h[2], kind, if (h[3] >= 0L) h[3] else null, h[4], h[5], h[6], h[7], h[8], h[9], lineSums, pays,
            ),
            sign = -1,
            voided = true,
        )
        if (tx.syncEnabled) {
            val payload = Outbox.json { w -> Outbox.writeRow(w, VOID_COLS, voidValues) }
            Outbox.append(tx, Entity.SALE_VOID, EventOp.INSERT, voidId, hlc, payload)
        }
        return true
    }

    // ------------------------------------------------------------------ queries

    private const val HISTORY_FIRST =
        "SELECT id, kind, receipt_no, sold_at, total, status, line_count FROM sale " +
            "ORDER BY sold_at DESC, id DESC LIMIT ?"

    // Keyset page: `sold_at <= ?` gives the index range, the OR resolves ties on sold_at.
    private const val HISTORY_NEXT =
        "SELECT id, kind, receipt_no, sold_at, total, status, line_count FROM sale " +
            "WHERE sold_at <= ? AND (sold_at < ? OR id < ?) ORDER BY sold_at DESC, id DESC LIMIT ?"

    private const val BY_RECEIPT =
        "SELECT id, kind, receipt_no, sold_at, total, status, line_count FROM sale " +
            "WHERE receipt_no = ? ORDER BY sold_at DESC LIMIT 1"

    private const val PRODUCT_HISTORY =
        "SELECT l.id, l.sale_id, l.hlc, s.receipt_no, s.sold_at, l.qty, l.net, s.status " +
            "FROM sale_line l JOIN sale s ON s.id = l.sale_id " +
            "WHERE l.product_id = ? AND l.hlc <= ? AND (l.hlc < ? OR l.id < ?) " +
            "ORDER BY l.hlc DESC, l.id DESC LIMIT ?"

    /** Newest sales first; pass the last row of the previous page as [after]. */
    fun history(db: SQLiteDatabase, after: SaleRow?, limit: Int = 50): List<SaleRow> =
        if (after == null) {
            db.queryList(HISTORY_FIRST, args(limit), ::saleRow)
        } else {
            db.queryList(HISTORY_NEXT, args(after.soldAt, after.soldAt, after.id, limit), ::saleRow)
        }

    fun byReceipt(db: SQLiteDatabase, receiptNo: String): SaleRow? = db.queryOne(BY_RECEIPT, args(receiptNo), ::saleRow)

    private fun saleRow(c: android.database.Cursor) = SaleRow(
        id = c.getLong(0),
        kind = c.getInt(1),
        receiptNo = c.getString(2),
        soldAt = c.getLong(3),
        total = c.getLong(4),
        status = c.getInt(5),
        lineCount = c.getInt(6),
    )

    fun lines(db: SQLiteDatabase, saleId: Long): List<SaleLineRow> = db.queryList(
        "SELECT id, sale_id, line_no, product_id, name, qty, unit_price, gross, net, tax FROM sale_line " +
            "WHERE sale_id = ? ORDER BY line_no",
        args(saleId),
    ) { c ->
        SaleLineRow(
            c.getLong(0), c.getLong(1), c.getInt(2), c.longOrNull(3), c.getString(4), c.getLong(5),
            c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9),
        )
    }

    /** Most recent lines of one product; pass the last row of the previous page as [after]. */
    fun productHistory(db: SQLiteDatabase, productId: Long, after: ProductSaleRow?, limit: Int = 50): List<ProductSaleRow> {
        val hlc = after?.hlc ?: Long.MAX_VALUE
        val id = after?.lineId ?: Long.MAX_VALUE
        return db.queryList(PRODUCT_HISTORY, args(productId, hlc, hlc, id, limit)) { c ->
            ProductSaleRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getInt(7))
        }
    }

    fun count(db: SQLiteDatabase): Long = db.long("SELECT COUNT(*) FROM sale")

    fun isVoided(db: SQLiteDatabase, saleId: Long): Boolean =
        db.queryOne("SELECT status FROM sale WHERE id = ?", args(saleId)) { it.getInt(0) == SaleStatus.VOIDED } ?: false

    fun refunded(db: SQLiteDatabase, saleId: Long): Long = db.long("SELECT refunded FROM sale WHERE id = ?", saleId)

    fun hasOutboxRows(db: SQLiteDatabase): Boolean = db.queryOne("SELECT 1 FROM outbox LIMIT 1") { it.bool(0) } ?: false

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "history_first" to HISTORY_FIRST,
        "history_next" to HISTORY_NEXT,
        "receipt_lookup" to BY_RECEIPT,
        "product_history" to PRODUCT_HISTORY,
    )
}
