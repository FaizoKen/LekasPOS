package com.lekaspos.core.csv

import java.io.BufferedReader
import java.io.Reader

/**
 * RFC 4180 CSV writing (D-041): fields with the delimiter, quotes or line breaks are quoted,
 * quotes doubled; rows end with CRLF (what spreadsheet programs expect). Exports start with
 * a UTF-8 byte-order mark ([BOM]) so Excel shows Malay and Chinese text correctly.
 *
 * Text that a spreadsheet would run as a formula (a customer named `=HYPERLINK(…)`, say) gets
 * a leading `'`; numbers, negative ones too, are written as they are ([defuse]).
 */
class CsvWriter(private val out: Appendable, private val delimiter: Char = ',') {

    var rows: Long = 0L
        private set

    fun row(fields: List<String?>) {
        for ((i, f) in fields.withIndex()) {
            if (i > 0) out.append(delimiter)
            field(f ?: "")
        }
        out.append("\r\n")
        rows++
    }

    fun row(vararg fields: String?) = row(fields.asList())

    private fun field(text: String) {
        val s = defuse(text)
        val quote = s.isNotEmpty() && (
            s[0] == ' ' || s[s.length - 1] == ' ' ||
                s.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
            )
        if (!quote) {
            out.append(s)
            return
        }
        out.append('"')
        for (c in s) {
            if (c == '"') out.append('"')
            out.append(c)
        }
        out.append('"')
    }

    companion object {
        /** U+FEFF, built from its code point (a literal would put an invisible character in the source). */
        val BOM: Char = Char(0xFEFF)

        private val NUMBER = Regex("[+-]?[0-9][0-9.,]*%?")

        /** [s] with a `'` in front when a spreadsheet would read it as a formula. */
        fun defuse(s: String): String {
            if (s.isEmpty()) return s
            val risky = when (s[0]) {
                '=', '@', '\t', '\r' -> true
                '+', '-' -> !NUMBER.matches(s)
                else -> false
            }
            return if (risky) "'$s" else s
        }

        /** The text [defuse] was given back (a file exported here, edited and imported again). */
        fun undefuse(s: String): String =
            if (s.length >= 2 && s[0] == '\'' && s[1] in "=@+-\t\r" && defuse(s.substring(1)) == s) s.substring(1) else s
    }
}

/**
 * Streaming CSV reader: one record at a time (files of any size, constant memory). Accepts
 * CRLF, LF or CR line ends, quoted fields with doubled quotes and line breaks, a leading
 * UTF-8 BOM, and a comma, semicolon or tab delimiter detected from the header line. A record
 * longer than [MAX_RECORD] characters (a crafted file, or a quote never closed) stops the read.
 */
class CsvReader(reader: Reader, delimiter: Char? = null) {

    private val input: Reader = if (reader.markSupported()) reader else BufferedReader(reader, 16 * 1024)

    class Malformed(val line: Int, message: String) : Exception("line $line: $message")

    private var peeked = -2
    private var line = 1

    /** 1-based line where the record returned last started (for error messages). */
    var recordLine: Int = 0
        private set

    private var delim: Char = delimiter ?: '\u0000'
    private var first = true

    /** The next record, or null at the end of the input. Blank lines (also separators only) are skipped. */
    fun next(): List<String>? {
        while (true) {
            if (first) {
                first = false
                if (peek() == BOM_CODE) read()
            }
            if (peek() == -1) return null
            recordLine = line
            val fields = record()
            // A blank line, or one of separators only: Excel writes ",,,,," for formatted empty rows, and
            // each became "name missing, price missing", burying the real problems (2026-10 review).
            if (fields.all { it.isBlank() }) continue
            return fields
        }
    }

    /** Characters of the record being read (fields and delimiters). */
    private var recordChars = 0

    private fun counted() {
        if (++recordChars > MAX_RECORD) throw Malformed(recordLine, "line too long")
    }

    private fun record(): List<String> {
        if (delim == '\u0000') delim = detect()
        recordChars = 0
        val fields = ArrayList<String>(16)
        val sb = StringBuilder(32)
        while (true) {
            val c = peek()
            if (c == '"'.code && sb.isEmpty()) {
                read()
                quoted(sb)
                val after = peek()
                if (after != -1 && after != delim.code && after != '\n'.code && after != '\r'.code) {
                    throw Malformed(line, "text after a closing quote")
                }
                continue
            }
            when (c) {
                -1 -> {
                    fields.add(sb.toString())
                    return fields
                }
                '\r'.code, '\n'.code -> {
                    endOfLine()
                    fields.add(sb.toString())
                    return fields
                }
                delim.code -> {
                    read()
                    counted()
                    fields.add(sb.toString())
                    sb.setLength(0)
                    if (fields.size > MAX_FIELDS) throw Malformed(recordLine, "too many columns")
                }
                else -> {
                    read()
                    counted()
                    sb.append(c.toChar())
                    if (sb.length > MAX_FIELD) throw Malformed(recordLine, "field too long")
                }
            }
        }
    }

    private fun quoted(sb: StringBuilder) {
        val start = line
        while (true) {
            val c = read()
            when (c) {
                -1 -> throw Malformed(start, "quote not closed")
                '"'.code -> {
                    if (peek() == '"'.code) {
                        read()
                        sb.append('"')
                    } else {
                        return
                    }
                }
                '\r'.code -> {
                    if (peek() == '\n'.code) read()
                    line++
                    sb.append('\n')
                }
                '\n'.code -> {
                    line++
                    sb.append('\n')
                }
                else -> sb.append(c.toChar())
            }
            counted()
            if (sb.length > MAX_FIELD) throw Malformed(start, "field too long")
        }
    }

    private fun endOfLine() {
        if (read() == '\r'.code && peek() == '\n'.code) read()
        line++
    }

    /** Delimiter of the first line: the most frequent of , ; and tab outside quotes (comma by default). */
    private fun detect(): Char {
        val counts = IntArray(3)
        var inQuotes = false
        fun count(c: Int) {
            when {
                c == '"'.code -> inQuotes = !inQuotes
                inQuotes -> Unit
                c == ','.code -> counts[0]++
                c == ';'.code -> counts[1]++
                c == '\t'.code -> counts[2]++
            }
        }
        // The character already peeked counts too; the rest is read ahead and then reset.
        val p = peek()
        if (p == -1 || p == '\n'.code || p == '\r'.code) return ','
        count(p)
        input.mark(LOOKAHEAD)
        var n = 0
        while (n < LOOKAHEAD) {
            val c = input.read()
            if (c == -1 || (!inQuotes && (c == '\n'.code || c == '\r'.code))) break
            count(c)
            n++
        }
        input.reset()
        return when {
            counts[1] > counts[0] && counts[1] >= counts[2] -> ';'
            counts[2] > counts[0] && counts[2] > counts[1] -> '\t'
            else -> ','
        }
    }

    private fun peek(): Int {
        if (peeked == -2) peeked = input.read()
        return peeked
    }

    private fun read(): Int {
        val c = peek()
        peeked = -2
        return c
    }

    companion object {
        const val MAX_FIELD = 64 * 1024

        /** Longest record (all its fields and delimiters), in characters. */
        const val MAX_RECORD = 64 * 1024
        private const val BOM_CODE = 0xFEFF
        const val MAX_FIELDS = 512
        private const val LOOKAHEAD = 8 * 1024
    }
}
