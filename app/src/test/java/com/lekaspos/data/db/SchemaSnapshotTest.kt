package com.lekaspos.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards references/database.md §9: the DDL of the current schema version is frozen in
 * `src/androidTest/assets/schemas/<VERSION>.sql`. Changing Schema.kt without bumping VERSION
 * (and adding a migration) fails here. The instrumented MigrationTest uses the same files.
 */
class SchemaSnapshotTest {

    private val dir = File("src/androidTest/assets/schemas")

    private fun render(): String = Schema.STATEMENTS.joinToString(";\n", postfix = ";\n")

    @Test
    fun currentSchemaMatchesItsSnapshot() {
        val file = File(dir, "${Schema.VERSION}.sql")
        if (!file.exists()) {
            dir.mkdirs()
            file.writeText(render())
            fail("Created missing schema snapshot ${file.path} — review and commit it, then re-run.")
        }
        assertEquals(
            file.readText().replace("\r\n", "\n"), render(),
            "Schema.kt changed but schema v${Schema.VERSION} is frozen: bump Schema.VERSION, add a Migration and a new snapshot.",
        )
    }

    @Test
    fun everyTableHasExactlyOneSyncClass() {
        val created = Schema.STATEMENTS.mapNotNull {
            Regex("^CREATE (?:VIRTUAL )?TABLE (\\w+)").find(it)?.groupValues?.get(1)
        }
        val classified = Schema.LWW_TABLES + Schema.EVENT_TABLES + Schema.DERIVED_TABLES + Schema.LOCAL_TABLES
        assertEquals(classified.size, classified.toSet().size, "a table is listed in two sync classes")
        assertEquals(created.toSet(), classified.toSet(), "tables and sync classes differ")
    }

    @Test
    fun noForbiddenSqlForApi21() {
        val forbidden = listOf(" ON CONFLICT", "RETURNING", " OVER (", "IIF(", "FILTER (", "NULLS FIRST", "NULLS LAST", "fts5", "GENERATED ALWAYS")
        for (sql in Schema.STATEMENTS) {
            for (f in forbidden) assertTrue(!sql.contains(f, ignoreCase = true), "'$f' is not available on SQLite 3.8.4: $sql")
        }
    }

    @Test
    fun snapshotsExistForEveryVersion() {
        for (v in 1..Schema.VERSION) assertTrue(File(dir, "$v.sql").exists(), "missing schema snapshot $v.sql")
    }
}
