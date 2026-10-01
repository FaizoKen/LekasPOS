package com.lekaspos.core.sync

/** Version of a value: HLC timestamp, ties broken by device number (references/sync.md §3). */
data class Version(val hlc: Long, val dev: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        if (hlc != other.hlc) hlc.compareTo(other.hlc) else dev.compareTo(other.dev)

    companion object {
        /** Version of seed rows: any real edit wins. */
        val SEED = Version(0L, 0)
    }
}

/**
 * Per-field versions of an LWW row, stored in its `fver` column as `field:hlc:dev` entries
 * joined by `;` (NULL when empty). A field without an entry has the row's base version
 * (`ver_hlc`, `ver_dev`). See references/database.md §4.
 */
class FieldVersions private constructor(private val map: Map<String, Version>) {

    fun of(field: String, base: Version): Version = map[field] ?: base

    fun with(field: String, version: Version): FieldVersions {
        require(field.isNotEmpty() && field.none { it == ':' || it == ';' }) { "bad field name $field" }
        val m = LinkedHashMap(map)
        m[field] = version
        return FieldVersions(m)
    }

    fun with(fields: Collection<String>, version: Version): FieldVersions {
        var r = this
        for (f in fields) r = r.with(f, version)
        return r
    }

    val isEmpty: Boolean get() = map.isEmpty()

    fun fields(): Set<String> = map.keys

    /** Column value: null when empty. Entries sorted by field name for stable output. */
    fun encode(): String? {
        if (map.isEmpty()) return null
        return map.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.hlc}:${it.value.dev}" }
    }

    override fun equals(other: Any?): Boolean = other is FieldVersions && other.map == map

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = encode() ?: ""

    companion object {
        val EMPTY = FieldVersions(emptyMap())

        fun decode(s: String?): FieldVersions {
            if (s.isNullOrEmpty()) return EMPTY
            val m = LinkedHashMap<String, Version>()
            for (entry in s.split(';')) {
                if (entry.isEmpty()) continue
                val parts = entry.split(':')
                require(parts.size == 3) { "bad fver entry '$entry'" }
                m[parts[0]] = Version(parts[1].toLong(), parts[2].toInt())
            }
            return FieldVersions(m)
        }
    }
}

/** Per-field last-writer-wins decisions. */
object Lww {

    /**
     * Fields of an incoming change (all stamped [incoming]) that should be applied to a row
     * whose base version is [base] and field versions [current]. Strictly greater wins, so
     * re-applying the same change is a no-op (idempotent).
     */
    fun winningFields(
        incomingFields: Collection<String>,
        incoming: Version,
        base: Version,
        current: FieldVersions,
    ): List<String> = incomingFields.filter { incoming > current.of(it, base) }

    /**
     * The HLC that stamps a local edit: the clock's [now], but always above [held], the newest
     * version the edited fields have here (2026-10 review). A till whose clock is behind the one
     * that wrote them (more than a day: its clock does not follow) would otherwise keep its edit
     * while every other till rejects it as older — the tills would disagree for good.
     */
    fun stampAbove(now: Long, held: Long): Long = if (now > held) now else held + 1L
}
