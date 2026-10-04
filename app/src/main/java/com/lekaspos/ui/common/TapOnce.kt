package com.lekaspos.ui.common

import android.os.SystemClock

/**
 * One action per tap: a second tap within [gapMs] of the last one that ran (a double tap) is
 * dropped. A double tap on Print queued two copies with two audit entries, on "Open drawer" gave
 * two pulses, on "Back up now" two whole backups that are kept for good (2026-10 review).
 */
class TapOnce(private val gapMs: Long = 1_000L) {
    private var lastAt = 0L

    /** Runs [action] unless the last one ran less than [gapMs] ago. Main thread. */
    fun run(action: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (lastAt != 0L && now - lastAt < gapMs) return
        lastAt = now
        action()
    }
}
