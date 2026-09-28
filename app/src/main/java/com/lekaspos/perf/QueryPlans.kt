package com.lekaspos.perf

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.data.db.DerivedRebuild
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.stock.StockDao

/**
 * Verifies with EXPLAIN QUERY PLAN (references/database.md §8):
 *  - hot queries never scan a large table, and paged lists are read in index order;
 *  - maintenance statements may scan, but never run a correlated subquery that scans a large
 *    table (O(n²) on SQLite 3.8, which ignores partial indexes inside correlated subqueries).
 * Works with both the SQLite 3.8 ("SCAN TABLE x") and the modern ("SCAN x") output formats.
 * Run it on the API 21 image: that is where the planner is weakest.
 */
object QueryPlans {

    /** Tables that grow with sales volume or catalogue size. */
    val LARGE_TABLES = setOf(
        "sale", "sale_line", "payment", "sale_void", "stock_movement", "stock_count", "audit_log", "credit_entry",
        "cash_movement", "product", "product_barcode", "sum_day_product", "purchase", "purchase_line",
    )

    /** Paged lists: must come out of an index in order (no temp B-tree sort). */
    private val INDEX_ORDERED = setOf(
        "history_first", "history_next", "product_history", "category_page", "search_prefix", "search_barcode_prefix",
    )

    fun hotQueries(): List<Pair<String, String>> =
        ProductDao.HOT_QUERIES + SaleDao.HOT_QUERIES + StockDao.HOT_QUERIES + ReportDao.HOT_QUERIES

    fun maintenanceQueries(): List<Pair<String, String>> = DerivedRebuild.MAINTENANCE_QUERIES

    private val SCAN = Regex("^SCAN (?:TABLE )?([A-Za-z_][A-Za-z0-9_]*)(.*)$")

    // Modern SQLite prints the alias ("SCAN r"), SQLite 3.8 the table ("SCAN TABLE sale AS r"),
    // so aliases are mapped back to their tables from the statement's FROM/JOIN clauses.
    private val FROM_JOIN = Regex(
        "\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*))?",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_ALIASES = setOf(
        "WHERE", "ON", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "JOIN", "NATURAL", "GROUP", "ORDER",
        "LIMIT", "UNION", "USING", "INDEXED", "NOT", "AND", "OR", "HAVING", "SET", "VALUES", "SELECT",
    )

    internal fun tableAliases(sql: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in FROM_JOIN.findAll(sql)) {
            val table = m.groupValues[1]
            val alias = m.groupValues[2]
            map[table] = table
            if (alias.isNotEmpty() && alias.uppercase() !in NOT_ALIASES) map[alias] = table
        }
        return map
    }

    fun check(db: SQLiteDatabase): List<PlanCheck> =
        hotQueries().map { (name, sql) -> check(db, name, sql, maintenance = false) } +
            maintenanceQueries().map { (name, sql) -> check(db, name, sql, maintenance = true) }

    fun check(db: SQLiteDatabase, name: String, sql: String, maintenance: Boolean = false): PlanCheck {
        val plan = db.rawQuery("EXPLAIN QUERY PLAN $sql", null).use { c ->
            val col = c.getColumnIndex("detail").takeIf { it >= 0 } ?: (c.columnCount - 1)
            val lines = ArrayList<String>()
            while (c.moveToNext()) lines.add(c.getString(col))
            lines
        }
        val violations = ArrayList<String>()
        val aliases = tableAliases(sql)
        var insideCorrelated = false // subquery plan lines follow their "CORRELATED ..." marker
        for (line in plan) {
            if (line.contains("CORRELATED")) insideCorrelated = true
            val m = SCAN.find(line.trim())
            if (m != null) {
                val table = aliases[m.groupValues[1]] ?: m.groupValues[1]
                val fullScan = table in LARGE_TABLES && !m.groupValues[2].contains("USING")
                if (fullScan && !maintenance) violations.add("full scan of $table: $line")
                if (fullScan && insideCorrelated) violations.add("correlated subquery scans $table: $line")
            }
            if (name in INDEX_ORDERED && line.contains("TEMP B-TREE") && line.contains("ORDER BY")) {
                violations.add("sorts instead of reading index order: $line")
            }
        }
        return PlanCheck(name, plan, violations)
    }
}
