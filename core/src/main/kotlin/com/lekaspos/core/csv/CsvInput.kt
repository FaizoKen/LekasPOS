package com.lekaspos.core.csv

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * What an imported file is, judged from its bytes in one streaming pass of constant memory
 * (D-041, 2026-10 review):
 *  - an Excel or other spreadsheet file (ZIP `PK\x03\x04`: .xlsx/.ods; OLE2 `D0 CF 11 E0`: .xls),
 *    which the import refuses with a message saying how to save it as CSV;
 *  - UTF-16 when it starts with a UTF-16 byte-order mark (Excel's "Unicode Text");
 *  - UTF-8 when its non-ASCII byte sequences are (almost all) valid UTF-8 — the few broken ones
 *    are read as U+FFFD and the preview says so; otherwise Windows-1252 (Excel's "CSV" on
 *    Windows). One stray byte used to switch a whole UTF-8 file to Windows-1252 and garble every
 *    Malay, Chinese and Tamil name in it.
 */
object CsvInput {

    /** The file is a spreadsheet (Excel …), not CSV text. */
    class SpreadsheetFile : IOException("an Excel or other spreadsheet file, not CSV")

    /**
     * [validSequences] and [invalidSequences] count non-ASCII UTF-8 sequences (0 for UTF-16).
     * [lossy]: read as UTF-8 although some sequences are broken (they become U+FFFD).
     */
    data class Detected(
        val charset: Charset,
        val spreadsheet: Boolean = false,
        val validSequences: Long = 0L,
        val invalidSequences: Long = 0L,
    ) {
        val lossy: Boolean get() = charset == Charsets.UTF_8 && invalidSequences > 0L
    }

    /**
     * Share of valid sequences (percent) from which a file with broken bytes is still UTF-8. A
     * Windows-1252 file almost never forms valid UTF-8 pairs (that takes an accented capital
     * followed by a symbol such as "É™"), so 95 % is unambiguous while a UTF-8 file with a few
     * damaged bytes keeps its names.
     */
    const val UTF8_MIN_PERCENT = 95L

    /** Reads [input] to its end (the caller closes it). */
    fun detect(input: InputStream): Detected {
        val buf = ByteArray(64 * 1024)
        var n = readFully(input, buf)
        if (n <= 0) return Detected(Charsets.UTF_8)
        val b0 = buf[0].toInt() and 0xFF
        val b1 = if (n > 1) buf[1].toInt() and 0xFF else -1
        if (n >= 4 && isSpreadsheet(buf)) return Detected(Charsets.UTF_8, spreadsheet = true)
        if (b0 == 0xFF && b1 == 0xFE) return Detected(Charsets.UTF_16LE) // the CSV reader skips the BOM
        if (b0 == 0xFE && b1 == 0xFF) return Detected(Charsets.UTF_16BE)
        val v = Utf8Counter()
        while (n > 0) {
            v.feed(buf, n)
            n = input.read(buf, 0, buf.size)
        }
        v.end()
        val charset = if (isUtf8(v.valid, v.invalid)) Charsets.UTF_8 else WINDOWS_1252
        return Detected(charset, false, v.valid, v.invalid)
    }

    /** UTF-8 when no sequence is broken, or at least [UTF8_MIN_PERCENT] % of them are valid. */
    fun isUtf8(valid: Long, invalid: Long): Boolean =
        invalid == 0L || (valid > 0L && valid * 100L >= (valid + invalid) * UTF8_MIN_PERCENT)

    /** ZIP (.xlsx, .ods) or OLE2 (.xls) signature at the start of [head] (at least 4 bytes). */
    fun isSpreadsheet(head: ByteArray): Boolean {
        if (head.size < 4) return false
        fun at(i: Int) = head[i].toInt() and 0xFF
        val zip = at(0) == 0x50 && at(1) == 0x4B && at(2) == 0x03 && at(3) == 0x04
        val ole = at(0) == 0xD0 && at(1) == 0xCF && at(2) == 0x11 && at(3) == 0xE0
        return zip || ole
    }

    val WINDOWS_1252: Charset get() = Charset.forName("windows-1252")

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return if (total == 0) -1 else total
    }

    /**
     * Counts valid and broken multi-byte UTF-8 sequences, streaming (a sequence may cross the end
     * of a buffer). Overlong forms, surrogates and code points above U+10FFFF are broken, as a
     * strict decoder sees them.
     */
    private class Utf8Counter {
        var valid = 0L
        var invalid = 0L
        private var need = 0 // continuation bytes still expected
        private var lo = 0x80 // allowed range of the next continuation byte
        private var hi = 0xBF

        fun feed(buf: ByteArray, n: Int) {
            var i = 0
            while (i < n) {
                val b = buf[i].toInt() and 0xFF
                if (need > 0) {
                    if (b in lo..hi) {
                        lo = 0x80
                        hi = 0xBF
                        if (--need == 0) valid++
                        i++
                        continue
                    }
                    invalid++ // the sequence broke off: this byte starts afresh
                    need = 0
                    lo = 0x80
                    hi = 0xBF
                }
                when (b) {
                    in 0x00..0x7F -> Unit
                    in 0xC2..0xDF -> need = 1
                    0xE0 -> start(2, 0xA0, 0xBF)
                    in 0xE1..0xEC, 0xEE, 0xEF -> need = 2
                    0xED -> start(2, 0x80, 0x9F)
                    0xF0 -> start(3, 0x90, 0xBF)
                    in 0xF1..0xF3 -> need = 3
                    0xF4 -> start(3, 0x80, 0x8F)
                    else -> invalid++ // a lone continuation byte, C0, C1, F5..FF
                }
                i++
            }
        }

        private fun start(n: Int, low: Int, high: Int) {
            need = n
            lo = low
            hi = high
        }

        /** A sequence cut off by the end of the file is broken. */
        fun end() {
            if (need > 0) invalid++
            need = 0
        }
    }
}
