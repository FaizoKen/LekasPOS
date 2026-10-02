package com.lekaspos.core.time

/**
 * Is the phone's clock fit to date a sale? A sale's date and business day come from it and are
 * never changed afterwards (they travel to every till), so a sale made while the clock is wrong
 * stays filed under the wrong day: cheap tablets and POS boxes without a clock battery start at
 * 1970 or 2000 until the network sets the time (2026-10 review).
 */
object ClockCheck {

    /** Before this app existed (2026-09-01 UTC): the clock was reset, the date is certainly wrong. */
    const val NOT_BEFORE = 1_788_220_800_000L

    /** How far before this till's own last sale the clock may be (a small correction is fine). */
    const val BACK_MARGIN_MS = 60L * 60L * 1000L

    enum class Verdict {
        OK,

        /** Certainly wrong: no sale until the date is set. */
        WRONG,

        /** Earlier than this till's last sale: the clock was set back, or was ahead before. Ask. */
        SUSPECT,
    }

    fun verdict(now: Long, lastOwnSale: Long?): Verdict = when {
        now < NOT_BEFORE -> Verdict.WRONG
        lastOwnSale != null && now < lastOwnSale - BACK_MARGIN_MS -> Verdict.SUSPECT
        else -> Verdict.OK
    }
}
