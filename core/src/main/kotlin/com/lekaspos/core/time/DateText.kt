package com.lekaspos.core.time

import com.lekaspos.core.money.Checked
import java.util.TimeZone

/**
 * Fixed-format dates for receipts and lists ("29/09/2026 14:05"), independent of the device
 * locale (which may use other digits) and without java.time (D-013).
 */
object DateText {

    /** "dd/MM/yyyy HH:mm" in [tz]. */
    fun dateTime(epochMs: Long, tz: TimeZone): String {
        val local = epochMs + tz.getOffset(epochMs)
        val day = Checked.floorDiv(local, Days.DAY_MS)
        val sb = StringBuilder(16)
        appendDate(sb, day)
        sb.append(' ')
        appendTime(sb, local - day * Days.DAY_MS)
        return sb.toString()
    }

    /** "dd/MM/yyyy" of a local epoch day. */
    fun date(epochDay: Long): String = StringBuilder(10).also { appendDate(it, epochDay) }.toString()

    /** "yyyy-MM-dd" of a local epoch day (files: sorts and parses the same everywhere). */
    fun isoDate(epochDay: Long): String {
        val ymd = Days.toYmd(epochDay)
        val sb = StringBuilder(10)
        sb.append(ymd / 10_000)
        sb.append('-')
        pad2(sb, ymd / 100 % 100)
        sb.append('-')
        pad2(sb, ymd % 100)
        return sb.toString()
    }

    /** "HH:mm" in [tz]. */
    fun time(epochMs: Long, tz: TimeZone): String {
        val local = epochMs + tz.getOffset(epochMs)
        return StringBuilder(5).also { appendTime(it, Checked.floorMod(local, Days.DAY_MS)) }.toString()
    }

    private fun appendDate(sb: StringBuilder, epochDay: Long) {
        val ymd = Days.toYmd(epochDay)
        pad2(sb, ymd % 100)
        sb.append('/')
        pad2(sb, ymd / 100 % 100)
        sb.append('/')
        sb.append(ymd / 10_000)
    }

    private fun appendTime(sb: StringBuilder, msOfDay: Long) {
        pad2(sb, (msOfDay / 3_600_000L).toInt())
        sb.append(':')
        pad2(sb, (msOfDay / 60_000L % 60L).toInt())
    }

    private fun pad2(sb: StringBuilder, v: Int) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }
}
