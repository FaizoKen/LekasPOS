package com.lekaspos.ui.common

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import kotlin.test.assertEquals
import org.junit.Test

/** 2026-10 review: the character set of an imported file is decided by the whole file. */
class CsvCharsetTest {

    private fun charset(bytes: ByteArray): Charset = CsvFiles.charsetOf(ByteArrayInputStream(bytes))

    private fun ascii(n: Int) = ByteArray(n) { (if (it % 40 == 39) '\n' else 'a').code.toByte() }

    @Test
    fun aWindowsByteFarIntoTheFileMakesItWindows1252() {
        // 40 KB of plain rows, then "Nescafé" saved by Excel as Windows-1252 (é = E9).
        assertEquals(Charset.forName("windows-1252"), charset(ascii(40_000) + byteArrayOf(0x4E, 0xE9.toByte(), 0x0A)))
    }

    @Test
    fun utf8IsKeptAlsoWhenACharacterCrossesAChunk() {
        val bytes = ascii(65_535) + "é 牛奶\n".toByteArray(Charsets.UTF_8) // é starts on the last byte of the first chunk
        assertEquals(Charsets.UTF_8, charset(bytes))
        assertEquals(Charsets.UTF_8, charset(ByteArray(0)))
        assertEquals(Charsets.UTF_8, charset(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "name,price\n".toByteArray()))
    }

    @Test
    fun excelUnicodeTextIsUtf16() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "name\tprice\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, charset(le))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "name\tprice\n".toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, charset(be))
    }

    @Test
    fun aCutOffCharacterAtTheVeryEndIsNotUtf8() {
        assertEquals(Charset.forName("windows-1252"), charset("abc".toByteArray() + byteArrayOf(0xC3.toByte())))
    }
}
