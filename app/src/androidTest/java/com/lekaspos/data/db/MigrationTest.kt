package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.Perm
import com.lekaspos.core.time.Days
import com.lekaspos.testing.TestDb
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * references/database.md §9: a database created from every frozen schema snapshot must, after
 * the app's migrations, have exactly the same schema as a fresh install.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private fun master(db: SQLiteDatabase): List<String> = db.queryList(
        "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
    ) { c -> "${c.getString(0)}|${c.getString(1)}|${c.getString(2)}|${c.stringOrNull(3)?.let(Schema::normalize)}" }

    private fun snapshot(version: Int): List<String> =
        TestDb.testContext.assets.open("schemas/$version.sql").bufferedReader().use { it.readText() }
            .split(";\n").map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun everySnapshotMigratesToTheFreshSchema() {
        val fresh = TestDb.fresh()
        val expected = master(fresh.sqlite)
        TestDb.delete(fresh)

        val versions = TestDb.testContext.assets.list("schemas").orEmpty()
            .filter { it.endsWith(".sql") }.map { it.removeSuffix(".sql").toInt() }.sorted()
        assertTrue(versions.contains(Schema.VERSION), "no snapshot for the current schema v${Schema.VERSION}")

        for (v in versions) {
            val name = "migrate-from-$v.db"
            val file = TestDb.context.getDatabasePath(name)
            SQLiteDatabase.deleteDatabase(file)
            file.parentFile?.mkdirs()
            val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                raw.beginTransaction()
                for (sql in snapshot(v)) raw.execSQL(sql)
                Meta.createIdentity(raw, System.currentTimeMillis())
                raw.setTransactionSuccessful()
                raw.endTransaction()
                raw.version = v
            } finally {
                raw.close()
            }
            val migrated = Db.open(TestDb.context, name) // runs onUpgrade v → current
            try {
                assertEquals(expected, master(migrated.sqlite), "schema after migrating from v$v differs from a fresh install")
            } finally {
                TestDb.delete(migrated)
            }
        }
    }

    @Test
    fun v3GivesUneditedSeedRolesTheirDefaultPermissions() {
        val name = "migrate-roles.db"
        val file = TestDb.context.getDatabasePath(name)
        SQLiteDatabase.deleteDatabase(file)
        file.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            raw.beginTransaction()
            for (sql in snapshot(2)) raw.execSQL(sql)
            Meta.createIdentity(raw, System.currentTimeMillis())
            // Phase 2/3 seed roles had no permissions; a custom role was edited (ver_hlc > 0).
            for (id in 1..3) {
                raw.execSQL("INSERT INTO role(id, name, sys_role, perms, created_at, updated_at, ver_hlc, ver_dev) VALUES($id, 'r$id', $id, 0, 0, 0, 0, 0)")
            }
            raw.execSQL("INSERT INTO role(id, name, sys_role, perms, created_at, updated_at, ver_hlc, ver_dev) VALUES(99, 'custom', 0, 0, 0, 0, 5, 1)")
            raw.execSQL("INSERT INTO credit_entry(id, customer_id, kind, amount, at, hlc) VALUES(7, 1, 1, 500, 0, 0)")
            // Per-day product totals from before v4: the migration fills the per-month table from them.
            for ((ymd, pid, qty) in listOf(Triple(20251231, 5L, 1_000L), Triple(20260101, 5L, 2_000L), Triple(20260131, 5L, 3_000L), Triple(20260101, 6L, 500L))) {
                raw.execSQL(
                    "INSERT INTO sum_day_product(day, product_id, category_id, qty, net_ex, tax, cost) VALUES(?, ?, 9, ?, ?, 0, 1)",
                    arrayOf<Any>(Days.fromYmd(ymd), pid, qty, qty / 10),
                )
            }
            raw.setTransactionSuccessful()
            raw.endTransaction()
            raw.version = 2
        } finally {
            raw.close()
        }
        val db = Db.open(TestDb.context, name)
        try {
            val perms = db.readBlocking { r -> r.queryList("SELECT id, perms FROM role ORDER BY id") { it.getLong(0) to it.getLong(1) } }.toMap()
            assertEquals(0L, perms[1L]) // the owner role always has everything
            assertEquals(Perm.DEFAULT_MANAGER, perms[2L])
            assertEquals(Perm.DEFAULT_CASHIER, perms[3L])
            assertEquals(0L, perms[99L]) // edited roles are left alone
            val shift = db.readBlocking { r -> r.queryList("SELECT shift_id FROM credit_entry WHERE id = 7") { it.isNull(0) } }
            assertEquals(listOf(true), shift) // old credit entries: no shift
            val months = db.readBlocking { r ->
                r.queryList("SELECT month, product_id, category_id, qty, net_ex, cost FROM sum_month_product ORDER BY month, product_id") {
                    (0 until 6).joinToString("|") { i -> it.getString(i) }
                }
            }
            assertEquals(listOf("202512|5|9|1000|100|1", "202601|5|9|5000|500|2", "202601|6|9|500|50|1"), months)
            // v7 (D-058): the per-year table, filled from the months.
            val years = db.readBlocking { r ->
                r.queryList("SELECT year, product_id, category_id, qty, net_ex, cost FROM sum_year_product ORDER BY year, product_id") {
                    (0 until 6).joinToString("|") { i -> it.getString(i) }
                }
            }
            assertEquals(listOf("2025|5|9|1000|100|1", "2026|5|9|5000|500|2", "2026|6|9|500|50|1"), years)
        } finally {
            TestDb.delete(db)
        }
    }
}
