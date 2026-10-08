package com.lekaspos.core.shift

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
