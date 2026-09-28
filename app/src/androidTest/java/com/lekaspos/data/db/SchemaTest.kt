package com.lekaspos.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.id.Ids
import com.lekaspos.testing.TestDb
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SchemaTest {

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = TestDb.fresh()
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    @Test
    fun walAndDurabilityPragmasAreActive() {
        db.readBlocking { r -> assertEquals("wal", r.pragma("PRAGMA journal_mode")?.lowercase()) }
        db.writeBlocking { tx ->
            assertEquals("2", tx.db.pragma("PRAGMA synchronous"), "synchronous must be FULL (2)")
            assertEquals("1", tx.db.pragma("PRAGMA foreign_keys"))
        }
    }

    @Test
    fun everyDeclaredTableExists() {
        val tables = db.readBlocking { r ->
            r.queryList("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(0) }.toSet()
        }
        for (t in Schema.LWW_TABLES + Schema.EVENT_TABLES + Schema.DERIVED_TABLES + Schema.LOCAL_TABLES) {
            assertTrue(t in tables, "missing table $t")
        }
    }

    @Test
    fun seedRowsAndIdentityExist() {
        db.readBlocking { r ->
            assertEquals(3L, r.long("SELECT COUNT(*) FROM role WHERE id IN (1, 2, 3) AND ver_hlc = 0"))
            assertEquals(4L, r.long("SELECT COUNT(*) FROM payment_method WHERE id BETWEEN 1 AND 4 AND ver_hlc = 0"))
            val deviceNo = Meta.getLong(r, Meta.DEVICE_NO)
            assertNotNull(deviceNo)
            assertTrue(deviceNo in 1L..Ids.MAX_DEVICE_NO.toLong())
            assertEquals(deviceNo.toInt(), db.deviceNo)
            assertTrue(!Meta.get(r, Meta.STORE_UUID).isNullOrEmpty())
        }
    }

    @Test
    fun fts4SimpleTokenizerWithPrefixIndexWorks() {
        db.writeBlocking { tx ->
            tx.insert("INSERT INTO product_fts(docid, body) VALUES(?, ?)", 11L, "milo activ go 1kg")
            tx.insert("INSERT INTO product_fts(docid, body) VALUES(?, ?)", 12L, "牛 奶 1l")
        }
        db.readBlocking { r ->
            fun match(q: String) = r.queryList("SELECT docid FROM product_fts WHERE product_fts MATCH ?", args(q)) { it.getLong(0) }
            assertEquals(listOf(11L), match("mi*"))
            assertEquals(listOf(11L), match("act* 1k*"))
            assertEquals(listOf(12L), match("奶*"))
            assertEquals(emptyList(), match("zz*"))
        }
    }

    @Test
    fun reportsSqliteVersion() {
        val version = db.readBlocking { it.stringOrNull("SELECT sqlite_version()") }
        assertNotNull(version)
        android.util.Log.i("LekasTest", "SQLite version on this device: $version")
    }
}
