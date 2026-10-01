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

    /**
     * Permissions of [staffId]: everything while PIN login is off, nothing while locked — and
     * nothing until [load] has run. "Not loaded yet" must never mean "no PIN login, so the owner":
     * a screen Android restored into a new process ran with every permission that way.
     */
    val perms: Long
        get() {
            val s = _state.value
            if (!s.loaded) return 0L
            return s.current?.perms ?: if (s.loginRequired) 0L else Perm.ALL
        }

    private val loadLock = Mutex()
    private val pinLock = Mutex()

    @Volatile
    private var lastActivity = SystemClock.elapsedRealtime()

    /**
     * Bumped (under [stateLock]) by every sign-in and lock, so a late "clear the signed-in staff"
     * write never undoes a newer sign-in, and a [reload] that read the database before a lock never
     * signs the person back in.
     */
    @Volatile
    private var generation = 0L
    private val stateLock = Any()

    /** Screens of the app started and not stopped yet (changed on the main thread): 0 = out of sight. */
    @Volatile
    private var screens = 0

    /** Bumped by each away-time write, so only the newest one is stored (see [screenStopped]). */
    @Volatile
    private var awayGeneration = 0L
    @Volatile
    private var awayStored = false

    suspend fun load() = loadLock.withLock {
        if (!_state.value.loaded) reloadLocked()
    }

    /** Re-reads staff and roles, e.g. after an edit in Settings → Staff (role, PIN, active). */
    suspend fun reload() = loadLock.withLock { reloadLocked() }

    private suspend fun reloadLocked() {
        val db = graph.db()
        val first = !_state.value.loaded
        // The idle time of this device decides whether the stored sign-in may come back.
        if (first) graph.settings.load()
        while (true) {
            val g = generation
            val inMemory = _state.value.current?.id
            // Only the first load (a new process) brings back the stored sign-in. Later, nobody in
            // memory means locked: the "clear the signed-in staff" write may still be queued, and
            // reading it back signed the person in again (2026-10 review).
            val (required, signed, away) = db.read { r ->
                val required = StaffDao.loginRequired(r)
                val id = inMemory ?: if (first) Meta.getLong(r, KEY_STAFF) else null
                val away = if (first) Meta.getLong(r, KEY_AWAY) else null
                Triple(required, id?.let { StaffDao.get(r, it) }?.takeIf { it.canSignIn }, away)
            }
            var current = if (required) signed?.let(::signed) else null
            var expired = false
            if (current != null && inMemory == null && away != null) {
                // A new process (Android ended the app while the phone was idle): the idle lock
                // counts from the last activity before the app went out of sight, not from now.
                val idle = System.currentTimeMillis() - away
                val minutes = graph.settings.device.value.autoLockMinutes
                if (minutes > 0 && (idle < 0L || idle >= minutes * 60_000L)) {
                    current = null
                    expired = true
                } else {
                    lastActivity = SystemClock.elapsedRealtime() - idle.coerceAtLeast(0L)
                }
            }
            val applied = synchronized(stateLock) {
                if (generation != g) {
                    false // signed in or locked meanwhile: read again
                } else {
                    if (current == null && _state.value.current != null) graph.permissions.clear()
                    if (expired) generation++ // a late write of the stored sign-in must not bring it back
                    _state.value = State(loaded = true, loginRequired = required, current = current)
                    true
                }
            }
            if (!applied) continue
            // The away time stays until a screen shows (screenStarted): a process started in the
            // background (a sync job) must not use it up, or a later start would sign in again.
            if (away != null) {
                if (screens > 0) storeAway(null) else awayStored = true
            }
            if (expired) {
                // An expired sign-in is forgotten for good.
                val staffGen = generation
                db.write(reserveIds = 0L) { tx -> if (generation == staffGen) Meta.put(tx.db, KEY_STAFF, null) }
            }
            return
        }
    }

    /** Checks [pin] of [staffId] and signs them in (audited). */
    suspend fun signIn(staffId: Long, pin: String): Check {
        val c = check(staffId, pin, perm = 0L)
        if (c is Check.Ok) {
            val now = System.currentTimeMillis()
            synchronized(stateLock) { generation++ }
            graph.db().write(reserveIds = 2L) { tx ->
                Meta.put(tx.db, KEY_STAFF, staffId.toString())
                AuditDao.log(tx, AuditAction.SIGN_IN, staffId, now, Entity.STAFF, staffId)
            }
            graph.permissions.clear()
            touch()
            synchronized(stateLock) {
                generation++
                _state.value = _state.value.copy(loaded = true, current = signed(c.staff))
            }
        }
        return c
    }

    /** Signs [staff] in without a PIN check: right after they set their own PIN. */
    internal suspend fun adopt(staff: Staff) {
        synchronized(stateLock) { generation++ }
        graph.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, KEY_STAFF, staff.id.toString()) }
        touch()
        synchronized(stateLock) {
            generation++
            _state.value = _state.value.copy(loaded = true, current = signed(staff))
        }
    }

    /** Locks the till: the next person must sign in. No effect while PIN login is off. */
    fun lock() {
        val g: Long
        synchronized(stateLock) {
            val s = _state.value
            if (!s.loginRequired || s.current == null) return
            graph.permissions.clear()
            _state.value = s.copy(current = null)
            g = ++generation
        }
        graph.appScope.launch {
            graph.db().write(reserveIds = 0L) { tx -> if (generation == g) Meta.put(tx.db, KEY_STAFF, null) }
        }
    }

    /**
     * A screen of the app became visible. The idle time is checked at once: when the phone's
     * screen had turned off, the first tap after waking it counted as activity before any idle
     * check ran, so the till never locked (2026-10 review). Returns true if it locked.
     */
    fun screenStarted(): Boolean {
        if (screens++ == 0 && awayStored) {
            awayStored = false
            storeAway(null)
        }
        return lockIfIdle()
    }

    /**
     * A screen stopped. When none is left (screen off, another app), the time of the last activity
     * is stored: if Android ends the app meanwhile, the next start still knows how long the till
     * was idle, instead of bringing the sign-in back with a fresh idle time.
     */
    fun screenStopped() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens > 0) return
        val s = _state.value
        if (!s.loginRequired || s.current == null) return
        awayStored = true
        storeAway(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - lastActivity))
    }

    private fun storeAway(at: Long?) {
        val g = ++awayGeneration
        graph.appScope.launch {
            graph.db().write(reserveIds = 0L) { tx -> if (awayGeneration == g) Meta.put(tx.db, KEY_AWAY, at?.toString()) }
        }
    }

    /**
     * A touch or key: locks first when the till has been idle too long (returns true: the event
     * must be dropped, it was meant for the person signed in before), else records the activity.
     */
    fun activity(): Boolean {
        if (lockIfIdle()) return true
        touch()
        return false
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

    /** Tests: as if the last touch or key was [ms] ago. */
    @androidx.annotation.VisibleForTesting
    internal fun idleFor(ms: Long) {
        lastActivity = SystemClock.elapsedRealtime() - ms
    }

    /**
     * Locks when this device's idle time has passed; returns true if it locked. Never while a
     * bill is being paid: locking closed the payment and lost a split payment half entered.
     */
    fun lockIfIdle(): Boolean {
        val minutes = graph.settings.device.value.autoLockMinutes
        val s = _state.value
        if (minutes <= 0 || !s.loginRequired || s.current == null) return false
        val bill = graph.cart.state.value
        if (bill.paying || bill.busy) return false
        if (SystemClock.elapsedRealtime() - lastActivity < minutes * 60_000L) return false
        lock()
        return true
    }

    /** Milliseconds before a PIN of [staffId] is checked again (after too many wrong ones). */
    suspend fun waitMs(staffId: Long): Long {
        val f = graph.db().read { r -> fails(r, staffId) }
        return remaining(f, System.currentTimeMillis())
    }

    /** Wrong PINs in a row, the wall-clock time of the last one, and "boot:elapsedRealtime" of it. */
    private data class Fails(val count: Int, val last: Long, val lastRt: String?)

    private fun fails(r: android.database.sqlite.SQLiteDatabase, staffId: Long) = Fails(
        (Meta.getLong(r, KEY_FAILS + staffId) ?: 0L).toInt(),
        Meta.getLong(r, KEY_LAST_FAIL + staffId) ?: 0L,
        Meta.get(r, KEY_LAST_FAIL_RT + staffId),
    )

    /**
     * The wait left, by the wall clock and — when the phone has not restarted since the last wrong
     * PIN — by the time since boot, which setting the clock cannot change: setting the clock forward
     * ended any wait, so a 4-digit PIN could be guessed in hours (2026-10 review).
     */
    private fun remaining(f: Fails, now: Long): Long {
        var wait = PinLockout.remainingMs(f.count, f.last, now)
        val boot = graph.bootCount()
        val at = f.lastRt?.split(':')
        if (boot != null && at != null && at.size == 2 && at[0] == boot.toString()) {
            val rt = at[1].toLongOrNull()
            if (rt != null) wait = maxOf(wait, PinLockout.remainingMs(f.count, rt, SystemClock.elapsedRealtime()))
        }
        return wait
    }

    private fun bootStamp(): String? = graph.bootCount()?.let { "$it:${SystemClock.elapsedRealtime()}" }

    /** Forgets [staffId]'s wrong PINs (a new PIN was set for them, or the owner used the recovery code). */
    internal fun clearFails(tx: com.lekaspos.data.db.Db.Tx, staffId: Long) {
        Meta.put(tx.db, KEY_FAILS + staffId, null)
        Meta.put(tx.db, KEY_LAST_FAIL + staffId, null)
        Meta.put(tx.db, KEY_LAST_FAIL_RT + staffId, null)
    }

    /**
     * Checks [pin] of [staffId], who must be able to sign in and hold [perm] (0 = any). Wrong
     * PINs are counted per staff member, and only that person's right PIN clears the count: a
     * cashier's own sign-in must not give fresh guesses at the manager's PIN (2026-10 review).
     */
    suspend fun check(staffId: Long, pin: String, perm: Long): Check = pinLock.withLock {
        val db = graph.db()
        val now = System.currentTimeMillis()
        val (staff, f) = db.read { r -> StaffDao.get(r, staffId) to fails(r, staffId) }
        val wait = remaining(f, now)
        if (wait > 0L) {
            // The clock was set back before the last wrong PIN: the wait counts from now. Left as it
            // was, the wait lasted until the clock reached that time again — a cashier who set the
            // date to 2099 for five wrong owner PINs blocked the owner's PIN for years.
            if (now < f.last) db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, KEY_LAST_FAIL + staffId, now.toString()) }
            return@withLock Check.Wait(wait)
        }
        if (staff == null || !staff.canSignIn || !Perm.has(staff.perms, perm)) return@withLock Check.NotAllowed
        val ok = withContext(Dispatchers.Default) { PinHash.verify(pin, staff.pin) }
        if (ok) {
            if (f.count != 0) db.write(reserveIds = 0L) { tx -> Meta.put(tx.db, KEY_FAILS + staffId, null) }
            return@withLock Check.Ok(staff)
        }
        val n = f.count + 1
        db.write(reserveIds = 1L) { tx ->
            Meta.put(tx.db, KEY_FAILS + staffId, n.toString())
            Meta.put(tx.db, KEY_LAST_FAIL + staffId, now.toString())
            Meta.put(tx.db, KEY_LAST_FAIL_RT + staffId, bootStamp())
            // Every wait is recorded (the 5th wrong PIN and each one after it), with who was signed in.
            if (n >= PinLockout.FREE_TRIES) {
                AuditDao.log(tx, AuditAction.PIN_LOCKOUT, _state.value.current?.id, now, Entity.STAFF, staffId, detail = "$n wrong PINs")
            }
        }
        Check.WrongPin(PinLockout.triesLeft(n), PinLockout.waitMs(n))
    }

    private fun signed(s: Staff) = Signed(s.id, s.name, s.roleName, s.perms, s.isOwner)

    companion object {
        const val KEY_STAFF = "session.staff"
        /** Wrong PINs in a row and the time of the last one, per staff member: the staff id follows. */
        const val KEY_FAILS = "pin.fails."
        const val KEY_LAST_FAIL = "pin.last_fail."
        const val KEY_LAST_FAIL_RT = "pin.last_fail_rt."

        /** Wall-clock time of the last activity while the till was out of sight (see [screenStopped]). */
        const val KEY_AWAY = "session.away_at"
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
