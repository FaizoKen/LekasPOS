package com.lekaspos.data.audit

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.staff.ActionTotal
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.Outbox

data class AuditRow(
    val id: Long,
    val action: Int,
    val staffId: Long?,
    val approvedBy: Long?,
    val entity: Int?,
    val entityId: Long?,
    val amount: Long?,
    val detail: String?,
    val at: Long,
)

/** EVENT table `audit_log`: who did which sensitive action, when (voids, refunds, overrides…). */
object AuditDao {
    private val COLS = arrayOf("id", "action", "staff_id", "approved_by", "entity", "entity_id", "amount", "detail", "at", "hlc")
    private const val INSERT =
        "INSERT INTO audit_log(id, action, staff_id, approved_by, entity, entity_id, amount, detail, at, hlc) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?)"

    fun log(
        tx: Db.Tx,
        action: Int,
        staffId: Long?,
        at: Long,
        entity: Int? = null,
        entityId: Long? = null,
        amount: Long? = null,
        detail: String? = null,
        approvedBy: Long? = null,
    ): Long {
        val id = tx.nextId()
        val hlc = tx.hlcNow()
        val values = arrayOf<Any?>(id, action, staffId, approvedBy, entity, entityId, amount, detail, at, hlc)
        tx.insert(INSERT, *values)
        if (tx.syncEnabled) {
            Outbox.append(tx, Entity.AUDIT, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, COLS, values) })
        }
        return id
    }

    private const val COLUMNS = "id, action, staff_id, approved_by, entity, entity_id, amount, detail, at"
    private const val RECENT_FIRST = "SELECT $COLUMNS FROM audit_log ORDER BY at DESC, id DESC LIMIT ?"
    private const val RECENT_NEXT =
        "SELECT $COLUMNS FROM audit_log WHERE at <= ? AND (at < ? OR id < ?) ORDER BY at DESC, id DESC LIMIT ?"

    /** Newest first, keyset-paginated: pass the last row of the previous page as [after]. */
    fun recent(db: SQLiteDatabase, after: AuditRow?, limit: Int = 50): List<AuditRow> =
        if (after == null) {
            db.queryList(RECENT_FIRST, args(limit), ::row)
        } else {
            db.queryList(RECENT_NEXT, args(after.at, after.at, after.id, limit), ::row)
        }

    private const val ACTION_FIRST = "SELECT $COLUMNS FROM audit_log WHERE action = ? ORDER BY at DESC, id DESC LIMIT ?"
    private const val ACTION_NEXT =
        "SELECT $COLUMNS FROM audit_log WHERE action = ? AND at <= ? AND (at < ? OR id < ?) ORDER BY at DESC, id DESC LIMIT ?"

    /** Entries of one [action] only, newest first. */
    fun byAction(db: SQLiteDatabase, action: Int, after: AuditRow?, limit: Int = 50): List<AuditRow> =
        if (after == null) {
            db.queryList(ACTION_FIRST, args(action, limit), ::row)
        } else {
            db.queryList(ACTION_NEXT, args(action, after.at, after.at, after.id, limit), ::row)
        }

    fun countByAction(db: SQLiteDatabase, action: Int): Long =
        db.long("SELECT COUNT(*) FROM audit_log WHERE action = ?", action)

    private const val BY_STAFF =
        "SELECT staff_id, action, COUNT(*), COALESCE(SUM(amount), 0) FROM audit_log WHERE at >= ? AND at < ? GROUP BY staff_id, action"

    /** The activity log from [fromMs] to [toMs] (exclusive) by person and action, for the staff check (D-067). */
    fun totalsByStaff(db: SQLiteDatabase, fromMs: Long, toMs: Long): Map<Long, List<ActionTotal>> {
        val out = HashMap<Long, MutableList<ActionTotal>>()
        db.queryList(BY_STAFF, args(fromMs, toMs)) { c ->
            if (!c.isNull(0)) out.getOrPut(c.getLong(0)) { ArrayList() }.add(ActionTotal(c.getInt(1), c.getLong(2), c.getLong(3)))
        }
        return out
    }

    private const val OF_TILL =
        "SELECT action, COUNT(*), COALESCE(SUM(amount), 0) FROM audit_log WHERE at >= ? AND at <= ? AND id >= ? AND id < ? GROUP BY action"

    /**
     * The activity log of till [deviceNo] from [fromMs] to [toMs] (inclusive) by action: a shift's checks.
     * A till's entries are the ids it made (`device_no shl 41`, references/database.md §5).
     */
    fun totalsOfTill(db: SQLiteDatabase, deviceNo: Int, fromMs: Long, toMs: Long): List<ActionTotal> {
        val first = deviceNo.toLong() shl 41
        return db.queryList(OF_TILL, args(fromMs, toMs, first, first + (1L shl 41))) { c -> ActionTotal(c.getInt(0), c.getLong(1), c.getLong(2)) }
    }

    private const val EXISTS = "SELECT COUNT(*) FROM audit_log WHERE action = ? AND staff_id = ? AND entity_id = ?"

    /** Is there an entry of [action] by [staffId] about [entityId]? (A rare action: its rows by the action index.) */
    fun exists(db: SQLiteDatabase, action: Int, staffId: Long, entityId: Long): Boolean = db.long(EXISTS, action, staffId, entityId) > 0L

    private const val EXISTS_DETAIL = "SELECT COUNT(*) FROM audit_log WHERE action = ? AND detail = ?"

    /** Is there an entry of [action] (on any till: the log is synced) with exactly [detail]? A rare action, by its index. */
    fun existsWithDetail(db: SQLiteDatabase, action: Int, detail: String): Boolean = db.long(EXISTS_DETAIL, action, detail) > 0L

    private fun row(c: Cursor) = AuditRow(
        id = c.getLong(0),
        action = c.getInt(1),
        staffId = c.longOrNull(2),
        approvedBy = c.longOrNull(3),
        entity = if (c.isNull(4)) null else c.getInt(4),
        entityId = c.longOrNull(5),
        amount = c.longOrNull(6),
        detail = c.stringOrNull(7),
        at = c.getLong(8),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "audit_first" to RECENT_FIRST,
        "audit_next" to RECENT_NEXT,
        "audit_action_first" to ACTION_FIRST,
        "audit_action_next" to ACTION_NEXT,
        "audit_exists" to EXISTS,
        "audit_exists_detail" to EXISTS_DETAIL,
        "audit_by_staff" to BY_STAFF,
        "audit_of_till" to OF_TILL,
    )
}
