package com.lekaspos.domain

import android.os.SystemClock
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.staff.PinHash
import com.lekaspos.core.staff.PinLockout
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.Seed
import com.lekaspos.data.staff.Staff
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.domain.sale.ActionRefused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Who is using the till (D-037). While nobody has a PIN the till runs as the store owner with
 * every permission, exactly as before Phase 4. Once an active staff member has a PIN, someone
 * must sign in; the signed-in staff member survives a process restart (crash-safe till) until
 * they lock the till or it locks itself after the idle time set for this device.
 */
class StaffSession(private val graph: AppGraph) {

    data class Signed(val id: Long, val name: String, val roleName: String?, val perms: Long, val isOwner: Boolean)

    data class State(
        val loaded: Boolean = false,
        /** Some active staff member has a PIN, so the till needs a sign-in. */
        val loginRequired: Boolean = false,
        val current: Signed? = null,
    ) {
        val locked: Boolean get() = loaded && loginRequired && current == null
    }

    /** Outcome of checking a PIN (sign-in or a manager's approval). */
    sealed class Check {
        data class Ok(val staff: Staff) : Check()

        /** Wrong PIN: [triesLeft] before the till must wait, or the wait it now imposes. */
        data class WrongPin(val triesLeft: Int, val waitMs: Long) : Check()

        /** Too many wrong PINs: no PIN is checked for [ms] more. */
        data class Wait(val ms: Long) : Check()

        /** The staff member cannot sign in (removed, inactive, no PIN) or lacks the permission. */
        object NotAllowed : Check()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Who actions are recorded for: the signed-in staff member, or the owner while PIN login is off. */
    val staffId: Long get() = _state.value.current?.id ?: Seed.Ids.STAFF_OWNER

    /** Permissions of [staffId]: everything while PIN login is off, nothing while locked. */
    val perms: Long
        get() {
            val s = _state.value
            return s.current?.perms ?: if (s.loginRequired) 0L else Perm.ALL
        }

    private val loadLock = Mutex()
    private val pinLock = Mutex()

    @Volatile
    private var lastActivity = SystemClock.elapsedRealtime()

    /** Bumped by every sign-in and lock, so a late "clear the signed-in staff" write never undoes a newer sign-in. */
    @Volatile
    private var generation = 0L

    suspend fun load() = loadLock.withLock {
        if (!_state.value.loaded) reloadLocked()
    }

    /** Re-reads staff and roles, e.g. after an edit in Settings → Staff (role, PIN, active). */
    suspend fun reload() = loadLock.withLock { reloadLocked() }

    private suspend fun reloadLocked() {
        val db = graph.db()
        val (required, signed) = db.read { r ->
            val required = StaffDao.loginRequired(r)
            val id = _state.value.current?.id ?: Meta.getLong(r, KEY_STAFF)
            required to id?.let { StaffDao.get(r, it) }?.takeIf { it.canSignIn }
        }
        val current = if (required) signed?.let(::signed) else null
        if (current == null && _state.value.current != null) graph.permissions.clear()
        _state.value = State(loaded = true, loginRequired = required, current = current)
    }

    /** Checks [pin] of [staffId] and signs them in (audited). */
    suspend fun signIn(staffId: Long, pin: String): Check {
        val c = check(staffId, pin, perm = 0L)
        if (c is Check.Ok) {
            val now = System.currentTimeMillis()
            generation++
            graph.db().write(reserveIds = 2L) { tx ->
                Meta.put(tx.db, KEY_STAFF, staffId.toString())
                AuditDao.log(tx, AuditAction.SIGN_IN, staffId, now, Entity.STAFF, staffId)
            }
            graph.permissions.clear()
            touch()
            _state.value = _state.value.copy(loaded = true, current = signed(c.staff))
        }
        return c
    }

    /** Signs [staff] in without a PIN check: right after they set their own PIN. */
    internal suspend fun adopt(staff: Staff) {
        generation++
        graph.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, KEY_STAFF, staff.id.toString()) }
        touch()
        _state.value = _state.value.copy(loaded = true, current = signed(staff))
    }

    /** Locks the till: the next person must sign in. No effect while PIN login is off. */
    fun lock() {
        val s = _state.value
        if (!s.loginRequired || s.current == null) return
        graph.permissions.clear()
        _state.value = s.copy(current = null)
        val g = ++generation
        graph.appScope.launch {
            graph.db().write(reserveIds = 0L) { tx -> if (generation == g) Meta.put(tx.db, KEY_STAFF, null) }
        }
    }

    /** Audits a manager's approval that a screen keeps (the permission bits go in `amount`). */
    suspend fun recordApproval(a: Approval) {
        val now = System.currentTimeMillis()
        graph.db().write(reserveIds = 1L) { tx ->
            AuditDao.log(tx, AuditAction.APPROVAL, staffId, now, amount = a.perm, approvedBy = a.staffId)
        }
    }

    /** Records user activity (touches and keys) for the idle lock. */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    /** Locks when this device's idle time has passed; returns true if it locked. */
    fun lockIfIdle(): Boolean {
        val minutes = graph.settings.device.value.autoLockMinutes
        val s = _state.value
        if (minutes <= 0 || !s.loginRequired || s.current == null) return false
        if (SystemClock.elapsedRealtime() - lastActivity < minutes * 60_000L) return false
        lock()
        return true
    }

    /** Milliseconds this till must still wait before a PIN is checked (after too many wrong ones). */
    suspend fun waitMs(): Long {
        val (fails, last) = graph.db().read { r -> (Meta.getLong(r, KEY_FAILS) ?: 0L).toInt() to (Meta.getLong(r, KEY_LAST_FAIL) ?: 0L) }
        return PinLockout.remainingMs(fails, last, System.currentTimeMillis())
    }

    /**
     * Checks [pin] of [staffId], who must be able to sign in and hold [perm] (0 = any). Wrong
     * PINs count towards this till's lockout, whoever's PIN was tried (D-037).
     */
    suspend fun check(staffId: Long, pin: String, perm: Long): Check = pinLock.withLock {
        val db = graph.db()
        val now = System.currentTimeMillis()
        val (staff, fails, last) = db.read { r ->
            Triple(StaffDao.get(r, staffId), (Meta.getLong(r, KEY_FAILS) ?: 0L).toInt(), Meta.getLong(r, KEY_LAST_FAIL) ?: 0L)
        }
        val wait = PinLockout.remainingMs(fails, last, now)
        if (wait > 0L) return@withLock Check.Wait(wait)
        if (staff == null || !staff.canSignIn || !Perm.has(staff.perms, perm)) return@withLock Check.NotAllowed
        val ok = withContext(Dispatchers.Default) { PinHash.verify(pin, staff.pin) }
        if (ok) {
            if (fails != 0) db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, KEY_FAILS, "0") }
            return@withLock Check.Ok(staff)
        }
        val n = fails + 1
        db.write(reserveIds = 1L) { tx ->
            Meta.put(tx.db, KEY_FAILS, n.toString())
            Meta.put(tx.db, KEY_LAST_FAIL, now.toString())
            if (n == PinLockout.FREE_TRIES) {
                AuditDao.log(tx, AuditAction.PIN_LOCKOUT, staffId, now, Entity.STAFF, staffId, detail = "$n wrong PINs")
            }
        }
        Check.WrongPin(PinLockout.triesLeft(n), PinLockout.waitMs(n))
    }

    private fun signed(s: Staff) = Signed(s.id, s.name, s.roleName, s.perms, s.isOwner)

    companion object {
        const val KEY_STAFF = "session.staff"
        const val KEY_FAILS = "pin.fails"
        const val KEY_LAST_FAIL = "pin.last_fail"
    }
}

/**
 * A manager's PIN-checked approval of one permission (D-037). Only [PermissionGate.approve]
 * creates one; it is passed to a single action, or held by one screen while it is open.
 */
class Approval internal constructor(val perm: Long, val staffId: Long, val name: String)

/** Who does an action and, when it needed a manager, who approved it (audit `approved_by`). */
data class Actor(val staffId: Long, val approvedBy: Long?)

/**
 * Permission checks for every sensitive action (D-028, D-037): the signed-in staff member's
 * role, else a manager's [Approval] for this action, else an approval held by an open screen.
 */
class PermissionGate(private val session: StaffSession) {

    private val elevations = LinkedHashMap<Long, Approval>()
    private var nextToken = 1L

    /** May the current user do [perm] (by role, or through an open screen's approval)? */
    fun allowed(perm: Long): Boolean = Perm.has(session.perms, perm) || elevated(perm) != null

    /** May the current user's role do [perm], without any approval? */
    fun ownRole(perm: Long): Boolean = Perm.has(session.perms, perm)

    /** The actor for an action needing [perm]; throws [ActionRefused] NOT_ALLOWED if nobody allows it. */
    fun actor(perm: Long, approval: Approval? = null): Actor =
        actorOrNull(perm, approval) ?: throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)

    fun actorOrNull(perm: Long, approval: Approval? = null): Actor? {
        val staff = session.staffId
        if (Perm.has(session.perms, perm)) return Actor(staff, null)
        if (approval != null && Perm.has(approval.perm, perm)) return Actor(staff, approval.staffId)
        val e = elevated(perm) ?: return null
        return Actor(staff, e.staffId)
    }

    /** Checks the approver's PIN and permission; [StaffSession.Check.Ok] comes with the approval. */
    suspend fun approve(staffId: Long, pin: String, perm: Long): Pair<StaffSession.Check, Approval?> {
        val c = session.check(staffId, pin, perm)
        return c to if (c is StaffSession.Check.Ok) Approval(perm, c.staff.id, c.staff.name) else null
    }

    /** Lets a screen use [approval] until [release]; returns the token. */
    @Synchronized
    fun elevate(approval: Approval): Long {
        val token = nextToken++
        elevations[token] = approval
        return token
    }

    @Synchronized
    fun release(token: Long) {
        elevations.remove(token)
    }

    /** Is [token] still valid (not cleared by a sign-out)? */
    @Synchronized
    fun holds(token: Long): Boolean = elevations.containsKey(token)

    /** Drops every screen approval (sign-in, lock). */
    @Synchronized
    fun clear() {
        elevations.clear()
    }

    @Synchronized
    private fun elevated(perm: Long): Approval? = elevations.values.lastOrNull { Perm.has(it.perm, perm) }
}
