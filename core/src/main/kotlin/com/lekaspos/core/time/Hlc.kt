package com.lekaspos.core.time

/**
 * Hybrid logical clock (references/sync.md §3). Values are `physicalMillis shl 16 or counter`
 * packed in a Long, so they compare as plain numbers. Monotonic per device; advancing past
 * observed remote values keeps "edit after seeing an edit" ordered even with clock skew.
 */
class Hlc(private val wallClock: () -> Long, initial: Long = 0L) {

    private var last: Long = initial

    /** A new timestamp, strictly greater than every earlier one from this clock. */
    @Synchronized
    fun now(): Long {
        val physical = wallClock()
        // Never past the largest value (a crafted backup or sync file once set it there): it wrapped
        // to the smallest and every later edit lost to every earlier one (2026-10 review).
        last = if (physical > physicalOf(last)) pack(physical, 0) else if (last == Long.MAX_VALUE) last else last + 1L
        return last
    }

    /**
     * Folds in a timestamp seen from another device. Returns false (and ignores it) if it lies
     * more than [MAX_FUTURE_MS] ahead of our own time — a device with a wrong clock must not
     * drag every other device's clock into the future. Our own time is the later of the wall clock
     * and our last timestamp: a till whose clock was set back stopped following the others, and its
     * later stock counts and sales sorted before theirs (2026-10 review).
     */
    @Synchronized
    fun observe(remote: Long): Boolean {
        if (physicalOf(remote) > maxOf(wallClock(), physicalOf(last)) + MAX_FUTURE_MS) return false
        // A fixed bound too, the same on every till: one step of 24 h at a time, a crafted file could
        // otherwise walk every till's clock as far ahead as it liked (2026-10 review).
        if (physicalOf(remote) > MAX_PHYSICAL_MS) return false
        if (remote > last) last = remote
        return true
    }

    @Synchronized
    fun current(): Long = last

    companion object {
        const val MAX_FUTURE_MS = 24L * 60L * 60L * 1000L

        /** 1 January 2100: no till's time is ever later. */
        const val MAX_PHYSICAL_MS = 4_102_444_800_000L
        private const val COUNTER_BITS = 16

        fun pack(physicalMillis: Long, counter: Int): Long =
            (physicalMillis shl COUNTER_BITS) or (counter.toLong() and 0xFFFFL)

        fun physicalOf(hlc: Long): Long = hlc ushr COUNTER_BITS

        fun counterOf(hlc: Long): Int = (hlc and 0xFFFFL).toInt()
    }
}
