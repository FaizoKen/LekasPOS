package com.lekaspos.core.diag

/**
 * One error report (D-057): what went wrong, where, and on which app and phone. Never sales,
 * products, customers, staff, PINs or tokens — the free text in it went through [CrashText.scrub].
 * The relay (relay/worker.js) checks the same fields and limits.
 */
data class ErrorReport(
    val kind: String,
    /** 16 hex: the same bug has the same fingerprint on every till and in every build. */
    val fingerprint: String,
    val title: String,
    /** When it last happened, and first (a report waiting to be sent counts repeats). */
    val at: Long,
    val firstAt: Long = at,
    val count: Int = 1,
    /** The app and the phone when it happened (the app may be updated before the report is sent). */
    val app: String,
    val build: Int,
    val signing: String,
    val android: String,
    val sdk: Int,
    val device: String,
    val ramMb: Long,
    val freeMb: Long,
    val dbMb: Long,
    val lang: String,
    val thread: String = "",
    val message: String = "",
    val trace: String = "",
    /** The error log's lines before it (warnings and errors only). */
    val log: String = "",
    /** Only in a report sent by hand: what the shop says, and how to reach them if they want. */
    val note: String = "",
    val contact: String = "",
) {
    /** Within the relay's limits (its whole body is at most 64 KB). */
    fun clipped(): ErrorReport = copy(
        title = clip(title, MAX_TITLE),
        message = clip(message, MAX_MESSAGE),
        trace = clip(trace, MAX_TRACE),
        log = clipStart(log, MAX_LOG),
        note = clip(note, MAX_NOTE),
        contact = clip(contact, MAX_CONTACT),
        thread = clip(thread, 60),
        device = clip(device, 80),
    )

    /** The same problem again before the report was sent. */
    fun again(at: Long): ErrorReport = copy(at = maxOf(this.at, at), firstAt = minOf(firstAt, at), count = count + 1)

    companion object {
        const val CRASH = "crash"
        const val ANR = "anr"
        const val NATIVE = "native"
        const val KILLED = "killed"
        const val ERROR = "error"
        const val CHECK = "check"
        const val MANUAL = "manual"

        const val MAX_TITLE = 120
        const val MAX_MESSAGE = 500
        const val MAX_TRACE = 16_000
        const val MAX_LOG = 8_000
        const val MAX_NOTE = 1_000
        const val MAX_CONTACT = 200

        private fun clip(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)

        /** The end of a log is what came right before the error. */
        private fun clipStart(s: String, max: Int) = if (s.length <= max) s else s.substring(s.length - max)
    }
}
