package com.lekaspos.domain

import com.lekaspos.core.model.Perm
import com.lekaspos.data.db.Seed

/**
 * Who is using the till. Until PIN login and roles arrive (Phase 4, D-028) this is always the
 * store owner, who may do everything; every sensitive action already goes through
 * [PermissionGate] and writes an audit entry, so Phase 4 only changes this class.
 */
class StaffSession {
    val staffId: Long = Seed.Ids.STAFF_OWNER
    val perms: Long = Perm.ALL
}

class PermissionGate(private val session: StaffSession) {
    fun allowed(perm: Long): Boolean = session.perms and perm == perm
}
