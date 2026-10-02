package com.lekaspos.domain.shift

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CashMoveKind
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.money.Checked
import com.lekaspos.core.shift.MethodTotal
import com.lekaspos.core.shift.ShiftCash
import com.lekaspos.core.shift.ShiftReport
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.shift.Shift
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.data.shift.ShiftTotals
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shifts and cash management of this till (D-038): open with a float, cash in / out / drops,
 * close with the counted cash. Sales, refunds, voids and credit repayments made while a shift
 * is open carry its id, so the shift's expected cash is always recomputed from the events.
 */
class ShiftService(private val graph: AppGraph) {

    private val _current = MutableStateFlow<Shift?>(null)

    /** The open shift of this till, if any. */
    val current: StateFlow<Shift?> = _current

    private val loadLock = Mutex()

    @Volatile
    private var loaded = false

    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        val db = graph.db()
        _current.value = db.read { ShiftDao.current(it, db.deviceNo) }
        loaded = true
    }

    /** The open shift's id for a document being written now (null when none is open). */
    val currentId: Long? get() = _current.value?.id

    /** Opens a shift with [openingFloat] in the drawer (the drawer opens to put it in). */
    suspend fun open(openingFloat: Long): Shift {
        require(openingFloat >= 0L) { "negative float" }
        load()
        val staff = graph.staff.staffId
        val device = graph.settings.device.value
        // The shift in memory follows the commit even when the screen that asked is closing: a
        // shift opened in the database but not here refused every payment (no shift) and every
        // open (one is open) until the app restarted (2026-10 review). Likewise for close.
        return withContext(NonCancellable) {
            val shift = graph.db().write(reserveIds = 4L) { tx ->
                if (ShiftDao.current(tx.db, tx.deviceNo) != null) throw ActionRefused(ActionRefused.Reason.SHIFT_OPEN)
                val now = System.currentTimeMillis()
                val s = ShiftDao.open(tx, staff, openingFloat, now)
                AuditDao.log(tx, AuditAction.SHIFT_OPEN, staff, now, Entity.SHIFT, s.id, openingFloat)
                if (device.hasPrinter && device.drawerEnabled && openingFloat > 0L) PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, null, 1, now)
                s
            }
            _current.value = shift
            graph.printer.wake()
            shift
        }
    }

    /** Cash in, cash out or a drop ([kind] = CashMoveKind) during the open shift (permission + audit). */
    suspend fun moveCash(kind: Int, amount: Long, reason: String?, approval: Approval? = null) {
        require(amount > 0L) { "amount must be positive" }
        val actor = graph.permissions.actor(Perm.CASH_MOVE, approval)
        load()
        val shift = _current.value ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
        val device = graph.settings.device.value
        val action = when (kind) {
            CashMoveKind.CASH_IN -> AuditAction.CASH_IN
            CashMoveKind.CASH_OUT -> AuditAction.CASH_OUT
            CashMoveKind.DROP -> AuditAction.CASH_DROP
            else -> throw IllegalArgumentException("unknown cash movement $kind")
        }
        graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            val id = ShiftDao.insertMove(tx, shift.id, kind, amount, reason, actor.staffId, now)
            AuditDao.log(tx, action, actor.staffId, now, Entity.CASH_MOVE, id, amount, reason, actor.approvedBy)
            if (device.hasPrinter && device.drawerEnabled) PrintJobDao.enqueue(tx, PrintJobKind.DRAWER, null, 1, now)
        }
        graph.printer.wake()
    }

    /** The report of shift [shiftId] as it stands now. */
    suspend fun report(shiftId: Long): ShiftReport? {
        val store = graph.settings.store.value
        return graph.db().read { r ->
            val shift = ShiftDao.get(r, shiftId) ?: return@read null
            build(shift, ShiftDao.totals(r, shiftId), PaymentMethodDao.names(r), StaffDao.names(r), store.name)
        }
    }

    /**
     * Closes the open shift with [counted] cash: expected cash, the difference and the audit
     * entry are written in one transaction. The report prints when the closer may see it.
     */
    suspend fun close(counted: Long, note: String?): ShiftReport {
        require(counted >= 0L) { "negative count" }
        load()
        val open = _current.value ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
        val staff = graph.staff.staffId
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val printIt = device.hasPrinter && device.autoPrint && graph.permissions.allowed(Perm.SHIFT_REPORT)
        return withContext(NonCancellable) { closeCommitted(open, counted, note, staff, store.name, printIt) }
    }

    private suspend fun closeCommitted(open: Shift, counted: Long, note: String?, staff: Long, storeName: String, printIt: Boolean): ShiftReport {
        val report = graph.db().write(reserveIds = 4L) { tx ->
            val shift = ShiftDao.get(tx.db, open.id)?.takeIf { it.open } ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
            val now = System.currentTimeMillis()
            val before = build(shift, ShiftDao.totals(tx.db, shift.id), PaymentMethodDao.names(tx.db), StaffDao.names(tx.db), storeName)
            val expected = before.cash.expected
            val closed = ShiftDao.close(tx, shift, staff, counted, expected, note?.takeIf { it.isNotBlank() }, now)
            AuditDao.log(tx, AuditAction.SHIFT_CLOSE, staff, now, Entity.SHIFT, shift.id, Checked.sub(counted, expected), note)
            if (printIt) PrintJobDao.enqueue(tx, PrintJobKind.SHIFT, shift.id, 1, now)
            before.copy(closedBy = StaffDao.name(tx.db, staff), closedAt = closed.closedAt, counted = counted, note = note?.takeIf { it.isNotBlank() })
        }
        _current.value = null
        if (printIt) graph.printer.wake()
        return report
    }

    /** Prints the report of shift [shiftId] (needs SHIFT_REPORT). */
    suspend fun print(shiftId: Long, approval: Approval? = null) {
        graph.permissions.actor(Perm.SHIFT_REPORT, approval)
        graph.db().write(reserveIds = 0L) { tx -> PrintJobDao.enqueue(tx, PrintJobKind.SHIFT, shiftId, 1, System.currentTimeMillis()) }
        graph.printer.wake()
    }

    suspend fun page(after: Shift?, limit: Int = 50): List<Shift> = graph.db().read { ShiftDao.page(it, after, limit) }

    companion object {
        /** Pure: a shift's report from its totals (D-038). */
        fun build(shift: Shift, t: ShiftTotals, methods: Map<Long, String>, staff: Map<Long, String>, storeName: String): ShiftReport {
            val sale = t.docs.filter { it.kind == SaleKind.SALE }
            val refund = t.docs.filter { it.kind == SaleKind.REFUND }
            // Per method: payments taken in this shift minus those of documents voided in it
            // (a sale voided now may have been paid in an earlier shift).
            val paid = t.payments.groupBy { it.methodId }
            val voidedBy = t.voidedPayments.groupBy { it.methodId }
            val byMethod = (paid.keys + voidedBy.keys).map { methodId ->
                val p = paid[methodId].orEmpty()
                val v = voidedBy[methodId].orEmpty()
                val kind = (p.firstOrNull() ?: v.first()).kind
                MethodTotal(methods[methodId] ?: "#$methodId", kind, p.sumOf { it.count }, p.sumOf { it.amount } - v.sumOf { it.amount })
            }
            val cashPay = t.payments.filter { it.kind == PaymentKind.CASH }
            val cash = ShiftCash(
                openingFloat = shift.openingFloat,
                cashSales = cashPay.sumOf { it.positive },
                cashRefunds = cashPay.sumOf { it.amount - it.positive },
                voided = t.voidedPayments.filter { it.kind == PaymentKind.CASH }.sumOf { it.amount },
                cashIn = t.cashMoves[CashMoveKind.CASH_IN] ?: 0L,
                cashOut = t.cashMoves[CashMoveKind.CASH_OUT] ?: 0L,
                drops = t.cashMoves[CashMoveKind.DROP] ?: 0L,
                creditRepayments = t.credit.filter { it.kind == CreditKind.PAYMENT && it.methodKind == PaymentKind.CASH }.sumOf { it.amount },
            )
            val repaid = t.credit.filter { it.kind == CreditKind.PAYMENT }.map {
                MethodTotal(it.methodId?.let { id -> methods[id] } ?: "-", it.methodKind ?: PaymentKind.OTHER, it.count, it.amount)
            }
            return ShiftReport(
                storeName = storeName,
                deviceNo = shift.deviceNo,
                openedBy = staff[shift.openedBy],
                openedAt = shift.openedAt,
                closedBy = shift.closedBy?.let { staff[it] },
                closedAt = shift.closedAt,
                sales = sale.sumOf { it.count },
                salesTotal = sale.sumOf { it.total },
                refunds = refund.sumOf { it.count },
                refundsTotal = refund.sumOf { it.total },
                voids = t.voidCount,
                voidsTotal = t.voidTotal,
                discounts = t.discount, // documents voided in this shift come off (D-038, D-054)
                tax = t.tax,
                methods = byMethod,
                cash = cash,
                counted = shift.countedCash,
                creditCharged = t.credit.filter { it.kind == CreditKind.CHARGE }.sumOf { it.amount },
                creditRepaid = repaid,
                note = shift.note,
            )
        }
    }
}
