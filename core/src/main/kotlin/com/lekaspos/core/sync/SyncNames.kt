package com.lekaspos.core.sync

/**
 * Names of the files a store keeps in its sync folder (references/sync.md §5). Flat names, so
 * any blob store works: Google Drive's hidden app folder, a USB stick, a test directory.
 * A till only ever creates (and deletes) files carrying its own device number.
 */
object SyncNames {

    fun store(store: String): String = "store-$store.json"

    fun device(store: String, dev: Int): String = "dev-$store-$dev.json"

    fun segment(store: String, dev: Int, seq: Long): String = "seg-$store-$dev-${pad(seq)}$SEGMENT_EXT"

    /**
     * A pack (D-085): till [dev]'s segments [from]..[to] in one file. Its name starts like a segment's, so
     * one listing finds both, and ends otherwise, so a till of an older version passes it by.
     */
    fun pack(store: String, dev: Int, from: Long, to: Long): String = "seg-$store-$dev-${pad(from)}-${pad(to)}$PACK_EXT"

    fun segmentPrefix(store: String): String = "seg-$store-"

    fun devicePrefix(store: String): String = "dev-$store-"

    const val STORE_PREFIX = "store-"
    const val SEGMENT_EXT = ".ndjson.gz"
    const val PACK_EXT = ".pack.gz"
    private const val SEQ_DIGITS = 8

    private fun pad(seq: Long) = seq.toString().padStart(SEQ_DIGITS, '0')

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

    /**
     * One till's file in the folder: a segment ([pack] false, [from] = [to] = its number) or a pack of its
     * segments [from]..[to] (D-085).
     */
    data class TillFile(val store: String, val dev: Int, val from: Long, val to: Long, val pack: Boolean)

    /** A segment or pack name parsed, or null for any other name. */
    fun parseFile(name: String): TillFile? {
        parseSegment(name)?.let { return TillFile(it.store, it.dev, it.seq, it.seq, false) }
        if (!name.startsWith("seg-") || !name.endsWith(PACK_EXT)) return null
        val body = name.substring(4, name.length - PACK_EXT.length)
        val toDash = body.lastIndexOf('-')
        if (toDash <= 0) return null
        val fromDash = body.lastIndexOf('-', toDash - 1)
        if (fromDash <= 0) return null
        val devDash = body.lastIndexOf('-', fromDash - 1)
        if (devDash <= 0) return null
        val dev = body.substring(devDash + 1, fromDash).toIntOrNull() ?: return null
        val from = body.substring(fromDash + 1, toDash).toLongOrNull() ?: return null
        val to = body.substring(toDash + 1).toLongOrNull() ?: return null
        if (from < 1L || to < from || dev < 0) return null
        return TillFile(body.substring(0, devDash), dev, from, to, true)
    }

    /** The store id of a store manifest name, or null. */
    fun parseStore(name: String): String? =
        if (name.startsWith(STORE_PREFIX) && name.endsWith(".json")) name.substring(STORE_PREFIX.length, name.length - 5).takeIf { it.isNotEmpty() } else null
}

/** Which files of one other till to apply next (references/sync.md §6). */
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
     * The files of one other till to read after [applied], in order (D-085): each starts at the number
     * after the one before. A segment is read on its own; a pack holding the next number is read instead
     * when that segment is gone, or when it holds [packWorth] or more of the numbers still to read (one
     * download for many). The run stops at the first number no file holds: it is waited for, never skipped.
     */
    fun plan(applied: Long, files: Collection<SyncNames.TillFile>, packWorth: Int = PACK_WORTH): List<SyncNames.TillFile> {
        val segments = HashMap<Long, SyncNames.TillFile>()
        val packs = ArrayList<SyncNames.TillFile>()
        for (f in files) if (f.pack) packs.add(f) else segments[f.from] = f
        val out = ArrayList<SyncNames.TillFile>()
        var s = applied + 1L
        while (true) {
            val pack = packs.filter { it.from <= s && it.to >= s }.maxByOrNull { it.to }
            val segment = segments[s]
            val next = when {
                pack != null && (segment == null || pack.to - s + 1L >= packWorth) -> pack
                segment != null -> segment
                else -> return out
            }
            out.add(next)
            s = next.to + 1L
        }
    }

    /** The number [plan] reads up to from [applied] ([applied] when no file follows on). */
    fun reach(applied: Long, files: Collection<SyncNames.TillFile>): Long = plan(applied, files).lastOrNull()?.to ?: applied

    /** A pack holding this many of the numbers still to read is read instead of those segments. */
    const val PACK_WORTH = 20
}
