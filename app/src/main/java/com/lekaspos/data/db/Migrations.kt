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
    val ALL: List<Migration> = emptyList()

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
