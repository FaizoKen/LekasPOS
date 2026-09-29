package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase

/** One schema step. Runs inside the open-helper transaction; SQL must work on SQLite 3.8.4. */
class Migration(val from: Int, val to: Int, val migrate: (SQLiteDatabase) -> Unit) {
    init {
        require(to == from + 1) { "migrations go one version at a time" }
    }
}

object Migrations {
    /**
     * Ordered steps. Never edit a step that shipped; add a new one. Column changes use the
     * table-rebuild pattern (no RENAME/DROP COLUMN on API 21) — see references/database.md §9.
     */
    val ALL: List<Migration> = listOf(
        // v1 → v2 (Phase 3, D-034): count sessions, expected qty and cost on counts, movement log index.
        Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE stock_count ADD COLUMN expected INTEGER")
            db.execSQL("ALTER TABLE stock_count ADD COLUMN unit_cost INTEGER")
            db.execSQL(
                "CREATE TABLE count_session (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                    "status INTEGER NOT NULL DEFAULT 0, category_id INTEGER, started_at INTEGER NOT NULL, " +
                    "finished_at INTEGER, staff_id INTEGER, note TEXT, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, ver_hlc INTEGER NOT NULL, " +
                    "ver_dev INTEGER NOT NULL, fver TEXT)",
            )
            db.execSQL("CREATE INDEX count_session_started ON count_session(started_at) WHERE deleted = 0")
            db.execSQL("CREATE INDEX stock_count_session ON stock_count(session_id, hlc)")
            db.execSQL("CREATE INDEX stock_movement_hlc ON stock_movement(hlc)")
        },
        // v2 → v3 (Phase 4, D-040): shift of credit repayments, voids per shift, default role permissions.
        Migration(2, 3) { db ->
            db.execSQL("ALTER TABLE credit_entry ADD COLUMN shift_id INTEGER")
            db.execSQL("CREATE INDEX credit_entry_shift ON credit_entry(shift_id) WHERE shift_id IS NOT NULL")
            db.execSQL("CREATE INDEX sale_void_shift ON sale_void(shift_id) WHERE shift_id IS NOT NULL")
            // Seed roles nobody edited get their Phase 4 defaults (frozen values of Perm.DEFAULT_MANAGER / _CASHIER).
            db.execSQL("UPDATE role SET perms = 129919 WHERE id = 2 AND ver_hlc = 0 AND perms = 0")
            db.execSQL("UPDATE role SET perms = 49184 WHERE id = 3 AND ver_hlc = 0 AND perms = 0")
        },
        // v3 → v4 (Phase 5, D-043): per-month product totals, filled from the per-day ones;
        // unedited manager role gets the new REPORTS permission (1 shl 17).
        Migration(3, 4) { db ->
            db.execSQL(
                "CREATE TABLE sum_month_product (month INTEGER NOT NULL, product_id INTEGER NOT NULL, " +
                    "category_id INTEGER, qty INTEGER NOT NULL DEFAULT 0, net_ex INTEGER NOT NULL DEFAULT 0, " +
                    "tax INTEGER NOT NULL DEFAULT 0, cost INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (month, product_id)) WITHOUT ROWID",
            )
            db.execSQL(
                "INSERT INTO sum_month_product(month, product_id, category_id, qty, net_ex, tax, cost) " +
                    "SELECT CAST(strftime('%Y%m', day * 86400, 'unixepoch') AS INTEGER), product_id, MIN(category_id), " +
                    "SUM(qty), SUM(net_ex), SUM(tax), SUM(cost) FROM sum_day_product GROUP BY 1, product_id",
            )
            db.execSQL("UPDATE role SET perms = perms | 131072 WHERE id = 2 AND ver_hlc = 0")
        },
        // v4 → v5 (Phase 6, D-045): LOCAL bookkeeping of sync segments and import cursors.
        Migration(4, 5) { db ->
            db.execSQL(
                "CREATE TABLE sync_segment (seq INTEGER PRIMARY KEY, count INTEGER NOT NULL, first_hlc INTEGER NOT NULL, " +
                    "last_hlc INTEGER NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                    "uploaded_at INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE sync_cursor (dev INTEGER PRIMARY KEY, seq INTEGER NOT NULL, last_hlc INTEGER NOT NULL DEFAULT 0, " +
                    "updated_at INTEGER NOT NULL)",
            )
        },
    )

    fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
        var v = from
        while (v < to) {
            val step = ALL.firstOrNull { it.from == v }
                ?: throw IllegalStateException("No migration from schema v$v (target v$to)")
            step.migrate(db)
            v = step.to
        }
    }
}
