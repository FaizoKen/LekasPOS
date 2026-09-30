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

    /**
     * Cheap syntax guard (the JVM cannot run SQLite): a lost column template left a trailing comma
     * in v6's first `promotion` DDL, and the app failed on every device before the emulators ran.
     */
    @Test
    fun statementsHaveNoObviousSyntaxSlips() {
        val trailingComma = Regex(""",\s*\)""")
        for (s in Schema.STATEMENTS) {
            assertTrue(!trailingComma.containsMatchIn(s), "trailing comma before ')': $s")
            assertEquals(s.count { it == '(' }, s.count { it == ')' }, "unbalanced brackets in: $s")
        }
        for (lww in Schema.LWW_TABLES.filter { it != "setting" }) {
            val ddl = Schema.normalize(Schema.STATEMENTS.first { it.startsWith("CREATE TABLE $lww ") })
            for (col in listOf("deleted", "ver_hlc", "ver_dev", "fver")) {
                assertTrue(Regex("""[(,] ?$col """).containsMatchIn(ddl), "$lww lacks the LWW column $col")
            }
        }
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
