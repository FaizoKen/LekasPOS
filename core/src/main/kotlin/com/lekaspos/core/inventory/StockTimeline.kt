package com.lekaspos.core.inventory

import com.lekaspos.core.money.Checked

/** Something that changed one product's stock, as the history screen lists it. */
sealed class StockEvent {
    abstract val hlc: Long
    abstract val id: Long

    /** A sale line, refund line, receipt, adjustment…: stock changed by [delta] (0 if voided). */
    data class Change(override val hlc: Long, override val id: Long, val delta: Long) : StockEvent()

    /** A count: stock became [counted]; [expected] is what the app had just before (null = unknown). */
    data class Count(override val hlc: Long, override val id: Long, val counted: Long, val expected: Long?) : StockEvent()
}

/**
 * Running balance for a product's history, newest first (references/database.md §7): starting
 * from the current level, each change is undone on the way back; a count resets the balance to
 * what was expected before it. `null` = unknown (a count from before schema v2 has no expected
 * value).
 */
object StockTimeline {

    /** Balance right after each event of [newestFirst], given the balance after the newest one. */
    fun balances(newestFirst: List<StockEvent>, levelAfterNewest: Long?): Balances {
        val after = arrayOfNulls<Long>(newestFirst.size)
        var running = levelAfterNewest
        for ((i, e) in newestFirst.withIndex()) {
            after[i] = when (e) {
                is StockEvent.Count -> e.counted
                is StockEvent.Change -> running
            }
            running = when (e) {
                is StockEvent.Count -> e.expected
                is StockEvent.Change -> running?.let { Checked.sub(it, e.delta) }
            }
        }
        return Balances(after.toList(), running)
    }

    /** [after] per event; [before] = balance before the oldest event (start value of the next page). */
    data class Balances(val after: List<Long?>, val before: Long?)
}
