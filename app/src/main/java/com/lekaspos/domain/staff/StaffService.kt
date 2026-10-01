package com.lekaspos.domain.staff

import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SysRole
import com.lekaspos.core.staff.PinHash
import com.lekaspos.core.staff.RecoveryCode
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.settings.SettingKeys
import com.lekaspos.data.settings.SettingsDao
import com.lekaspos.data.staff.Role
import com.lekaspos.data.staff.RoleDao
import com.lekaspos.data.staff.Staff
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.domain.Actor
import com.lekaspos.domain.Approval
import com.lekaspos.domain.StaffSession
import com.lekaspos.domain.sale.ActionRefused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Staff, roles and PINs (D-037). One rule protects the store from locking itself out: while
 * anyone has a PIN, at least one active owner must have one too — so the first PIN is always
 * an owner's, and the last owner cannot lose theirs. The owner's recovery code (shown once)
 * is the way back in after a forgotten PIN.
 */
class StaffService(private val graph: AppGraph) {

    suspend fun staff(): List<Staff> = graph.db().read { StaffDao.list(it) }

    suspend fun roles(): List<Role> = graph.db().read { RoleDao.list(it) }

    /** Adds ([before] = null) or edits a staff member's name, role and active flag. */
    suspend fun save(before: Staff?, name: String, roleId: Long, active: Boolean, approval: Approval? = null): Long {
        val actor = graph.permissions.actor(Perm.MANAGE_STAFF, approval)
        require(name.isNotBlank()) { "name required" }
        val id = graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            val role = RoleDao.get(tx.db, roleId) ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (role.isOwner || before?.isOwner == true) requireOwner(tx, actor)
            val id: Long
            if (before == null) {
                id = StaffDao.insert(tx, name.trim(), roleId, active, null, now)
            } else {
                id = before.id
                val after = before.copy(name = name.trim(), roleId = roleId, active = active, sysRole = role.sysRole, rolePerms = role.perms)
                checkOwnerRemains(StaffDao.list(tx.db).map { if (it.id == id) after else it })
                StaffDao.update(tx, before, name.trim(), roleId, active, now)
            }
            AuditDao.log(tx, AuditAction.STAFF_CHANGE, actor.staffId, now, Entity.STAFF, id, detail = describe(name, role, active), approvedBy = actor.approvedBy)
            id
        }
        graph.staff.reload()
        return id
    }

    suspend fun delete(staffId: Long, approval: Approval? = null) {
        val actor = graph.permissions.actor(Perm.MANAGE_STAFF, approval)
        if (staffId == graph.staff.staffId) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        graph.db().write(reserveIds = 2L) { tx ->
            val now = System.currentTimeMillis()
            val all = StaffDao.list(tx.db)
            val s = all.firstOrNull { it.id == staffId } ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (s.isOwner) requireOwner(tx, actor)
            checkOwnerRemains(all.filter { it.id != staffId })
            StaffDao.delete(tx, staffId, now)
            AuditDao.log(tx, AuditAction.STAFF_CHANGE, actor.staffId, now, Entity.STAFF, staffId, detail = "removed: ${s.name}", approvedBy = actor.approvedBy)
        }
        graph.staff.reload()
    }

    /** Result of setting a PIN: [recoveryCode] is shown once, when an owner set the store's first PIN. */
    data class PinSet(val recoveryCode: String?)

    /**
     * Sets ([pin]) or removes (null) a staff member's PIN. The store's first PIN signs its
     * owner in, turns PIN login on and creates the recovery code.
     */
    suspend fun setPin(staffId: Long, pin: String?, approval: Approval? = null): PinSet {
        val actor = graph.permissions.actor(Perm.MANAGE_STAFF, approval)
        if (pin != null) require(PinHash.validPin(pin)) { "PIN must be 4 to 6 digits" }
        val record = pin?.let { withContext(Dispatchers.Default) { PinHash.create(it) } }
        val wasRequired = graph.staff.state.value.loginRequired
        var code: String? = null
        val staff = graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            val all = StaffDao.list(tx.db)
            val s = all.firstOrNull { it.id == staffId } ?: throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            if (s.isOwner) requireOwner(tx, actor)
            val after = all.map { if (it.id == staffId) it.copy(pin = record) else it }
            if (record != null && !s.isOwner && after.none { it.isOwner && it.canSignIn }) {
                throw ActionRefused(ActionRefused.Reason.OWNER_PIN_FIRST)
            }
            checkOwnerRemains(after)
            StaffDao.setPin(tx, staffId, record, now)
            graph.staff.clearFails(tx, staffId) // a new PIN: wrong guesses at the old one no longer count
            AuditDao.log(
                tx, AuditAction.STAFF_CHANGE, actor.staffId, now, Entity.STAFF, staffId,
                detail = if (record == null) "PIN removed: ${s.name}" else "PIN set: ${s.name}", approvedBy = actor.approvedBy,
            )
            if (record != null && s.isOwner && recoveryHash(tx) == null) {
                code = RecoveryCode.generate()
                SettingsDao.put(tx, SettingKeys.OWNER_RECOVERY, PinHash.create(RecoveryCode.normalize(code ?: "")), now)
            }
            s.copy(pin = record)
        }
        graph.staff.reload()
        // The owner who just typed the store's first PIN is the one using the till.
        if (!wasRequired && record != null && staff.canSignIn && graph.staff.state.value.current == null) graph.staff.adopt(staff)
        return PinSet(code)
    }

    /** The signed-in staff member changes their own PIN, proving the old one. */
    suspend fun changeOwnPin(oldPin: String, newPin: String): StaffSession.Check {
        require(PinHash.validPin(newPin)) { "PIN must be 4 to 6 digits" }
        val me = graph.staff.state.value.current ?: throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        val c = graph.staff.check(me.id, oldPin, 0L)
        if (c !is StaffSession.Check.Ok) return c
        val record = withContext(Dispatchers.Default) { PinHash.create(newPin) }
        graph.db().write(reserveIds = 2L) { tx ->
            val now = System.currentTimeMillis()
            StaffDao.setPin(tx, me.id, record, now)
            AuditDao.log(tx, AuditAction.STAFF_CHANGE, me.id, now, Entity.STAFF, me.id, detail = "own PIN changed")
        }
        return c
    }

    /** A new owner recovery code (the old one stops working). Owners only. */
    suspend fun newRecoveryCode(): String {
        val s = graph.staff.state.value
        if (s.loginRequired && s.current?.isOwner != true) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
        val code = RecoveryCode.generate()
        val hash = withContext(Dispatchers.Default) { PinHash.create(RecoveryCode.normalize(code)) }
        graph.db().write(reserveIds = 1L) { tx ->
            val now = System.currentTimeMillis()
            SettingsDao.put(tx, SettingKeys.OWNER_RECOVERY, hash, now)
            AuditDao.log(tx, AuditAction.STAFF_CHANGE, graph.staff.staffId, now, detail = "new owner recovery code")
        }
        return code
    }

    suspend fun hasRecoveryCode(): Boolean = graph.db().read { SettingsDao.all(it)[SettingKeys.OWNER_RECOVERY] } != null

    /**
     * Forgotten owner PIN: with the recovery code, owner [ownerId] gets [newPin] and is signed
     * in. Returns false for a wrong code.
     */
    suspend fun recover(ownerId: Long, code: String, newPin: String): Boolean {
        require(PinHash.validPin(newPin)) { "PIN must be 4 to 6 digits" }
        val stored = graph.db().read { SettingsDao.all(it)[SettingKeys.OWNER_RECOVERY] }
        val ok = withContext(Dispatchers.Default) { PinHash.verify(RecoveryCode.normalize(code), stored) }
        if (!ok) return false
        val record = withContext(Dispatchers.Default) { PinHash.create(newPin) }
        val owner = graph.db().write(reserveIds = 2L) { tx ->
            val now = System.currentTimeMillis()
            val s = StaffDao.get(tx.db, ownerId)?.takeIf { it.isOwner && it.active } ?: throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
            StaffDao.setPin(tx, ownerId, record, now)
            graph.staff.clearFails(tx, ownerId) // the wait of a forgotten PIN ends with the recovery code
            AuditDao.log(tx, AuditAction.OWNER_PIN_RESET, ownerId, now, Entity.STAFF, ownerId)
            s.copy(pin = record)
        }
        graph.staff.reload()
        graph.staff.adopt(owner)
        return true
    }

    /** Adds ([before] = null) or edits a role. The owner role's permissions are fixed. */
    suspend fun saveRole(before: Role?, name: String, perms: Long, approval: Approval? = null): Long {
        val actor = graph.permissions.actor(Perm.MANAGE_STAFF, approval)
        require(name.isNotBlank()) { "name required" }
        val id = graph.db().write(reserveIds = 4L) { tx ->
            val now = System.currentTimeMillis()
            val id = if (before == null) {
                RoleDao.insert(tx, name.trim(), perms, now)
            } else {
                RoleDao.update(tx, before, name.trim(), if (before.isOwner) before.perms else perms, now)
                before.id
            }
            AuditDao.log(tx, AuditAction.ROLE_CHANGE, actor.staffId, now, Entity.ROLE, id, perms, name.trim(), actor.approvedBy)
            id
        }
        graph.staff.reload()
        return id
    }

    suspend fun deleteRole(role: Role, approval: Approval? = null) {
        val actor = graph.permissions.actor(Perm.MANAGE_STAFF, approval)
        if (role.sysRole != SysRole.NONE) throw ActionRefused(ActionRefused.Reason.SEED_ROLE)
        graph.db().write(reserveIds = 2L) { tx ->
            if (RoleDao.staffCount(tx.db, role.id) > 0L) throw ActionRefused(ActionRefused.Reason.ROLE_IN_USE)
            val now = System.currentTimeMillis()
            RoleDao.delete(tx, role.id, now)
            AuditDao.log(tx, AuditAction.ROLE_CHANGE, actor.staffId, now, Entity.ROLE, role.id, detail = "removed: ${role.name}", approvedBy = actor.approvedBy)
        }
    }

    /**
     * Owners are managed by owners only: making someone an owner, or changing an owner's role,
     * active flag or PIN, or removing them, needs an owner doing it (or approving it for someone
     * whose role cannot manage staff). With "Manage staff" alone a manager made themselves owner,
     * renewed the recovery code and demoted the real owner (2026-10 review). While nobody has a PIN
     * the till runs as the owner (D-037).
     */
    private fun requireOwner(tx: Db.Tx, actor: Actor) {
        if (!graph.staff.state.value.loginRequired) return
        val who = StaffDao.get(tx.db, actor.approvedBy ?: actor.staffId)
        if (who == null || who.deleted || !who.isOwner) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
    }

    /** While anyone can sign in, an owner must be able to (else nobody could manage staff). */
    private fun checkOwnerRemains(after: List<Staff>) {
        if (after.any { it.canSignIn } && after.none { it.isOwner && it.canSignIn }) throw ActionRefused(ActionRefused.Reason.LAST_OWNER)
    }

    private fun recoveryHash(tx: Db.Tx): String? = SettingsDao.all(tx.db)[SettingKeys.OWNER_RECOVERY]

    private fun describe(name: String, role: Role, active: Boolean) = "${name.trim()} (${role.name}${if (active) "" else ", inactive"})"
}
