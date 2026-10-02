package com.lekaspos.domain.customer

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.customer.CreditEntry
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.customer.CustomerItem
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused

/**
 * Customers and "pay later" credit (D-039): the customer list, repayments into the drawer
 * and balance adjustments. Credit sales themselves are charged by the checkout.
 */
class CustomerService(private val graph: AppGraph) {

    /** A statement line with the balance right after it. */
    data class StatementRow(val entry: CreditEntry, val balanceAfter: Long)

    suspend fun page(query: String, after: CustomerItem?): List<CustomerItem> = graph.db().read { r ->
        val q = query.trim()
        if (q.isNotEmpty() && q.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
            if (after == null) CustomerDao.byPhone(r, q) else emptyList()
        } else {
            CustomerDao.page(r, q, after)
        }
    }

    /** A limit as the audit log shows it: an amount, or "none" for 0 (no limit). */
    private fun limitText(v: Long): String = if (v == 0L) "none" else MoneyFormat.plain(v, graph.settings.store.value.currency.decimals)

    suspend fun get(id: Long): Pair<Customer, Long>? = graph.db().read { r ->
        CustomerDao.get(r, id)?.let { it to CustomerDao.balance(r, id) }
    }

    /**
     * Adds or edits a customer. Setting or changing the credit limit needs CREDIT_LIMIT (the role,
     * or a manager's [limitApproval]) and is audited: 0 means "no limit", so whoever may edit a
     * customer must not be able to lift the limit that stops their own credit sales.
     */
    suspend fun save(before: Customer?, after: Customer, approval: Approval? = null, limitApproval: Approval? = null): Customer {
        val actor = graph.permissions.actor(Perm.CUSTOMERS, approval)
        require(after.name.isNotBlank()) { "name required" }
        require(after.creditLimit >= 0L) { "negative limit" }
        val oldLimit = before?.creditLimit ?: 0L
        val limitActor = if (after.creditLimit != oldLimit) graph.permissions.actor(Perm.CREDIT_LIMIT, limitApproval) else null
        return graph.db().write(reserveIds = 3L) { tx ->
            val now = System.currentTimeMillis()
            val saved = if (before == null) {
                after.copy(id = CustomerDao.insert(tx, after, now))
            } else {
                CustomerDao.update(tx, before, after, now)
                after
            }
            if (limitActor != null) {
                AuditDao.log(
                    tx, AuditAction.CREDIT_LIMIT_CHANGE, actor.staffId, now, Entity.CUSTOMER, saved.id, after.creditLimit,
                    "${after.name}: ${limitText(oldLimit)} → ${limitText(after.creditLimit)}", limitActor.approvedBy,
                )
            }
            saved
        }
    }

    /** Removes a customer who owes nothing (their history stays). */
    suspend fun delete(id: Long, approval: Approval? = null) {
        graph.permissions.actor(Perm.CUSTOMERS, approval)
        graph.db().write(reserveIds = 0L) { tx ->
            if (CustomerDao.balance(tx.db, id) != 0L) throw ActionRefused(ActionRefused.Reason.HAS_BALANCE)
            CustomerDao.delete(tx, id, System.currentTimeMillis())
        }
    }

    /**
     * The customer pays back [amount] with [method]; cash goes into the open shift's drawer
     * (which then opens). Returns the new balance.
     *
     * A repayment is never more than is owed (2026-10 review): more would be a balance adjustment
     * (CREDIT_LIMIT + audit), but any cashier could wipe a friend's debt with a "card" repayment
     * of any size, or leave them credit to spend past their limit. Every repayment is audited.
     * Cash is paid in steps of the smallest coin like a sale (MYR: 5 sen): the drawer gets the
     * rounded amount, and paying off the whole debt books the difference as cash rounding, so the
     * shift's expected cash and the balance (0.00) both come out right.
     */
    suspend fun receivePayment(
        customerId: Long,
        amount: Long,
        method: PaymentMethod,
        note: String?,
        approval: Approval? = null,
        /** The note of a cash-rounding entry, in the app's language. */
        roundingNote: String = "Cash rounding",
    ): Long {
        require(amount > 0L) { "amount must be positive" }
        require(method.kind != PaymentKind.CREDIT) { "credit cannot repay credit" }
        val actor = graph.permissions.actor(Perm.CUSTOMERS, approval)
        val store = graph.settings.store.value
        val shiftRequired = method.kind == PaymentKind.CASH && store.shiftRequired
        val device = graph.settings.device.value
        val balance = graph.db().write(reserveIds = 6L) { tx ->
            val now = System.currentTimeMillis()
            // The shift open now (one closed meanwhile never gets cash its count did not see).
            val shiftId = ShiftDao.current(tx.db, tx.deviceNo)?.id
            if (shiftId == null && shiftRequired) throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
            val customer = CustomerDao.get(tx.db, customerId) ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            val owed = CustomerDao.balance(tx.db, customerId)
            if (amount > owed) throw ActionRefused(ActionRefused.Reason.MORE_THAN_OWED)
            val paid = if (method.kind == PaymentKind.CASH) Settlement.cashDue(amount, store.currency.cashStep) else amount
            val settles = amount == owed || paid >= owed
            // Less than half the smallest coin, and the debt is not paid off: nothing was paid.
            if (paid == 0L && !settles) return@write owed
            val rounding = if (settles) paid - owed else 0L
            var entry = 0L
            if (paid != 0L) {
                entry = CustomerDao.insertCredit(tx, customerId, CreditKind.PAYMENT, paid, null, method.id, actor.staffId, shiftId, note, now)
            }
            if (rounding != 0L) {
                val id = CustomerDao.insertCredit(tx, customerId, CreditKind.ADJUST, rounding, null, null, actor.staffId, null, roundingNote, now)
                if (entry == 0L) entry = id
            }
            val detail = buildString {
                append(customer.name).append(": ").append(method.name)
                if (rounding != 0L) append(" (").append(roundingNote).append(' ').append(MoneyFormat.plain(rounding, store.currency.decimals)).append(')')
            }
            AuditDao.log(tx, AuditAction.CREDIT_PAYMENT, actor.staffId, now, Entity.CREDIT, entry, paid, detail, actor.approvedBy)
            if (paid != 0L && device.hasPrinter && device.drawerEnabled && method.opensDrawer) {
                PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, null, 1, now)
            }
            CustomerDao.balance(tx.db, customerId)
        }
        graph.printer.wake()
        return balance
    }

    /** Corrects a balance by [delta] (+ owes more, − owes less), with a reason (permission + audit). */
    suspend fun adjust(customerId: Long, delta: Long, note: String, approval: Approval? = null): Long {
        require(delta != 0L) { "nothing to adjust" }
        val actor = graph.permissions.actor(Perm.CREDIT_LIMIT, approval)
        return graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            if (CustomerDao.get(tx.db, customerId) == null) throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            val id = CustomerDao.insertCredit(tx, customerId, CreditKind.ADJUST, delta, null, null, actor.staffId, null, note, now)
            AuditDao.log(tx, AuditAction.CREDIT_ADJUST, actor.staffId, now, Entity.CREDIT, id, delta, note, actor.approvedBy)
            CustomerDao.balance(tx.db, customerId)
        }
    }

    /**
     * Statement lines, newest first. The balance after each line is walked back from the
     * current balance, so pass the last row of the previous page as [after].
     */
    suspend fun statement(customerId: Long, after: StatementRow?, limit: Int = 50): List<StatementRow> = graph.db().read { r ->
        var balance = if (after == null) CustomerDao.balance(r, customerId) else after.balanceAfter - after.entry.delta
        CustomerDao.statement(r, customerId, after?.entry, limit).map { e ->
            StatementRow(e, balance).also { balance -= e.delta }
        }
    }

    suspend fun debtors(): Pair<Long, Long> = graph.db().read { CustomerDao.debtors(it) }
}
