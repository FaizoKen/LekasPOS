package com.lekaspos.core.csv

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** 2026-10 review: how an imported file is read (Excel files, a few broken bytes in UTF-8). */
class CsvInputTest {

    private fun detect(bytes: ByteArray) = CsvInput.detect(ByteArrayInputStream(bytes))

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun excelFilesAreRecognisedByTheirFirstBytes() {
        val xlsx = bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00) + ByteArray(100)
        val xls = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) + ByteArray(100)
        assertTrue(detect(xlsx).spreadsheet)
        assertTrue(detect(xls).spreadsheet)
        assertFalse(detect("name,price\nPK,1.00\n".toByteArray()).spreadsheet)
        assertFalse(detect(bytes(0x50, 0x4B)).spreadsheet) // too short to say
        assertFalse(detect(ByteArray(0)).spreadsheet)
    }

    @Test
    fun aFewBrokenBytesKeepAUtf8FileUtf8() {
        // 40 names with "é" (valid UTF-8) and one stray Windows byte: was read as Windows-1252,
        // every "é" becoming "Ã©".
        val good = (1..40).joinToString("") { "Nescafé $it,5.00\n" }.toByteArray(Charsets.UTF_8)
        val file = "name,price\n".toByteArray() + good + "Caf".toByteArray() + bytes(0xE9) + ",3.00\n".toByteArray()
        val d = detect(file)
        assertEquals(Charsets.UTF_8, d.charset)
        assertTrue(d.lossy)
        assertEquals(40L, d.validSequences)
        assertEquals(1L, d.invalidSequences)
        // Read as UTF-8, the broken byte is U+FFFD and the rest is intact.
        val text = InputStreamReader(ByteArrayInputStream(file), d.charset).readText()
        assertTrue(text.contains("Nescafé 40,5.00"))
        assertTrue(text.contains("Caf�,3.00"))
    }

    @Test
    fun aWindowsFileStaysWindows1252() {
        val cp1252 = CsvInput.WINDOWS_1252
        val file = "name,price\nNescafé,5.00\nCrème brûlée,8.00\nJalapeño,2.00\n".toByteArray(cp1252)
        val d = detect(file)
        assertEquals(cp1252, d.charset)
        assertFalse(d.lossy)
        assertEquals(0L, d.validSequences)
        // A Windows-1252 accented capital followed by a symbol happens to be valid UTF-8 ("É®"),
        // but it is far from enough to make the file UTF-8.
        val mixed = "NESCAFÉ®,5.00\nCafé,1.00\nPâté,2.00\n".toByteArray(cp1252)
        assertEquals(cp1252, detect(mixed).charset)
    }

    @Test
    fun theShareOfValidSequencesDecides() {
        assertTrue(CsvInput.isUtf8(0, 0))
        assertTrue(CsvInput.isUtf8(5, 0))
        assertTrue(CsvInput.isUtf8(19, 1)) // 95 %
        assertFalse(CsvInput.isUtf8(18, 1))
        assertFalse(CsvInput.isUtf8(0, 1))
    }

    @Test
    fun strictUtf8RulesAndSequencesAcrossBuffers() {
        // Overlong "/" (C0 AF), a UTF-16 surrogate (ED A0 80) and a code point above U+10FFFF are broken.
        assertEquals(2L, detect(bytes(0x41, 0xC0, 0xAF)).invalidSequences) // C0 and the lone AF
        for (broken in listOf(bytes(0xED, 0xA0, 0x80), bytes(0xF4, 0x90, 0x80, 0x80), bytes(0xE0, 0x80, 0x80))) {
            val d = detect(broken)
            assertEquals(0L, d.validSequences)
            assertTrue(d.invalidSequences > 0L)
        }
        // A 4-byte emoji, Chinese and Tamil are valid.
        val d = detect("🍜 牛奶 பால்".toByteArray(Charsets.UTF_8))
        assertEquals(0L, d.invalidSequences)
        assertEquals(Charsets.UTF_8, d.charset)
        // A character starting on the last byte of the first 64 KB buffer.
        val crossing = ByteArray(65_535) { 'a'.code.toByte() } + "é牛".toByteArray(Charsets.UTF_8)
        assertEquals(2L, detect(crossing).validSequences)
        assertEquals(0L, detect(crossing).invalidSequences)
        // Cut off by the end of the file.
        assertEquals(1L, detect("abc".toByteArray() + bytes(0xE7, 0x89)).invalidSequences)
    }

    @Test
    fun utf16WithItsByteOrderMark() {
        val le = bytes(0xFF, 0xFE) + "name\tprice\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, detect(le).charset)
        val be = bytes(0xFE, 0xFF) + "name\tprice\n".toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, detect(be).charset)
    }
}
