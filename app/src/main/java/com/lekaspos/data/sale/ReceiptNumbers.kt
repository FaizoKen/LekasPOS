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
