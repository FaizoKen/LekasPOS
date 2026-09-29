package com.lekaspos.core.sync

/**
 * Names of the files a store keeps in its sync folder (references/sync.md §5). Flat names, so
 * any blob store works: Google Drive's hidden app folder, a USB stick, a test directory.
 * A till only ever creates (and deletes) files carrying its own device number.
 */
object SyncNames {

    fun store(store: String): String = "store-$store.json"

    fun device(store: String, dev: Int): String = "dev-$store-$dev.json"

    fun segment(store: String, dev: Int, seq: Long): String = "seg-$store-$dev-${seq.toString().padStart(SEQ_DIGITS, '0')}$SEGMENT_EXT"

    fun segmentPrefix(store: String): String = "seg-$store-"

    fun devicePrefix(store: String): String = "dev-$store-"

    const val STORE_PREFIX = "store-"
    const val SEGMENT_EXT = ".ndjson.gz"
    private const val SEQ_DIGITS = 8

    /** A parsed segment name. */
    data class Segment(val store: String, val dev: Int, val seq: Long)

    /** (store, device, seq) of a segment file name, or null for any other name. */
    fun parseSegment(name: String): Segment? {
        if (!name.startsWith("seg-") || !name.endsWith(SEGMENT_EXT)) return null
        val body = name.substring(4, name.length - SEGMENT_EXT.length)
        val lastDash = body.lastIndexOf('-')
        if (lastDash <= 0) return null
        val devDash = body.lastIndexOf('-', lastDash - 1)
        if (devDash <= 0) return null
        val store = body.substring(0, devDash)
        val dev = body.substring(devDash + 1, lastDash).toIntOrNull() ?: return null
        val seq = body.substring(lastDash + 1).toLongOrNull() ?: return null
        if (seq < 1L || dev < 0) return null
        return Segment(store, dev, seq)
    }

    /** The store id of a store manifest name, or null. */
    fun parseStore(name: String): String? =
        if (name.startsWith(STORE_PREFIX) && name.endsWith(".json")) name.substring(STORE_PREFIX.length, name.length - 5).takeIf { it.isNotEmpty() } else null
}

/** Which segments of one other till to apply next (references/sync.md §7). */
object Cursors {
    /**
     * The sequence numbers after [applied] that are all present in [available], in order — the
     * run stops at the first gap (a missing file is waited for, never skipped).
     */
    fun next(applied: Long, available: Collection<Long>): List<Long> {
        val have = available.toHashSet()
        val out = ArrayList<Long>()
        var s = applied + 1L
        while (s in have) {
            out.add(s)
            s++
        }
        return out
    }

    /**
     * A till may delete its own segment [seq] once every till seen recently has applied it
     * ([appliedBy]: device → its cursor for this till). With no other till, nothing is deleted.
     */
    fun deletable(seq: Long, appliedBy: Collection<Long>): Boolean = appliedBy.isNotEmpty() && appliedBy.all { it >= seq }
}
