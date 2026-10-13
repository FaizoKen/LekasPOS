package com.lekaspos.core.shift

import com.lekaspos.core.time.Days
import java.util.TimeZone

/**
 * What the till asks when someone starts using it (D-068), so a shift is never forgotten and nobody has
 * to know where to find it: the selling screen asks once per person and shift, in plain words.
 */
object ShiftGuide {

    enum class Ask {
        /** Nothing to ask. */
        NONE,

        /** No shift is open and the store uses shifts: count the drawer to start one. */
        OPEN,

        /** The open shift began on an earlier day (nobody closed it): one count closes it and starts today's. */
        NEW_DAY,

        /** Another person's shift is open: one count closes theirs and starts this person's (D-067). */
        HANDOVER,
    }

    /**
     * [required]: the store uses shifts ("Require an open shift"); [handover]: it asks for a count when
     * the cashier changes; [openedBy] / [openedDay]: the open shift's opener and business day (null: none
     * open); [me]: who is using the till; [today]: the business day now.
     */
    fun ask(required: Boolean, handover: Boolean, openedBy: Long?, openedDay: Long?, me: Long, today: Long): Ask = when {
        openedBy == null || openedDay == null -> if (required) Ask.OPEN else Ask.NONE
        openedDay < today -> Ask.NEW_DAY
        openedBy != me && handover -> Ask.HANDOVER
        else -> Ask.NONE
    }

    /** No closing-time reminder (the store setting `shift.close_at`). */
    const val CLOSE_OFF = -1

    const val MINUTES_PER_DAY = 1440

    /** After "Not yet", the closing-time reminder asks again this much later. */
    const val CLOSE_AGAIN_MS = 30L * 60_000L

    /**
     * The closing-time reminder (D-077), the close's counterpart of "Start the shift": a shift is open on this
     * till ([openedAt]), the store's closing time [closeAtMinute] (minutes after midnight, [CLOSE_OFF] = no
     * reminder) has passed today, the shift was opened before it (one opened later is someone working late on
     * purpose), and the till did not ask in the last [CLOSE_AGAIN_MS] ([lastAsked]; 0 = not asked yet).
     */
    fun closeDue(closeAtMinute: Int, now: Long, tz: TimeZone, openedAt: Long?, lastAsked: Long): Boolean {
        if (closeAtMinute !in 0 until MINUTES_PER_DAY || openedAt == null) return false
        val closeAt = Days.startOfDay(Days.epochDay(now, tz), tz) + closeAtMinute * 60_000L
        return now >= closeAt && openedAt < closeAt && now - lastAsked >= CLOSE_AGAIN_MS
    }
}

/**
 * The cash left in the drawer when the last shift was closed (D-068): the next opening count is
 * compared with it, so cash that went missing between two shifts (overnight) is seen, and a mistyped
 * opening count is caught before it turns into a "short" at the end of the day.
 */
data class LeftInDrawer(val amount: Long, val staffId: Long, val at: Long) {

    fun encode(): String = "$amount|$staffId|$at"

    companion object {
        fun decode(text: String?): LeftInDrawer? {
            val p = text?.split('|') ?: return null
            if (p.size != 3) return null
            val amount = p[0].toLongOrNull() ?: return null
            val staff = p[1].toLongOrNull() ?: return null
            val at = p[2].toLongOrNull() ?: return null
            return if (amount >= 0L) LeftInDrawer(amount, staff, at) else null
        }
    }
}
