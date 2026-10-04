package com.lekaspos.data.sale

import com.lekaspos.core.model.SaleKind
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Meta

/**
 * Receipt numbers are unique per device and per document kind (D-017):
 * `{prefix}{R if refund}{seq:06}`, e.g. `KQ-000123`, `KQ-R000045`. The prefix defaults to two
 * letters derived from the device number and can be changed in settings.
 */
object ReceiptNumbers {

    fun defaultPrefix(deviceNo: Int): String {
        val a = 'A' + (deviceNo / 26) % 26
        val b = 'A' + deviceNo % 26
        return "$a$b-"
    }

    fun prefix(tx: Db.Tx): String {
        Meta.get(tx.db, Meta.RECEIPT_PREFIX)?.let { return it }
        val p = defaultPrefix(tx.deviceNo)
        Meta.put(tx.db, Meta.RECEIPT_PREFIX, p)
        return p
    }

    /** This till's prefix as stored (or the default it will get), for reading. */
    fun prefix(db: android.database.sqlite.SQLiteDatabase, deviceNo: Int): String =
        Meta.get(db, Meta.RECEIPT_PREFIX) ?: defaultPrefix(deviceNo)

    /**
     * The receipt numbers a search for [query] may mean: what was typed (any case) and — for a
     * number alone, as printed at the bottom of a receipt or said by a customer — the sale and
     * refund with that number under each of [prefixes], this till's first ("123" → `KQ-000123`,
     * `KQ-R000123`; "R45" → `KQ-R000045`). Only the full number with its letters and zeros was found
     * before (2026-10 review); then only this till's (a receipt of another till of the shop, brought
     * back to this one, was not found or another sale with the same number was — 2026-10 review).
     */
    fun candidates(query: String, prefixes: List<String>): List<String> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(q)
        val refundOnly = q.startsWith("R")
        val digits = q.removePrefix("R")
        if (isNumber(q)) {
            val n = digits.toLong()
            for (prefix in prefixes) {
                if (!refundOnly) out.add(format(prefix, SaleKind.SALE, n))
                out.add(format(prefix, SaleKind.REFUND, n))
            }
        }
        return out.toList()
    }

    fun candidates(query: String, prefix: String): List<String> = candidates(query, listOf(prefix))

    /** A receipt's number alone ("123", "R45"), which [candidates] completes with the tills' prefixes. */
    fun isNumber(query: String): Boolean {
        val digits = query.trim().uppercase().removePrefix("R")
        return digits.isNotEmpty() && digits.length <= 12 && digits.all { it in '0'..'9' }
    }

    /**
     * The prefixes a stored receipt number may have been made with: what comes before its digits
     * ("KQ-000123" → "KQ-") and, when that ends in the refund mark, also without it ("KQ-R000045" →
     * "KQ-R", "KQ-"). Empty for a number without digits at the end.
     */
    fun prefixesOf(receiptNo: String): List<String> {
        val head = receiptNo.trimEnd { it in '0'..'9' }
        if (head.length == receiptNo.length) return emptyList()
        return if (head.endsWith("R")) listOf(head, head.dropLast(1)) else listOf(head)
    }

    fun format(prefix: String, kind: Int, seq: Long): String {
        val digits = seq.toString()
        val sb = StringBuilder(prefix.length + 8)
        sb.append(prefix)
        if (kind == SaleKind.REFUND) sb.append('R')
        for (i in digits.length until 6) sb.append('0')
        sb.append(digits)
        return sb.toString()
    }
}
