package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SysRole

/** Localized names for seed rows, taken from resources at first launch. */
data class SeedNames(
    val owner: String = "Owner",
    val manager: String = "Manager",
    val cashier: String = "Cashier",
    val cash: String = "Cash",
    val card: String = "Card",
    val ewallet: String = "E-wallet / QR",
    val credit: String = "Customer credit",
)

/**
 * Rows every device creates identically: IDs with device_no 0 and version (0, 0), so they
 * merge cleanly across devices and any real edit wins (references/database.md §5).
 */
object Seed {
    object Ids {
        const val ROLE_OWNER = 1L
        const val ROLE_MANAGER = 2L
        const val ROLE_CASHIER = 3L
        const val PM_CASH = 1L
        const val PM_CARD = 2L
        const val PM_EWALLET = 3L
        const val PM_CREDIT = 4L

        /** The store owner (a seed row): the till runs as the owner until someone sets a PIN (D-037). */
        const val STAFF_OWNER = 1L
    }

    fun insert(db: SQLiteDatabase, names: SeedNames, now: Long) {
        fun role(id: Long, name: String, sysRole: Int, perms: Long) = db.execSQL(
            "INSERT INTO role(id, name, sys_role, perms, created_at, updated_at, ver_hlc, ver_dev) " +
                "VALUES(?, ?, ?, ?, ?, ?, 0, 0)",
            arrayOf<Any?>(id, name, sysRole, perms, now, now),
        )
        role(Ids.ROLE_OWNER, names.owner, SysRole.OWNER, 0L) // the owner role always has every permission
        role(Ids.ROLE_MANAGER, names.manager, SysRole.MANAGER, Perm.DEFAULT_MANAGER)
        role(Ids.ROLE_CASHIER, names.cashier, SysRole.CASHIER, Perm.DEFAULT_CASHIER)

        fun method(id: Long, name: String, kind: Int, opensDrawer: Boolean, sort: Int) = db.execSQL(
            "INSERT INTO payment_method(id, name, kind, opens_drawer, sort, active, created_at, " +
                "updated_at, ver_hlc, ver_dev) VALUES(?, ?, ?, ?, ?, 1, ?, ?, 0, 0)",
            arrayOf<Any?>(id, name, kind, if (opensDrawer) 1 else 0, sort, now, now),
        )
        method(Ids.PM_CASH, names.cash, PaymentKind.CASH, true, 1)
        method(Ids.PM_CARD, names.card, PaymentKind.CARD, false, 2)
        method(Ids.PM_EWALLET, names.ewallet, PaymentKind.EWALLET, false, 3)
        method(Ids.PM_CREDIT, names.credit, PaymentKind.CREDIT, false, 4)
        ensureOwner(db, names, now)
    }

    /**
     * Built-in rows never edited (version (0, 0), no field versions) take [names]: the language chosen
     * on the welcome screen, after they were made in the phone's language ("Cash", "Owner" on Malay
     * screens and receipts, 2026-10 review). Unversioned, like the seed itself. Writer thread.
     */
    fun renameUntouched(db: SQLiteDatabase, names: SeedNames) {
        fun rename(table: String, id: Long, name: String) = db.execSQL(
            "UPDATE $table SET name = ? WHERE id = ? AND ver_hlc = 0 AND ver_dev = 0 AND fver IS NULL",
            arrayOf<Any?>(name, id),
        )
        rename("role", Ids.ROLE_OWNER, names.owner)
        rename("role", Ids.ROLE_MANAGER, names.manager)
        rename("role", Ids.ROLE_CASHIER, names.cashier)
        rename("payment_method", Ids.PM_CASH, names.cash)
        rename("payment_method", Ids.PM_CARD, names.card)
        rename("payment_method", Ids.PM_EWALLET, names.ewallet)
        rename("payment_method", Ids.PM_CREDIT, names.credit)
        rename("staff", Ids.STAFF_OWNER, names.owner)
    }

    /**
     * Seed rows added after schema v1 shipped (Phase 2): created on open when missing, so
     * databases from Phase 1 builds get them without a schema migration.
     */
    fun ensureOwner(db: SQLiteDatabase, names: SeedNames, now: Long) {
        db.execSQL(
            "INSERT OR IGNORE INTO staff(id, name, role_id, active, deleted, created_at, updated_at, ver_hlc, ver_dev) " +
                "VALUES(?, ?, ?, 1, 0, ?, ?, 0, 0)",
            arrayOf<Any?>(Ids.STAFF_OWNER, names.owner, Ids.ROLE_OWNER, now, now),
        )
    }
}
