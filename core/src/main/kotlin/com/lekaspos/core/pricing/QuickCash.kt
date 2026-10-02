package com.lekaspos.core.pricing

import com.lekaspos.core.money.Checked

/**
 * The cash amounts offered as one-tap buttons besides "Exact" for [due] (minor units; [scale]
 * minor units per major unit): what customers hand over with Malaysian notes — the next 5, the
 * next 10, a 20 note for a smaller bill, the next 50 and the next 100, the first [max] above [due].
 * The buttons used to stop at the next 1, 5 and 10, so RM50 and RM100 were missing for most bills
 * (RM23.45 offered 24, 25 and 30; 2026-10 review).
 */
object QuickCash {

    fun amounts(due: Long, scale: Long, max: Int = 3): List<Long> {
        if (due <= 0L || scale <= 0L || max <= 0) return emptyList()
        fun next(note: Long): Long {
            val unit = Checked.mul(note, scale)
            return Checked.mul((due + unit - 1L) / unit, unit)
        }
        val out = LinkedHashSet<Long>()
        for (c in listOf(next(5L), next(10L), Checked.mul(20L, scale), next(50L), next(100L))) {
            if (c > due) out.add(c)
            if (out.size >= max) break
        }
        return out.sorted()
    }
}
