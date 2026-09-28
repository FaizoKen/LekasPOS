package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.PaymentKind
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
    }

    fun insert(db: SQLiteDatabase, names: SeedNames, now: Long) {
        fun role(id: Long, name: String, sysRole: Int) = db.execSQL(
            "INSERT INTO role(id, name, sys_role, perms, created_at, updated_at, ver_hlc, ver_dev) " +
                "VALUES(?, ?, ?, 0, ?, ?, 0, 0)",
            arrayOf<Any?>(id, name, sysRole, now, now),
        )
        role(Ids.ROLE_OWNER, names.owner, SysRole.OWNER)
        role(Ids.ROLE_MANAGER, names.manager, SysRole.MANAGER)
        role(Ids.ROLE_CASHIER, names.cashier, SysRole.CASHIER)

        fun method(id: Long, name: String, kind: Int, opensDrawer: Boolean, sort: Int) = db.execSQL(
            "INSERT INTO payment_method(id, name, kind, opens_drawer, sort, active, created_at, " +
                "updated_at, ver_hlc, ver_dev) VALUES(?, ?, ?, ?, ?, 1, ?, ?, 0, 0)",
            arrayOf<Any?>(id, name, kind, if (opensDrawer) 1 else 0, sort, now, now),
        )
        method(Ids.PM_CASH, names.cash, PaymentKind.CASH, true, 1)
        method(Ids.PM_CARD, names.card, PaymentKind.CARD, false, 2)
        method(Ids.PM_EWALLET, names.ewallet, PaymentKind.EWALLET, false, 3)
        method(Ids.PM_CREDIT, names.credit, PaymentKind.CREDIT, false, 4)
    }
}
