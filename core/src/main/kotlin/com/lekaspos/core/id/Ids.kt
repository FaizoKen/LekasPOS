package com.lekaspos.core.id

import java.util.Random

/**
 * Globally unique 63-bit IDs without a server: `(deviceNo shl 41) or seq`.
 * deviceNo 0 is reserved for seed rows that are identical on every device.
 * See references/database.md §5 and docs/DECISIONS.md D-006.
 */
object Ids {
    const val DEVICE_BITS = 22
    const val SEQ_BITS = 41
    const val MAX_DEVICE_NO = (1 shl DEVICE_BITS) - 1 // 4,194,303
    const val MAX_SEQ = (1L shl SEQ_BITS) - 1L

    fun make(deviceNo: Int, seq: Long): Long {
        require(deviceNo in 0..MAX_DEVICE_NO) { "deviceNo out of range: $deviceNo" }
        require(seq in 1L..MAX_SEQ) { "seq out of range: $seq" }
        return (deviceNo.toLong() shl SEQ_BITS) or seq
    }

    fun deviceOf(id: Long): Int = (id ushr SEQ_BITS).toInt()

    fun seqOf(id: Long): Long = id and MAX_SEQ

    /** A random device number in 1..MAX_DEVICE_NO. */
    fun randomDeviceNo(random: Random): Int = 1 + random.nextInt(MAX_DEVICE_NO)

    /** First and last ID a device can ever produce — handy for range queries. */
    fun rangeOf(deviceNo: Int): LongRange = make(deviceNo, 1L)..make(deviceNo, MAX_SEQ)
}

/**
 * Hands out IDs for one device. Sequence numbers are reserved in blocks and the reservation is
 * persisted (in its own committed transaction) *before* any ID from the block is used, so a
 * crash can create gaps but never reuse an ID.
 */
class IdAllocator(
    private val deviceNo: Int,
    private val store: ReservationStore,
    private val blockSize: Long = 1000L,
) {
    interface ReservationStore {
        /** Highest sequence number ever reserved (0 if none). */
        fun loadReserved(): Long

        /** Durably records a new reservation high-water mark. Must not be inside a transaction. */
        fun saveReserved(value: Long)
    }

    private var next: Long
    private var limit: Long

    init {
        require(blockSize > 0L)
        val reserved = store.loadReserved()
        next = reserved + 1L
        limit = reserved
    }

    /** Ensures at least [count] IDs can be taken without touching storage. */
    @Synchronized
    fun ensure(count: Long) {
        require(count >= 0L)
        if (next + count - 1L > limit) {
            val newLimit = next + count - 1L + blockSize
            require(newLimit <= Ids.MAX_SEQ) { "device ID space exhausted" }
            store.saveReserved(newLimit)
            limit = newLimit
        }
    }

    @Synchronized
    fun remaining(): Long = limit - next + 1L

    /** Next ID; [ensure] must have reserved it. Throws rather than risk reuse. */
    @Synchronized
    fun nextId(): Long {
        check(next <= limit) { "ID block exhausted: call ensure() before the transaction" }
        return Ids.make(deviceNo, next++)
    }
}
