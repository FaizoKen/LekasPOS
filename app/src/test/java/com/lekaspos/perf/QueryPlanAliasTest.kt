package com.lekaspos.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Modern SQLite prints aliases in EXPLAIN QUERY PLAN ("SCAN r"); they must map back to tables. */
class QueryPlanAliasTest {

    @Test
    fun mapsAliasesBackToTables() {
        val m = QueryPlans.tableAliases(
            "SELECT l.id FROM sale_line l JOIN sale s ON s.id = l.sale_id " +
                "LEFT JOIN stock_level AS x ON x.product_id = l.product_id WHERE l.product_id = ?",
        )
        assertEquals("sale_line", m["l"])
        assertEquals("sale", m["s"])
        assertEquals("stock_level", m["x"])
        assertEquals("sale", m["sale"])
        assertNull(m["WHERE"])
        assertNull(m["ON"])
    }

    @Test
    fun ignoresKeywordsAfterUnaliasedTables() {
        val m = QueryPlans.tableAliases(
            "SELECT id FROM sale WHERE sold_at <= ? ORDER BY sold_at DESC LIMIT ?",
        )
        assertEquals(mapOf("sale" to "sale"), m)
    }

    @Test
    fun handlesSubqueriesAndCrossJoins() {
        val m = QueryPlans.tableAliases(
            "SELECT p.id FROM (SELECT docid FROM product_fts WHERE product_fts MATCH ? LIMIT 2000) f " +
                "CROSS JOIN product p ON p.id = f.docid LEFT JOIN stock_level s ON s.product_id = p.id",
        )
        assertEquals("product", m["p"])
        assertEquals("stock_level", m["s"])
        assertEquals("product_fts", m["product_fts"])
        assertNull(m["f"]) // a subquery, not a table
    }
}
