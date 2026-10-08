package com.lekaspos.domain.shift

import android.database.sqlite.SQLiteDatabase
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
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.shift.LeftInDrawer
import com.lekaspos.core.shift.MethodTotal
import com.lekaspos.core.shift.ShiftCash
import com.lekaspos.core.shift.ShiftGuide
import com.lekaspos.core.shift.ShiftReport
import com.lekaspos.core.staff.Checks
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.db.Meta
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.settings.SettingKeys
import com.lekaspos.data.settings.SettingsDao
import com.lekaspos.data.settings.StoreSettings
import com.lekaspos.data.shift.Shift
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.data.shift.ShiftTotals
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused
import java.util.TimeZone
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

    /**
     * Opens a shift with [openingFloat] in the drawer (the drawer opens to put it in). A count that is not
     * what the last close left in the drawer ([leftInDrawer]) is on record (FLOAT_DIFFERENCE, D-068).
     */
    suspend fun open(openingFloat: Long): Shift {
        require(openingFloat >= 0L) { "negative float" }
        load()
        val staff = graph.staff.staffId
        val device = graph.settings.device.value
        val currency = graph.settings.store.value.currency
        // The shift in memory follows the commit even when the screen that asked is closing: a
        // shift opened in the database but not here refused every payment (no shift) and every
        // open (one is open) until the app restarted (2026-10 review). Likewise for close.
        return withContext(NonCancellable) {
            val shift = graph.db().write(reserveIds = 4L) { tx ->
                if (ShiftDao.current(tx.db, tx.deviceNo) != null) throw ActionRefused(ActionRefused.Reason.SHIFT_OPEN)
                val now = System.currentTimeMillis()
                val s = ShiftDao.open(tx, staff, openingFloat, now)
                AuditDao.log(tx, AuditAction.SHIFT_OPEN, staff, now, Entity.SHIFT, s.id, openingFloat)
                val left = LeftInDrawer.decode(Meta.get(tx.db, LEFT_KEY))
                if (left != null && left.amount != openingFloat) {
                    val by = StaffDao.name(tx.db, left.staffId) ?: "#${left.staffId}"
                    val text = "left by $by: ${MoneyFormat.format(left.amount, currency)}, counted ${MoneyFormat.format(openingFloat, currency)}"
                    AuditDao.log(tx, AuditAction.FLOAT_DIFFERENCE, staff, now, Entity.SHIFT, s.id, Checked.sub(openingFloat, left.amount), text)
                }
                Meta.put(tx.db, LEFT_KEY, null)
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
            build(shift, ShiftDao.totals(r, shiftId), PaymentMethodDao.names(r), StaffDao.names(r), store.name, checks(r, shift))
        }
    }

    /**
     * Closes the open shift with [counted] cash: expected cash, the difference and the audit
     * entry are written in one transaction. The report prints when the closer may see it.
     */
    suspend fun close(counted: Long, note: String?, leave: Long? = null): ShiftReport {
        require(counted >= 0L) { "negative count" }
        load()
        val open = _current.value ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
        val staff = graph.staff.staffId
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val printIt = device.hasPrinter && device.autoPrint && graph.permissions.allowed(Perm.SHIFT_REPORT)
        return withContext(NonCancellable) { closeCommitted(open, counted, note, leave?.coerceIn(0L, counted), staff, store, printIt) }
    }

    /**
     * [leave]: the cash that stays in the drawer for the next shift (the rest goes to the owner, D-068); the
     * next opening count is compared with it.
     */
    private suspend fun closeCommitted(
        open: Shift, counted: Long, note: String?, leave: Long?, staff: Long, store: StoreSettings, printIt: Boolean,
    ): ShiftReport {
        val report = graph.db().write(reserveIds = 4L) { tx ->
            val shift = ShiftDao.get(tx.db, open.id)?.takeIf { it.open } ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
            val now = System.currentTimeMillis()
            val before = build(
                shift, ShiftDao.totals(tx.db, shift.id), PaymentMethodDao.names(tx.db), StaffDao.names(tx.db), store.name,
                checks(tx.db, shift.copy(closedAt = now)),
            )
            val expected = before.cash.expected
            val closed = ShiftDao.close(tx, shift, staff, counted, expected, note?.takeIf { it.isNotBlank() }, now)
            val left = leave?.let { " · left in the drawer ${MoneyFormat.format(it, store.currency)}" }.orEmpty()
            AuditDao.log(tx, AuditAction.SHIFT_CLOSE, staff, now, Entity.SHIFT, shift.id, Checked.sub(counted, expected), (note.orEmpty() + left).trim(' ', '·').ifEmpty { null })
            Meta.put(tx.db, LEFT_KEY, leave?.let { LeftInDrawer(it, staff, now).encode() })
            if (printIt) PrintJobDao.enqueue(tx, PrintJobKind.SHIFT, shift.id, 1, now)
            before.copy(closedBy = StaffDao.name(tx.db, staff), closedAt = closed.closedAt, counted = counted, note = note?.takeIf { it.isNotBlank() })
        }
        _current.value = null
        if (printIt) graph.printer.wake()
        return report
    }

    /**
     * The cashier changed (D-067): the open shift, someone else's, is closed with [counted] cash —
     * counted by whoever signed in now — and their own shift opens with that same cash as its float.
     * One count, in one transaction: no moment between two shifts to take cash from. The closed
     * shift's over/short is the one who opened it; the report says who counted.
     */
    suspend fun handover(counted: Long, note: String?, newDay: Boolean = false): Shift {
        require(counted >= 0L) { "negative count" }
        load()
        val open = _current.value ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
        val staff = graph.staff.staffId
        val store = graph.settings.store.value
        val device = graph.settings.device.value
        val printIt = device.hasPrinter && device.autoPrint && graph.permissions.allowed(Perm.SHIFT_REPORT)
        val text = note?.takeIf { it.isNotBlank() }
        return withContext(NonCancellable) {
            val shift = graph.db().write(reserveIds = 8L) { tx ->
                val shift = ShiftDao.get(tx.db, open.id)?.takeIf { it.open } ?: throw ActionRefused(ActionRefused.Reason.NEEDS_SHIFT)
                val now = System.currentTimeMillis()
                val expected = build(shift, ShiftDao.totals(tx.db, shift.id), PaymentMethodDao.names(tx.db), StaffDao.names(tx.db), store.name).cash.expected
                ShiftDao.close(tx, shift, staff, counted, expected, text, now)
                val by = StaffDao.name(tx.db, shift.openedBy) ?: "#${shift.openedBy}"
                // A shift nobody closed the day before is closed by the next day's first count (D-068).
                val why = if (newDay) "not closed the day before, opened by $by" else "handover from $by"
                AuditDao.log(tx, AuditAction.SHIFT_CLOSE, staff, now, Entity.SHIFT, shift.id, Checked.sub(counted, expected), why + (text?.let { ": $it" } ?: ""))
                if (printIt) PrintJobDao.enqueue(tx, PrintJobKind.SHIFT, shift.id, 1, now)
                val next = ShiftDao.open(tx, staff, counted, now)
                AuditDao.log(tx, AuditAction.SHIFT_OPEN, staff, now, Entity.SHIFT, next.id, counted, why)
                Meta.put(tx.db, LEFT_KEY, null) // one count: nothing in between to compare
                next
            }
            _current.value = shift
            if (printIt) graph.printer.wake()
            shift
        }
    }

    /**
     * What the till asks [staffId] who is using it now (D-068, `:core` ShiftGuide), about [shift] (the open one,
     * opened by [openedBy]); [left]: what the last close left in the drawer, for an opening count.
     */
    class Prompt(val ask: ShiftGuide.Ask, val shift: Shift?, val openedBy: String?, val left: LeftInDrawer?) {
        /** For [markAsked]: the shift asked about, or (no shift open) the day. */
        val key: Long get() = shift?.id ?: -(Days.epochDay(System.currentTimeMillis(), TimeZone.getDefault()) + 1L)
    }

    suspend fun prompt(staffId: Long, tz: TimeZone = TimeZone.getDefault()): Prompt {
        load()
        val store = graph.settings.store.value
        val open = _current.value
        val today = Days.epochDay(System.currentTimeMillis(), tz)
        var ask = ShiftGuide.ask(store.shiftRequired, store.handoverCount, open?.openedBy, open?.let { Days.epochDay(it.openedAt, tz) }, staffId, today)
        val (openedBy, left, declined) = graph.db().read { r ->
            Triple(
                open?.let { StaffDao.name(r, it.openedBy) },
                LeftInDrawer.decode(Meta.get(r, LEFT_KEY)),
                open != null && ask == ShiftGuide.Ask.HANDOVER && AuditDao.exists(r, AuditAction.SHIFT_CONTINUED, staffId, open.id),
            )
        }
        if (declined) ask = ShiftGuide.Ask.NONE // "Not now" in this shift: not asked again
        return Prompt(ask, open, openedBy, left)
    }

    /**
     * Whether [staffId], signed in, should be asked to count the drawer: another person's shift is open
     * here, the store asks for it, and they did not choose "Not now" in this shift ([continueShift]).
     */
    suspend fun handoverDue(staffId: Long): Shift? = prompt(staffId).takeIf { it.ask == ShiftGuide.Ask.HANDOVER }?.shift

    /** What the last close left in the drawer for the next shift on this till, if it was said (D-068). */
    suspend fun leftInDrawer(): LeftInDrawer? = graph.db().read { LeftInDrawer.decode(Meta.get(it, LEFT_KEY)) }

    private val asked = HashSet<Pair<Long, Long>>()

    /** True the first time (this process) [staffId] is asked about [key] (a shift, or a day without one: [Prompt.key]). */
    fun markAsked(key: Long, staffId: Long): Boolean = synchronized(asked) { asked.add(key to staffId) }

    /**
     * 1.12.1 (D-068): a shop whose staff sign in with PINs of their own uses shifts — the only way cash missing
     * from the drawer shows. Turned on once, when such staff first exist (on record; the owner can turn it off
     * in Store & receipt, and it stays off). A shop of one keeps its choice.
     */
    suspend fun useShiftsWithStaff() {
        val db = graph.db()
        val (done, staff) = db.read { r -> (Meta.get(r, SHIFTS_ON_KEY) != null) to StaffDao.list(r).any { it.canSignIn && !it.isOwner } }
        if (done) return
        val required = graph.settings.store.value.shiftRequired
        if (!required && !staff) return // asked again at the next start
        withContext(NonCancellable) {
            db.write(reserveIds = 1L) { tx ->
                val now = System.currentTimeMillis()
                if (!required) {
                    SettingsDao.putChanged(tx, SettingsDao.all(tx.db), mapOf(SettingKeys.SHIFT_REQUIRED to "1"), now)
                    AuditDao.log(tx, AuditAction.SETTINGS_CHANGE, null, now, detail = "shift.required: on (the shop has staff)")
                }
                Meta.put(tx.db, SHIFTS_ON_KEY, "1")
            }
            if (!required) graph.settings.reload()
        }
    }

    /** "Not now": [staffId] sells on in the open shift without a count; on record, and not asked again in it. */
    suspend fun continueShift(staffId: Long, shift: Shift) {
        withContext(NonCancellable) {
            graph.db().write(reserveIds = 1L) { tx ->
                val by = StaffDao.name(tx.db, shift.openedBy) ?: "#${shift.openedBy}"
                AuditDao.log(tx, AuditAction.SHIFT_CONTINUED, staffId, System.currentTimeMillis(), Entity.SHIFT, shift.id, detail = "shift of $by")
            }
        }
    }

    /** Prints the report of shift [shiftId] (needs SHIFT_REPORT). */
    suspend fun print(shiftId: Long, approval: Approval? = null) {
        graph.permissions.actor(Perm.SHIFT_REPORT, approval)
        graph.db().write(reserveIds = 0L) { tx -> PrintJobDao.enqueue(tx, PrintJobKind.SHIFT, shiftId, 1, System.currentTimeMillis()) }
        graph.printer.wake()
    }

    suspend fun page(after: Shift?, limit: Int = 50): List<Shift> = graph.db().read { ShiftDao.page(it, after, limit) }

    companion object {
        /** LOCAL meta: what the last close left in the drawer ([LeftInDrawer], D-068). */
        const val LEFT_KEY = "shift.left"

        /** LOCAL meta: [useShiftsWithStaff] has run (or found shifts on) on this till. */
        const val SHIFTS_ON_KEY = "upgrade.shifts_on"

        /**
         * What this till's activity log holds for [shift] that the owner should look at (D-067): bills
         * cleared, items taken off, the drawer opened without a sale … from its opening to its close (or now).
         */
        fun checks(r: SQLiteDatabase, shift: Shift): Checks =
            Checks.of(AuditDao.totalsOfTill(r, shift.deviceNo, shift.openedAt, shift.closedAt ?: System.currentTimeMillis()))

        /** Pure: a shift's report from its totals (D-038). */
        fun build(shift: Shift, t: ShiftTotals, methods: Map<Long, String>, staff: Map<Long, String>, storeName: String, checks: Checks = Checks()): ShiftReport {
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
                checks = checks,
            )
        }
    }
}
