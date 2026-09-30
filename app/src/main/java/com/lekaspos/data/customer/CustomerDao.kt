package com.lekaspos.data.customer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter
import com.lekaspos.data.sync.Outbox

data class Customer(
    val id: Long,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    /** Tax ID, for e-invoices. */
    val tin: String? = null,
    val note: String? = null,
    /** 0 = no limit. */
    val creditLimit: Long = 0L,
)

/** A row of the customer list: [balance] = what the customer owes. */
data class CustomerItem(val id: Long, val name: String, val nameKey: String, val phone: String?, val creditLimit: Long, val balance: Long)

/** One line of a customer's credit statement; [receiptNo] of the sale it belongs to, if any. */
data class CreditEntry(
    val id: Long,
    val kind: Int,
    val amount: Long,
    val saleId: Long?,
    val receiptNo: String?,
    val methodId: Long?,
    val staffId: Long?,
    val note: String?,
    val at: Long,
    val hlc: Long,
) {
    val delta: Long get() = CreditMath.delta(kind, amount)
}

/** LWW table `customer`, EVENT table `credit_entry` and DERIVED `customer_balance` (D-039). */
object CustomerDao {
    private const val COLUMNS = "id, name, phone, email, address, tin, note, credit_limit"
    private const val ITEM = "SELECT c.id, c.name, c.name_key, c.phone, c.credit_limit, IFNULL(b.balance, 0) " +
        "FROM customer c LEFT JOIN customer_balance b ON b.customer_id = c.id "
    private const val LIST_FIRST = ITEM +
        "WHERE c.deleted = 0 AND c.name_key >= ? AND c.name_key < ? ORDER BY c.name_key, c.id LIMIT ?"
    private const val LIST_NEXT = ITEM +
        "WHERE c.deleted = 0 AND c.name_key >= ? AND (c.name_key > ? OR c.id > ?) AND c.name_key < ? " +
        "ORDER BY c.name_key, c.id LIMIT ?"
    private const val BY_PHONE = ITEM + "WHERE c.deleted = 0 AND c.phone >= ? AND c.phone < ? ORDER BY c.phone LIMIT ?"

    /** Customers whose name starts with [prefix] (normalized), A–Z, keyset-paginated. */
    fun page(db: SQLiteDatabase, prefix: String, after: CustomerItem?, limit: Int = 50): List<CustomerItem> {
        val key = SearchText.key(prefix)
        val upper = SearchText.prefixUpperBound(key)
        return if (after == null) {
            db.queryList(LIST_FIRST, args(key, upper, limit), ::item)
        } else {
            // Keyset: from the last row onwards (its name, then larger ids of the same name), not from the prefix.
            db.queryList(LIST_NEXT, args(after.nameKey, after.nameKey, after.id, upper, limit), ::item)
        }
    }

    /** Customers whose phone number starts with the digits of [digits]. */
    fun byPhone(db: SQLiteDatabase, digits: String, limit: Int = 50): List<CustomerItem> {
        val p = normalizePhone(digits) ?: return emptyList()
        return db.queryList(BY_PHONE, args(p, SearchText.prefixUpperBound(p), limit), ::item)
    }

    fun get(db: SQLiteDatabase, id: Long): Customer? = db.queryOne("SELECT $COLUMNS FROM customer WHERE id = ?", args(id), ::row)

    fun name(db: SQLiteDatabase, id: Long): String? =
        db.queryOne("SELECT name FROM customer WHERE id = ?", args(id)) { it.getString(0) }

    fun balance(db: SQLiteDatabase, id: Long): Long = db.long("SELECT balance FROM customer_balance WHERE customer_id = ?", id)

    /** Customers who owe something, and how much in total. */
    fun debtors(db: SQLiteDatabase): Pair<Long, Long> =
        db.queryOne("SELECT COUNT(*), IFNULL(SUM(balance), 0) FROM customer_balance WHERE balance > 0", null) {
            it.getLong(0) to it.getLong(1)
        } ?: (0L to 0L)

    /** Phone numbers are stored as digits (and a leading +), so searching ignores spaces and dashes. */
    fun normalizePhone(text: String?): String? {
        if (text == null) return null
        val t = text.trim()
        val digits = t.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return if (t.startsWith("+")) "+$digits" else digits
    }

    fun fields(c: Customer): LinkedHashMap<String, Any?> = linkedMapOf(
        "name" to c.name,
        "name_key" to SearchText.key(c.name),
        "phone" to normalizePhone(c.phone),
        "email" to c.email,
        "address" to c.address,
        "tin" to c.tin,
        "note" to c.note,
        "credit_limit" to c.creditLimit,
    )

    fun insert(tx: Db.Tx, c: Customer, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(tx, "customer", Entity.CUSTOMER, id, fields(c), now)
        return id
    }

    fun update(tx: Db.Tx, before: Customer, after: Customer, now: Long) {
        val old = fields(before)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(after)) if (old[k] != v) changes[k] = v
        if (changes.isNotEmpty()) LwwWriter.update(tx, "customer", Entity.CUSTOMER, before.id, changes, now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "customer", Entity.CUSTOMER, id, now)
    }

    // ------------------------------------------------------------------ credit

    private val CREDIT_COLS = arrayOf("id", "customer_id", "kind", "amount", "sale_id", "method_id", "staff_id", "note", "at", "hlc", "shift_id")
    private const val INSERT_CREDIT =
        "INSERT INTO credit_entry(id, customer_id, kind, amount, sale_id, method_id, staff_id, note, at, hlc, shift_id) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?)"
    private const val BALANCE_UPDATE = "UPDATE customer_balance SET balance = balance + ? WHERE customer_id = ?"
    private const val BALANCE_INSERT = "INSERT INTO customer_balance(balance, customer_id) VALUES(?, ?)"

    /** Appends a credit entry and moves the customer's balance in the same transaction. */
    fun insertCredit(
        tx: Db.Tx,
        customerId: Long,
        kind: Int,
        amount: Long,
        saleId: Long?,
        methodId: Long?,
        staffId: Long?,
        shiftId: Long?,
        note: String?,
        at: Long,
    ): Long {
        val delta = CreditMath.delta(kind, amount)
        val id = tx.nextId()
        val hlc = tx.hlcNow()
        val values = arrayOf<Any?>(id, customerId, kind, amount, saleId, methodId, staffId, note, at, hlc, shiftId)
        tx.insert(INSERT_CREDIT, *values)
        val bal = arrayOf<Any?>(delta, customerId)
        tx.updateOrInsert(BALANCE_UPDATE, bal, BALANCE_INSERT, bal)
        if (tx.syncEnabled) {
            Outbox.append(tx, Entity.CREDIT, EventOp.INSERT, id, hlc, Outbox.json { w -> Outbox.writeRow(w, CREDIT_COLS, values) })
        }
        return id
    }

    /** Moves [customerId]'s balance by [delta] (a credit entry arriving from another till). */
    fun applyBalance(tx: Db.Tx, customerId: Long, delta: Long) {
        val bal = arrayOf<Any?>(delta, customerId)
        tx.updateOrInsert(BALANCE_UPDATE, bal, BALANCE_INSERT, bal)
    }

    private const val ENTRY = "SELECT c.id, c.kind, c.amount, c.sale_id, s.receipt_no, c.method_id, c.staff_id, c.note, c.at, c.hlc " +
        "FROM credit_entry c LEFT JOIN sale s ON s.id = c.sale_id "
    private const val STATEMENT_FIRST = ENTRY + "WHERE c.customer_id = ? ORDER BY c.hlc DESC, c.id DESC LIMIT ?"
    private const val STATEMENT_NEXT = ENTRY +
        "WHERE c.customer_id = ? AND c.hlc <= ? AND (c.hlc < ? OR c.id < ?) ORDER BY c.hlc DESC, c.id DESC LIMIT ?"

    /** A customer's credit entries, newest first, keyset-paginated. */
    fun statement(db: SQLiteDatabase, customerId: Long, after: CreditEntry?, limit: Int = 50): List<CreditEntry> =
        if (after == null) {
            db.queryList(STATEMENT_FIRST, args(customerId, limit), ::entry)
        } else {
            db.queryList(STATEMENT_NEXT, args(customerId, after.hlc, after.hlc, after.id, limit), ::entry)
        }

    const val BALANCES =
        "SELECT customer_id, SUM(CASE kind WHEN ${CreditKind.PAYMENT} THEN -amount ELSE amount END) FROM credit_entry GROUP BY customer_id"
    const val REBUILD_BALANCES = "INSERT INTO customer_balance(customer_id, balance) $BALANCES"

    private fun item(c: Cursor) = CustomerItem(c.getLong(0), c.getString(1), c.getString(2), c.stringOrNull(3), c.getLong(4), c.getLong(5))

    private fun row(c: Cursor) = Customer(
        c.getLong(0), c.getString(1), c.stringOrNull(2), c.stringOrNull(3), c.stringOrNull(4), c.stringOrNull(5),
        c.stringOrNull(6), c.getLong(7),
    )

    private fun entry(c: Cursor) = CreditEntry(
        c.getLong(0), c.getInt(1), c.getLong(2), c.longOrNull(3), c.stringOrNull(4), c.longOrNull(5), c.longOrNull(6),
        c.stringOrNull(7), c.getLong(8), c.getLong(9),
    )

    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "customer_first" to LIST_FIRST,
        "customer_next" to LIST_NEXT,
        "customer_phone" to BY_PHONE,
        "statement_first" to STATEMENT_FIRST,
        "statement_next" to STATEMENT_NEXT,
    )
}
