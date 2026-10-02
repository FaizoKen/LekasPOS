package com.lekaspos.data.report

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.report.Period
import com.lekaspos.core.report.RangePlan
import com.lekaspos.data.db.longOrNull

/**
 * The product rows of a report period, read by [RangePlan] (D-058): whole years from
 * `sum_year_product`, whole months from `sum_month_product`, loose days from `sum_day_product`,
 * with the pieces outside the period taken off again. The SQL is built from the constant
 * fragments below for the plan's shape (values are always bound).
 */
object SummaryRange {

    /** Whether any day in [from, to) had sales: a probe on the per-day table's key. */
    const val HAS_SALES = "SELECT 1 FROM sum_day_product WHERE day >= ? AND day < ? LIMIT 1"

    fun plan(db: SQLiteDatabase, p: Period): List<RangePlan.Piece> =
        RangePlan.of(p) { a, b -> db.longOrNull(HAS_SALES, a, b) != null }

    /** Columns: product, its category in the summary row, and the four measures (signed). */
    class Rows(val sql: String, val args: Array<String?>)

    /** `product_id, category_id, qty, net_ex, tax, cost` of every piece (UNION ALL). */
    fun all(pieces: List<RangePlan.Piece>): Rows = rows(pieces, ALL_POS, ALL_NEG)

    /** `product_id, qty` of every piece (UNION ALL), for rankings by quantity. */
    fun qty(pieces: List<RangePlan.Piece>): Rows = rows(pieces, QTY_POS, QTY_NEG)

    private fun rows(pieces: List<RangePlan.Piece>, pos: String, neg: String): Rows {
        require(pieces.isNotEmpty()) { "a period has at least one piece" }
        val sql = StringBuilder()
        val args = ArrayList<String?>(pieces.size * 2)
        for ((i, p) in pieces.withIndex()) {
            if (i > 0) sql.append(" UNION ALL ")
            sql.append(if (p.sign < 0) neg else pos)
            when (p.kind) {
                RangePlan.Kind.DAYS -> {
                    sql.append(DAYS)
                    args.add(p.from.toString())
                    args.add(p.to.toString())
                }
                RangePlan.Kind.MONTH -> {
                    sql.append(MONTH)
                    args.add(p.key.toString())
                }
                RangePlan.Kind.YEAR -> {
                    sql.append(YEAR)
                    args.add(p.key.toString())
                }
            }
        }
        return Rows(sql.toString(), args.toTypedArray())
    }

    private const val ALL_POS = "SELECT product_id, category_id, qty AS qty, net_ex AS net_ex, tax AS tax, cost AS cost"
    private const val ALL_NEG = "SELECT product_id, category_id, -qty AS qty, -net_ex AS net_ex, -tax AS tax, -cost AS cost"
    private const val QTY_POS = "SELECT product_id, qty AS qty"
    private const val QTY_NEG = "SELECT product_id, -qty AS qty"
    private const val DAYS = " FROM sum_day_product WHERE day >= ? AND day < ?"
    private const val MONTH = " FROM sum_month_product WHERE month = ?"
    private const val YEAR = " FROM sum_year_product WHERE year = ?"

    /** Every kind of piece, added and taken off: what the plan checks look at. */
    val SAMPLE: List<RangePlan.Piece> = listOf(
        RangePlan.Piece(RangePlan.Kind.YEAR, 0L, 1L, 2026, 1),
        RangePlan.Piece(RangePlan.Kind.MONTH, 0L, 1L, 202509, 1),
        RangePlan.Piece(RangePlan.Kind.DAYS, 0L, 1L, 0, -1),
        RangePlan.Piece(RangePlan.Kind.DAYS, 0L, 1L, 0, 1),
    )
}
