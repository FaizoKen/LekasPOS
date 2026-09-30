package com.lekaspos.data.print

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.model.PrintJobStatus
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryOne

data class PrintJob(
    val id: Long,
    val kind: Int,
    val refId: Long?,
    val copies: Int,
    val attempts: Int,
    val createdAt: Long,
)

/**
 * LOCAL table `print_job`: the persistent print queue. Jobs are enqueued in the same
 * transaction as the sale they print, survive restarts and wait while the printer is offline.
 */
object PrintJobDao {
    private const val INSERT =
        "INSERT INTO print_job(kind, ref_id, copies, status, attempts, created_at, updated_at) VALUES(?, ?, ?, ?, 0, ?, ?)"

    fun enqueue(tx: Db.Tx, kind: Int, refId: Long?, copies: Int, now: Long): Long =
        tx.insert(INSERT, kind, refId, copies.coerceIn(1, 5), PrintJobStatus.PENDING, now, now)

    /** Was a receipt of sale [saleId] printed or is one still waiting? (Not one that expired or was cleared unprinted.) */
    fun hasReceipt(db: SQLiteDatabase, saleId: Long): Boolean = db.long(
        "SELECT COUNT(*) FROM print_job WHERE ref_id = ? AND kind IN (${PrintJobKind.RECEIPT}, ${PrintJobKind.REPRINT}) AND status != ?",
        saleId, PrintJobStatus.FAILED,
    ) > 0L

    /**
     * The next pending job: drawer pulses first (the cashier is waiting to give change, and a pulse
     * stuck behind a backlog of receipts would expire), then the oldest.
     */
    fun next(db: SQLiteDatabase): PrintJob? = db.queryOne(
        "SELECT id, kind, ref_id, copies, attempts, created_at FROM print_job WHERE status = ? " +
            "ORDER BY CASE WHEN kind = ? THEN 0 ELSE 1 END, id LIMIT 1",
        args(PrintJobStatus.PENDING, PrintJobKind.DRAWER),
    ) { c -> PrintJob(c.getLong(0), c.getInt(1), c.longOrNull(2), c.getInt(3), c.getInt(4), c.getLong(5)) }

    /**
     * Gives up on sale receipts still waiting since before [before] (the printer was off): printed
     * much later they would only flood out between newer sales. They can be printed again from the
     * sale. Reprints, shift reports and test pages were asked for on purpose and keep waiting.
     */
    fun expireReceipts(tx: Db.Tx, before: Long, now: Long): Int = tx.update(
        "UPDATE print_job SET status = ?, last_error = 'expired', updated_at = ? " +
            "WHERE status = ? AND kind = ? AND created_at < ?",
        PrintJobStatus.FAILED, now, PrintJobStatus.PENDING, PrintJobKind.RECEIPT, before,
    )

    fun pendingCount(db: SQLiteDatabase): Long =
        db.long("SELECT COUNT(*) FROM print_job WHERE status = ?", PrintJobStatus.PENDING)

    fun markDone(tx: Db.Tx, id: Long, now: Long) {
        tx.update("UPDATE print_job SET status = ?, updated_at = ? WHERE id = ?", PrintJobStatus.DONE, now, id)
    }

    /** A failed attempt: the job stays pending and is retried after reconnecting. */
    fun markAttempt(tx: Db.Tx, id: Long, error: String?, now: Long) {
        tx.update(
            "UPDATE print_job SET attempts = attempts + 1, last_error = ?, updated_at = ? WHERE id = ?",
            error?.take(200), now, id,
        )
    }

    /** A job that can never print (e.g. its sale is gone). */
    fun markFailed(tx: Db.Tx, id: Long, error: String?, now: Long) {
        tx.update(
            "UPDATE print_job SET status = ?, last_error = ?, updated_at = ? WHERE id = ?",
            PrintJobStatus.FAILED, error?.take(200), now, id,
        )
    }

    /** Drops everything still waiting (the user cleared the queue). */
    fun cancelPending(tx: Db.Tx, now: Long): Int = tx.update(
        "UPDATE print_job SET status = ?, last_error = 'cancelled', updated_at = ? WHERE status = ?",
        PrintJobStatus.FAILED, now, PrintJobStatus.PENDING,
    )

    /** Deletes finished jobs older than [before] (the queue is not a history). */
    fun purge(tx: Db.Tx, before: Long): Int =
        tx.update("DELETE FROM print_job WHERE status != ? AND updated_at < ?", PrintJobStatus.PENDING, before)
}
