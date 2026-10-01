package com.lekaspos.data.staff

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SysRole
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.LwwWriter

/** A role: a name and permission bits (`Perm`). Seed roles have a [sysRole]; the owner's is fixed. */
data class Role(val id: Long, val name: String, val sysRole: Int, val perms: Long) {
    val effective: Long get() = Perm.effective(sysRole, perms)
    val isOwner: Boolean get() = sysRole == SysRole.OWNER
}

/**
 * A staff member and their role. [pin] is the stored PIN record (`PinHash`, D-037); it never
 * leaves the domain layer.
 */
data class Staff(
    val id: Long,
    val name: String,
    val roleId: Long,
    val active: Boolean,
    val pin: String?,
    val roleName: String?,
    val sysRole: Int,
    val rolePerms: Long,
    /** Removed (tombstone): kept for history, never signed in. */
    val deleted: Boolean = false,
) {
    val hasPin: Boolean get() = pin != null
    val perms: Long get() = Perm.effective(sysRole, rolePerms)
    val isOwner: Boolean get() = sysRole == SysRole.OWNER

    /**
     * May sign in at the till. A removed staff member may not: removed on another till, they
     * stayed signed in here, and their PIN still approved actions (2026-10 review).
     */
    val canSignIn: Boolean get() = active && hasPin && !deleted
}

/** LWW table `staff`. The PIN record is one field (`pin_hash`); `pin_salt` stays unused (D-037). */
object StaffDao {
    private const val COLUMNS = "s.id, s.name, s.role_id, s.active, s.pin_hash, r.name, r.sys_role, r.perms, s.deleted"

    // A removed role gives no permissions (a role removed on one till while another gave it to someone).
    private const val FROM = "FROM staff s LEFT JOIN role r ON r.id = s.role_id AND r.deleted = 0"

    /** Few rows (a shop's staff): sorting them needs no index. */
    fun list(db: SQLiteDatabase): List<Staff> =
        db.queryList("SELECT $COLUMNS $FROM WHERE s.deleted = 0 ORDER BY s.name COLLATE NOCASE, s.id", null, ::row)

    fun get(db: SQLiteDatabase, id: Long): Staff? = db.queryOne("SELECT $COLUMNS $FROM WHERE s.id = ?", args(id), ::row)

    fun name(db: SQLiteDatabase, id: Long): String? =
        db.queryOne("SELECT name FROM staff WHERE id = ?", args(id)) { it.getString(0) }

    /** Every name, including removed staff (old audit entries and reports still show them). */
    fun names(db: SQLiteDatabase): Map<Long, String> {
        val out = HashMap<Long, String>()
        db.queryList("SELECT id, name FROM staff", null) { it.getLong(0) to it.getString(1) }.forEach { out[it.first] = it.second }
        return out
    }

    /** PIN login is on as soon as one active staff member has a PIN. */
    fun loginRequired(db: SQLiteDatabase): Boolean =
        db.long("SELECT COUNT(*) FROM staff WHERE deleted = 0 AND active = 1 AND pin_hash IS NOT NULL") > 0L

    /** Staff who could approve [perm] now: active, with a PIN and a role that has it. */
    fun approvers(db: SQLiteDatabase, perm: Long): List<Staff> = list(db).filter { it.canSignIn && Perm.has(it.perms, perm) }

    fun fields(name: String, roleId: Long, active: Boolean): LinkedHashMap<String, Any?> =
        linkedMapOf("name" to name, "role_id" to roleId, "active" to active)

    fun insert(tx: Db.Tx, name: String, roleId: Long, active: Boolean, pin: String?, now: Long): Long {
        val id = tx.nextId()
        val f = fields(name, roleId, active)
        f["pin_hash"] = pin
        LwwWriter.insert(tx, "staff", Entity.STAFF, id, f, now)
        return id
    }

    /** Writes only the changed fields of [before]. */
    fun update(tx: Db.Tx, before: Staff, name: String, roleId: Long, active: Boolean, now: Long) {
        val old = fields(before.name, before.roleId, before.active)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(name, roleId, active)) if (old[k] != v) changes[k] = v
        if (changes.isNotEmpty()) LwwWriter.update(tx, "staff", Entity.STAFF, before.id, changes, now)
    }

    /** [pin] = a `PinHash` record, or null to remove the PIN. */
    fun setPin(tx: Db.Tx, id: Long, pin: String?, now: Long) {
        LwwWriter.update(tx, "staff", Entity.STAFF, id, mapOf("pin_hash" to pin), now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "staff", Entity.STAFF, id, now)
    }

    private fun row(c: Cursor) = Staff(
        id = c.getLong(0),
        name = c.getString(1),
        roleId = c.getLong(2),
        active = c.bool(3),
        pin = c.stringOrNull(4),
        roleName = c.stringOrNull(5),
        sysRole = if (c.isNull(6)) SysRole.NONE else c.getInt(6),
        rolePerms = if (c.isNull(7)) 0L else c.getLong(7),
        deleted = c.bool(8),
    )
}

/** LWW table `role`. */
object RoleDao {
    private const val COLUMNS = "id, name, sys_role, perms"

    /** Seed roles (ids 1–3) first, then the owner's own roles in the order they were made. */
    fun list(db: SQLiteDatabase): List<Role> =
        db.queryList("SELECT $COLUMNS FROM role WHERE deleted = 0 ORDER BY id", null, ::row)

    fun get(db: SQLiteDatabase, id: Long): Role? = db.queryOne("SELECT $COLUMNS FROM role WHERE id = ?", args(id), ::row)

    fun insert(tx: Db.Tx, name: String, perms: Long, now: Long): Long {
        val id = tx.nextId()
        LwwWriter.insert(tx, "role", Entity.ROLE, id, linkedMapOf("name" to name, "sys_role" to SysRole.NONE, "perms" to perms), now)
        return id
    }

    fun update(tx: Db.Tx, before: Role, name: String, perms: Long, now: Long) {
        val changes = LinkedHashMap<String, Any?>()
        if (name != before.name) changes["name"] = name
        if (perms != before.perms) changes["perms"] = perms
        if (changes.isNotEmpty()) LwwWriter.update(tx, "role", Entity.ROLE, before.id, changes, now)
    }

    fun delete(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "role", Entity.ROLE, id, now)
    }

    fun staffCount(db: SQLiteDatabase, roleId: Long): Long =
        db.long("SELECT COUNT(*) FROM staff WHERE deleted = 0 AND role_id = ?", roleId)

    private fun row(c: Cursor) = Role(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3))
}
