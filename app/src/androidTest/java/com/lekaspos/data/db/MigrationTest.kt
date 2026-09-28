package com.lekaspos.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
