package com.lekaspos.data.stock

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.CountSessionStatus
import com.lekaspos.core.model.Entity
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter

data class CountSession(
    val id: Long,
    val name: String,
    val status: Int,
    val categoryId: Long?,
    val startedAt: Long,
    val finishedAt: Long?,
    val staffId: Long?,
    val note: String?,
    val counted: Int,
) {
    val open: Boolean get() = status == CountSessionStatus.OPEN
}

/** One count of a session, with what the app expected and the cost at that moment. */
data class CountRow(
    val id: Long,
    val productId: Long,
    val name: String?,
    val unit: String?,
    val qty: Long,
    val expected: Long?,
    val unitCost: Long?,
    val at: Long,
    val hlc: Long,
)

/**
 * Stock counts (stock takes): LWW table `count_session` groups EVENT rows of `stock_count`.
 * Each count applies the moment it is entered (D-035), so selling can go on while counting.
 */
object CountSessionDao {
    private const val SESSION_COLS =
        "s.id, s.name, s.status, s.category_id, s.started_at, s.finished_at, s.staff_id, s.note, " +
            "(SELECT COUNT(*) FROM stock_count c WHERE c.session_id = s.id)"
    private const val LIST =
        "SELECT $SESSION_COLS FROM count_session s WHERE s.deleted = 0 ORDER BY s.started_at DESC LIMIT ?"

    fun list(db: SQLiteDatabase, limit: Int = 100): List<CountSession> = db.queryList(LIST, args(limit), ::session)

    fun get(db: SQLiteDatabase, id: Long): CountSession? =
        db.queryOne("SELECT $SESSION_COLS FROM count_session s WHERE s.id = ?", args(id), ::session)

    fun create(tx: Db.Tx, name: String, categoryId: Long?, staffId: Long?, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(
            tx, "count_session", Entity.COUNT_SESSION, id,
            linkedMapOf(
                "name" to name, "status" to CountSessionStatus.OPEN, "category_id" to categoryId, "started_at" to now,
                "finished_at" to null, "staff_id" to staffId, "note" to null,
            ),
            now,
        )
        return id
    }

    fun finish(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.update(
            tx, "count_session", Entity.COUNT_SESSION, id,
            linkedMapOf("status" to CountSessionStatus.FINISHED, "finished_at" to now), now,
        )
    }

    private const val COUNT_SELECT =
        "SELECT c.id, c.product_id, p.name, p.unit, c.qty, c.expected, c.unit_cost, c.at, c.hlc " +
            "FROM stock_count c LEFT JOIN product p ON p.id = c.product_id "
    private const val COUNTS_FIRST = COUNT_SELECT + "WHERE c.session_id = ? ORDER BY c.hlc DESC, c.id DESC LIMIT ?"
    private const val COUNTS_NEXT =
        COUNT_SELECT + "WHERE c.session_id = ? AND c.hlc <= ? AND (c.hlc < ? OR c.id < ?) ORDER BY c.hlc DESC, c.id DESC LIMIT ?"

    /** Counts of a session, newest first (keyset-paginated). */
    fun counts(db: SQLiteDatabase, sessionId: Long, after: CountRow?, limit: Int = 50): List<CountRow> =
        if (after == null) {
            db.queryList(COUNTS_FIRST, args(sessionId, limit), ::countRow)
        } else {
            db.queryList(COUNTS_NEXT, args(sessionId, after.hlc, after.hlc, after.id, limit), ::countRow)
        }

    /** Latest counted quantity per product in the session (the counting screen's ticks). */
    fun countedQty(db: SQLiteDatabase, sessionId: Long): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        db.queryList("SELECT product_id, qty FROM stock_count WHERE session_id = ? ORDER BY hlc, id", args(sessionId)) {
            it.getLong(0) to it.getLong(1)
        }.forEach { out[it.first] = it.second }
        return out
    }

    private fun session(c: Cursor) = CountSession(
        id = c.getLong(0), name = c.getString(1), status = c.getInt(2), categoryId = c.longOrNull(3), startedAt = c.getLong(4),
        finishedAt = c.longOrNull(5), staffId = c.longOrNull(6), note = c.stringOrNull(7), counted = c.getInt(8),
    )

    private fun countRow(c: Cursor) = CountRow(
        id = c.getLong(0), productId = c.getLong(1), name = c.stringOrNull(2), unit = c.stringOrNull(3), qty = c.getLong(4),
        expected = c.longOrNull(5), unitCost = c.longOrNull(6), at = c.getLong(7), hlc = c.getLong(8),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "count_sessions" to LIST,
        "session_counts_first" to COUNTS_FIRST,
        "session_counts_next" to COUNTS_NEXT,
    )
}
