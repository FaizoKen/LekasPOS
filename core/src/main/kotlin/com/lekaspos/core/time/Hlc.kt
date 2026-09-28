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
        last = if (physical > physicalOf(last)) pack(physical, 0) else last + 1L
        return last
    }

    /**
     * Folds in a timestamp seen from another device. Returns false (and ignores it) if it lies
     * more than [MAX_FUTURE_MS] ahead of our wall clock — a device with a wrong clock must not
     * drag every other device's clock into the future.
     */
    @Synchronized
    fun observe(remote: Long): Boolean {
        if (physicalOf(remote) > wallClock() + MAX_FUTURE_MS) return false
        if (remote > last) last = remote
        return true
    }

    @Synchronized
    fun current(): Long = last

    companion object {
        const val MAX_FUTURE_MS = 24L * 60L * 60L * 1000L
        private const val COUNTER_BITS = 16

        fun pack(physicalMillis: Long, counter: Int): Long =
            (physicalMillis shl COUNTER_BITS) or (counter.toLong() and 0xFFFFL)

        fun physicalOf(hlc: Long): Long = hlc ushr COUNTER_BITS

        fun counterOf(hlc: Long): Int = (hlc and 0xFFFFL).toInt()
    }
}
