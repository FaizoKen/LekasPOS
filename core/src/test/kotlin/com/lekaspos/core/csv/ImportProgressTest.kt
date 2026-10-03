package com.lekaspos.core.csv

import java.io.StringReader
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test

/** 2026-10 review: an import Android ended half-way continues where it stopped. */
class ImportProgressTest {

    private fun reference(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_16BE)).joinToString("") { "%02x".format(it) }

    @Test
    fun theHashIsTheFilesTextReadInPieces() {
        assertEquals(reference("abc"), ImportProgress.sha256(StringReader("abc")))
        assertEquals(reference(""), ImportProgress.sha256(StringReader("")))
        val big = buildString { for (i in 1..5_000) append("Nescafé 牛奶 $i,5.00\r\n") } // many 8 K buffers
        assertEquals(reference(big), ImportProgress.sha256(StringReader(big)))
        assertNotEquals(ImportProgress.sha256(StringReader(big)), ImportProgress.sha256(StringReader(big + "x")))
    }

    @Test
    fun theRecordRoundTrips() {
        val hash = ImportProgress.sha256(StringReader("name,price\n"))
        val p = ImportProgress(hash, 400, 380, 15, 5, 300, 4_398_046_511_123L, null, resumedFrom = 200)
        assertEquals(p, ImportProgress.parse(p.format()))
        val logged = p.copy(logged = true, approvedBy = 7L, staffId = null)
        assertEquals(logged, ImportProgress.parse(logged.format()))
        for (junk in listOf(null, "", "x", "2;$hash;1;1;1;1;1;;;0;0", "1;abc;1;1;1;1;1;;;0;0", "1;$hash;-1;1;1;1;1;;;0;0", p.format() + ";1")) {
            assertNull(ImportProgress.parse(junk), junk)
        }
    }

    @Test
    fun theAuditTextSaysWhatHappened() {
        assertEquals("created 200, updated 0, skipped 3, stock set 150, stopped early", ImportProgress.detail(200, 0, 3, 150, 0, finished = false))
        assertEquals("created 50, updated 1, skipped 0, stock set 0, continued after row 200", ImportProgress.detail(50, 1, 0, 0, 200, finished = true))
        val p = ImportProgress("0".repeat(64), 400, 380, 15, 5, 0, null, null, resumedFrom = 200)
        assertEquals("created 380, updated 15, skipped 5, stock set 0, continued after row 200, stopped early", p.detail())
    }
}
