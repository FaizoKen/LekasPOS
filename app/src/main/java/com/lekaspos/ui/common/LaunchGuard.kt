package com.lekaspos.ui.common

import android.content.Intent
import android.os.SystemClock

/**
 * One screen started once per tap: the same screen (or picker, or share sheet) asked for again
 * within [SAME_MS] is not started a second time. A double tap opened two Refund screens of one sale,
 * and each refunded the same return; two Receive screens overwrote each other's draft (2026-10 review).
 */
class LaunchGuard {
    private var last: String? = null
    private var lastAt = 0L

    /** True when [intent] may start now (and is remembered as the last one). Main thread. */
    fun allow(intent: Intent): Boolean {
        val key = intent.component?.className ?: intent.action ?: return true
        val now = SystemClock.uptimeMillis()
        if (key == last && now - lastAt < SAME_MS) return false
        last = key
        lastAt = now
        return true
    }

    private companion object {
        const val SAME_MS = 700L
    }
}
