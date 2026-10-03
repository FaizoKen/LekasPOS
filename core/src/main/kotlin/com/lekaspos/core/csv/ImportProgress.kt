package com.lekaspos.core.csv

import java.io.Reader
import java.security.MessageDigest

/**
 * How far a product import of one file got (2026-10 review). The app keeps it in `meta`, written
 * in the same transaction as each chunk of rows: when Android ends the app half-way, the next
 * import of the same file (same [hash]) continues after [rows] instead of creating the products
 * without barcode, SKU or number — and their opening stock — a second time. Cleared when the
 * import finishes. [logged]: the audit entry for the import that stopped is written.
 */
data class ImportProgress(
    /** SHA-256 (hex) of the file's text, see [sha256]. */
    val hash: String,
    /** Data rows (records after the header) done, counted from the start of the file. */
    val rows: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val stockSet: Int,
    val staffId: Long?,
    val approvedBy: Long?,
    /** This run itself continued an earlier one after this many rows (0: it started at the top). */
    val resumedFrom: Int = 0,
    val logged: Boolean = false,
) {

    fun format(): String = listOf(
        VERSION, hash, rows, created, updated, skipped, stockSet, staffId ?: "", approvedBy ?: "", resumedFrom,
        if (logged) 1 else 0,
    ).joinToString(";")

    /** The audit entry's text for this run, stopped part-way. */
    fun detail(): String = detail(created, updated, skipped, stockSet, resumedFrom, finished = false)

    companion object {
        private const val VERSION = "1"

        /** The record [format] wrote, or null when there is none or it cannot be read. */
        fun parse(text: String?): ImportProgress? {
            val p = text?.split(';') ?: return null
            if (p.size != 11 || p[0] != VERSION || p[1].length != 64) return null
            val ints = listOf(p[2], p[3], p[4], p[5], p[6], p[9]).map { it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: return null }
            return ImportProgress(
                hash = p[1], rows = ints[0], created = ints[1], updated = ints[2], skipped = ints[3], stockSet = ints[4],
                staffId = p[7].toLongOrNull(), approvedBy = p[8].toLongOrNull(), resumedFrom = ints[5], logged = p[10] == "1",
            )
        }

        /** The audit entry's text for an import run. */
        fun detail(created: Int, updated: Int, skipped: Int, stockSet: Int, resumedFrom: Int, finished: Boolean): String =
            "created $created, updated $updated, skipped $skipped, stock set $stockSet" +
                (if (resumedFrom > 0) ", continued after row $resumedFrom" else "") +
                if (finished) "" else ", stopped early"

        /**
         * SHA-256 (hex) of everything [reader] gives, read to its end in constant memory. It hashes
         * the decoded text (each UTF-16 unit as two bytes): the same file gives the same text, and
         * the hash works for any [Reader] the import is given.
         */
        fun sha256(reader: Reader): String {
            val md = MessageDigest.getInstance("SHA-256")
            val chars = CharArray(8 * 1024)
            val bytes = ByteArray(chars.size * 2)
            while (true) {
                val n = reader.read(chars, 0, chars.size)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = chars[i].code
                    bytes[2 * i] = (c shr 8).toByte()
                    bytes[2 * i + 1] = c.toByte()
                }
                md.update(bytes, 0, 2 * n)
            }
            val sb = StringBuilder(64)
            for (b in md.digest()) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
