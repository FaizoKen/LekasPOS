package com.lekaspos.data.shift

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter
import com.lekaspos.data.sync.Outbox

/** A till's shift: from opening (with a float) to closing (with counted cash). */
data class Shift(
    val id: Long,
    val deviceNo: Int,
    val openedBy: Long,
    val openedAt: Long,
    val openingFloat: Long,
    val closedBy: Long? = null,
    val closedAt: Long? = null,
    val countedCash: Long? = null,
    val expectedCash: Long? = null,
    val note: String? = null,
) {
    val open: Boolean get() = closedAt == null
}

/** Sales or refunds of a shift (`kind` = SaleKind), voided ones included (see [ShiftTotals.tax]). */
data class DocSum(val kind: Int, val count: Int, val total: Long, val discount: Long, val tax: Long)

/** Payments of one method in a shift; [positive] = the part > 0 (money in, before refunds). */
data class MethodSum(val methodId: Long, val kind: Int, val count: Int, val amount: Long, val positive: Long)

/** Credit entries of a shift per kind and method; [methodKind] = PaymentKind of the method (repayments). */
data class CreditSum(val kind: Int, val methodId: Long?, val methodKind: Int?, val count: Int, val amount: Long)

/** Everything a shift report is computed from (D-038); every query is one index range on `shift_id`. */
data class ShiftTotals(
    val docs: List<DocSum>,
    val voidCount: Int,
    val voidTotal: Long,
    val payments: List<MethodSum>,
    /** Payments of documents voided during this shift (whenever they were made; credit: a sale's first void only). */
    val voidedPayments: List<MethodSum>,
    /** CashMoveKind → sum of amounts. */
    val cashMoves: Map<Int, Long>,
    val credit: List<CreditSum>,
    /** Discounts and tax of the documents voided during this shift (whenever they were made). */
    val voidDiscount: Long = 0L,
    val voidTax: Long = 0L,
) {
    /**
     * Discounts and tax of this shift: its documents less those voided during it. A void belongs
     * to the shift it happens in (D-038), like the voided payments, so an earlier shift's report
     * never changes.
     */
    val discount: Long get() = docs.sumOf { it.discount } - voidDiscount
    val tax: Long get() = docs.sumOf { it.tax } - voidTax
}

/** A cash in / cash out / drop (EVENT table `cash_movement`); [amount] is positive, [kind] gives the direction. */
data class CashMove(val id: Long, val shiftId: Long, val kind: Int, val amount: Long, val reason: String?, val staffId: Long?, val at: Long)

/** LWW table `shift` (only the till that opened a shift edits it) and EVENT table `cash_movement`. */
object ShiftDao {
    private const val COLUMNS = "id, device_no, opened_by, opened_at, opening_float, closed_by, closed_at, counted_cash, expected_cash, note"
    private const val CURRENT =
        "SELECT $COLUMNS FROM shift WHERE deleted = 0 AND device_no = ? AND closed_at IS NULL ORDER BY opened_at DESC LIMIT 1"
    private const val PAGE_FIRST = "SELECT $COLUMNS FROM shift WHERE deleted = 0 ORDER BY opened_at DESC, id DESC LIMIT ?"
    private const val PAGE_NEXT =
        "SELECT $COLUMNS FROM shift WHERE deleted = 0 AND opened_at <= ? AND (opened_at < ? OR id < ?) " +
            "ORDER BY opened_at DESC, id DESC LIMIT ?"

    /** The open shift of till [deviceNo], if any. */
    fun current(db: SQLiteDatabase, deviceNo: Int): Shift? = db.queryOne(CURRENT, args(deviceNo), ::row)

    fun get(db: SQLiteDatabase, id: Long): Shift? = db.queryOne("SELECT $COLUMNS FROM shift WHERE id = ?", args(id), ::row)

    /** Newest first, keyset-paginated. */
    fun page(db: SQLiteDatabase, after: Shift?, limit: Int = 50): List<Shift> =
        if (after == null) {
            db.queryList(PAGE_FIRST, args(limit), ::row)
        } else {
            db.queryList(PAGE_NEXT, args(after.openedAt, after.openedAt, after.id, limit), ::row)
        }

    fun open(tx: Db.Tx, openedBy: Long, openingFloat: Long, now: Long): Shift {
        val id = tx.nextId()
        val fields = linkedMapOf<String, Any?>(
            "device_no" to tx.deviceNo, "opened_by" to openedBy, "opened_at" to now, "opening_float" to openingFloat,
        )
        LwwWriter.insert(tx, "shift", Entity.SHIFT, id, fields, now)
        return Shift(id, tx.deviceNo, openedBy, now, openingFloat)
    }

    fun close(tx: Db.Tx, shift: Shift, closedBy: Long, counted: Long, expected: Long, note: String?, now: Long): Shift {
        val changes = linkedMapOf<String, Any?>(
            "closed_by" to closedBy, "closed_at" to now, "counted_cash" to counted, "expected_cash" to expected, "note" to note,
        )
        LwwWriter.update(tx, "shift", Entity.SHIFT, shift.id, changes, now)
        return shift.copy(closedBy = closedBy, closedAt = now, countedCash = counted, expectedCash = expected, note = note)
    }

    // ------------------------------------------------------------------ totals

    private const val DOCS =
        "SELECT kind, COUNT(*), SUM(total), SUM(discount), SUM(tax) FROM sale WHERE shift_id = ? GROUP BY kind"
    // Only the first void of a sale counts (by hlc, then till, then id) in the void count and totals,
    // and for customer credit: two tills that each voided the same sale while offline both took it
    // off (2026-10 review; the later void's credit reversal is cancelled, SaleDao.creditOnce). Cash,
    // card and e-wallet stay with every void: each till's own drawer really paid them back.
    // sale_void_sale is a full index, so SQLite 3.8 uses it inside this correlated subquery.
    private const val FIRST_VOID =
        "NOT EXISTS (SELECT 1 FROM sale_void w WHERE w.sale_id = v.sale_id AND w.hlc <= v.hlc " +
            "AND (w.hlc < v.hlc OR (w.id >> 41) < (v.id >> 41) OR ((w.id >> 41) = (v.id >> 41) AND w.id < v.id)))"
    private const val VOIDS =
        "SELECT COUNT(*), SUM(s.total), SUM(s.discount), SUM(s.tax) FROM sale_void v CROSS JOIN sale s ON s.id = v.sale_id " +
            "WHERE v.shift_id = ? AND $FIRST_VOID"
    private const val PAYMENTS =
        "SELECT method_id, kind, COUNT(*), SUM(amount), SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) " +
            "FROM payment WHERE shift_id = ? GROUP BY method_id, kind"
    private const val VOIDED_PAYMENTS =
        "SELECT p.method_id, p.kind, COUNT(*), SUM(p.amount), SUM(CASE WHEN p.amount > 0 THEN p.amount ELSE 0 END) " +
            "FROM sale_void v CROSS JOIN payment p ON p.sale_id = v.sale_id " +
            "WHERE v.shift_id = ? AND (p.kind <> ${PaymentKind.CREDIT} OR $FIRST_VOID) GROUP BY p.method_id, p.kind"
    private const val CASH_MOVES = "SELECT kind, SUM(amount) FROM cash_movement WHERE shift_id = ? GROUP BY kind"
    private const val CREDIT =
        "SELECT c.kind, c.method_id, m.kind, COUNT(*), SUM(c.amount) FROM credit_entry c " +
            "LEFT JOIN payment_method m ON m.id = c.method_id WHERE c.shift_id = ? GROUP BY c.kind, c.method_id"

    fun totals(db: SQLiteDatabase, shiftId: Long): ShiftTotals {
        val a = args(shiftId)
        val docs = db.queryList(DOCS, a) { DocSum(it.getInt(0), it.getInt(1), it.getLong(2), it.getLong(3), it.getLong(4)) }
        val voids = db.queryOne(VOIDS, a) { c ->
            longArrayOf(c.getLong(0), c.longOrNull(1) ?: 0L, c.longOrNull(2) ?: 0L, c.longOrNull(3) ?: 0L)
        } ?: LongArray(4)
        val methodSum = { c: Cursor -> MethodSum(c.getLong(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4)) }
        val payments = db.queryList(PAYMENTS, a, methodSum)
        val voided = db.queryList(VOIDED_PAYMENTS, a, methodSum)
        val moves = HashMap<Int, Long>()
        db.queryList(CASH_MOVES, a) { it.getInt(0) to it.getLong(1) }.forEach { moves[it.first] = it.second }
        val credit = db.queryList(CREDIT, a) {
            CreditSum(it.getInt(0), it.longOrNull(1), if (it.isNull(2)) null else it.getInt(2), it.getInt(3), it.getLong(4))
        }
        return ShiftTotals(
            docs, voids[0].toInt(), voids[1], payments, voided, moves, credit, voidDiscount = voids[2], voidTax = voids[3],
        )
    }

    // ------------------------------------------------------------------ cash movements

    private val MOVE_COLS = arrayOf("id", "shift_id", "kind", "amount", "reason", "staff_id", "at", "hlc")
    private const val INSERT_MOVE =
        "INSERT INTO cash_movement(id, shift_id, kind, amount, reason, staff_id, at, hlc) VALUES(?,?,?,?,?,?,?,?)"

    fun insertMove(tx: Db.Tx, shiftId: Long, kind: Int, amount: Long, reason: String?, staffId: Long?, at: Long): Long {
        require(amount > 0L) { "cash movement amount must be positive" }
        val id = tx.nextId()
        val hlc = tx.hlcNow()
        val values = arrayOf<Any?>(id, shiftId, kind, amount, reason, staffId, at, hlc)
        tx.insert(INSERT_MOVE, *values)
        if (tx.syncEnabled) {
            Outbox.append(tx, Entity.CASH_MOVE, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, MOVE_COLS, values) })
        }
        return id
    }

    /** A shift has a handful of movements: no paging needed. */
    fun moves(db: SQLiteDatabase, shiftId: Long): List<CashMove> = db.queryList(
        "SELECT id, shift_id, kind, amount, reason, staff_id, at FROM cash_movement WHERE shift_id = ? ORDER BY at, id",
        args(shiftId),
    ) { CashMove(it.getLong(0), it.getLong(1), it.getInt(2), it.getLong(3), it.stringOrNull(4), it.longOrNull(5), it.getLong(6)) }

    private fun row(c: Cursor) = Shift(
        id = c.getLong(0),
        deviceNo = c.getInt(1),
        openedBy = c.getLong(2),
        openedAt = c.getLong(3),
        openingFloat = c.getLong(4),
        closedBy = c.longOrNull(5),
        closedAt = c.longOrNull(6),
        countedCash = c.longOrNull(7),
        expectedCash = c.longOrNull(8),
        note = c.stringOrNull(9),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "shift_current" to CURRENT,
        "shift_first" to PAGE_FIRST,
        "shift_next" to PAGE_NEXT,
        "shift_docs" to DOCS,
        "shift_voids" to VOIDS,
        "shift_payments" to PAYMENTS,
        "shift_voided_payments" to VOIDED_PAYMENTS,
        "shift_cash_moves" to CASH_MOVES,
        "shift_credit" to CREDIT,
    )
}
