package com.lekaspos.core.sync

/**
 * Long use of the sync folder (D-085, references/sync.md §8). A till uploads a segment file after most
 * sales, so a busy shop's folder grew by hundreds of files a day, for good: Google Drive holds at most
 * 500,000 items in a folder, the weekly whole-folder listing grew with every file ever sent, and a new
 * till downloaded them one by one. So each till combines its own older segments into packs — one file
 * for up to [MAX_SEGMENTS] of them — and then removes those segments from the folder. Readers that know
 * packs read the pack where a segment is gone; a till of an older version, which does not, is waited for.
 */
object Packs {

    /** Segments in one pack at most; a pack is made once this many are ready (or [MAX_BYTES]). */
    const val MAX_SEGMENTS = 2_000

    /** Segment bytes in one pack at most (pictures make large segments; a browser reads a pack whole). */
    const val MAX_BYTES = 4L * 1024L * 1024L

    /** The newest segments stay as they are: the other tills usually read them one by one meanwhile. */
    const val KEEP_NEWEST = 100

    /** A till whose card was not updated for this long is not waited for (it reads the packs once updated). */
    const val READER_GONE_MS = 30L * 24L * 60L * 60L * 1000L

    /** One of this till's sealed segments: its number and size in bytes. */
    data class Sealed(val seq: Long, val size: Long)

    /**
     * The segments of the next pack, or null when it is not time yet. [after]: the last number already
     * in a pack; [rows]: this till's segments after it, in order (at least [maxSegments] of them when
     * there are); [uploadedTo]: the last one uploaded. The pack takes the segments that follow on from
     * [after], uploaded and older than the newest [keepNewest], until it holds [maxSegments] of them or
     * [maxBytes] — and is only made once it is full, so a pack is a few days of a busy till, or weeks of
     * a quiet one.
     */
    fun next(
        after: Long,
        rows: List<Sealed>,
        uploadedTo: Long,
        keepNewest: Int = KEEP_NEWEST,
        maxSegments: Int = MAX_SEGMENTS,
        maxBytes: Long = MAX_BYTES,
    ): LongRange? {
        val last = uploadedTo - keepNewest
        var expect = after + 1L
        var bytes = 0L
        for (r in rows) {
            if (r.seq != expect || r.seq > last) return null // a missing number, or too new: not full yet
            bytes += r.size
            if (r.seq - after >= maxSegments || bytes >= maxBytes) return (after + 1L)..r.seq
            expect++
        }
        return null
    }

    /** Another till as its card says: whether it reads packs, when it was last seen, how far it has read this till. */
    data class Reader(val readsPacks: Boolean, val lastSeen: Long, val cursor: Long)

    /**
     * The highest number of this till's segments that may leave the folder: those in a pack ([packedTo]),
     * but not past what any till that cannot read packs has read — unless it has not been seen for
     * [goneMs] (it is offered the update, and once updated it reads the packs).
     */
    fun removableTo(packedTo: Long, readers: Collection<Reader>, now: Long, goneMs: Long = READER_GONE_MS): Long {
        var limit = packedTo
        for (r in readers) {
            if (r.readsPacks || now - r.lastSeen > goneMs) continue
            limit = minOf(limit, r.cursor)
        }
        return maxOf(limit, 0L)
    }
}
