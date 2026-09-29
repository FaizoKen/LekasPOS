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
