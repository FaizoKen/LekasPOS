package com.lekaspos.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Creates/migrates the database. WAL + synchronous=FULL (D-010): a committed sale survives a
 * power cut. Opening happens off the main thread (Db.open is blocking).
 */
class DbOpenHelper(
    context: Context,
    name: String,
    private val seedNames: SeedNames,
    private val clock: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context, name, null, Schema.VERSION, KeepDamagedDatabase) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in Schema.STATEMENTS) db.execSQL(sql)
        val now = clock()
        Seed.insert(db, seedNames, now)
        Meta.createIdentity(db, now)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Migrations.migrate(db, oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Database is from a newer app version (v$oldVersion > v$newVersion). Update the app or restore a backup.",
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        // Foreign keys only after onCreate/onUpgrade: a migration that rebuilds a table (create new,
        // copy, drop old) inside the upgrade transaction cannot turn them off, and dropping the old
        // `sale` would cascade-delete every sale line (references/database.md §9).
        db.setForeignKeyConstraintsEnabled(true)
        // These run on the primary (writing) connection, which is the one that commits.
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("PRAGMA cache_size=-4000")
        db.pragma("PRAGMA journal_size_limit=4194304")
    }
}
