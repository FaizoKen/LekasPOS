package com.lekaspos.core.inventory

import com.lekaspos.core.model.MovementKind

private const val OUT = -1
private const val IN = 1
private const val EITHER = 0

/**
 * Why stock was adjusted by hand. Stored in `stock_movement.reason` as the code, optionally
 * followed by ": " and a free-text note. Never rename a code.
 */
enum class AdjustReason(val code: String, val kind: Int, val direction: Int) {
    DAMAGED("damaged", MovementKind.WASTE, OUT),
    EXPIRED("expired", MovementKind.WASTE, OUT),
    LOST("lost", MovementKind.ADJUST, OUT),
    THEFT("theft", MovementKind.ADJUST, OUT),
    OWN_USE("own_use", MovementKind.ADJUST, OUT),
    RETURNED("returned", MovementKind.RETURN_TO_SUPPLIER, OUT),
    FOUND("found", MovementKind.ADJUST, IN),
    CORRECTION("correction", MovementKind.ADJUST, EITHER),
    OTHER("other", MovementKind.ADJUST, EITHER),
    ;

    /** Signed stock change for a quantity the user entered as a positive number. */
    fun delta(qty: Long, removing: Boolean): Long {
        require(qty > 0L) { "qty must be > 0" }
        val out = when (direction) {
            OUT -> true
            IN -> false
            else -> removing
        }
        return if (out) -qty else qty
    }

    fun encode(note: String?): String {
        val n = note?.trim()?.replace('\n', ' ')
        return if (n.isNullOrEmpty()) code else "$code: $n"
    }

    companion object {
        /** Reason and note from a stored text; unknown codes (newer app) keep the whole text as note. */
        fun decode(text: String?): Pair<AdjustReason?, String?> {
            if (text.isNullOrEmpty()) return null to null
            val colon = text.indexOf(": ")
            val code = if (colon >= 0) text.substring(0, colon) else text
            val reason = values().firstOrNull { it.code == code } ?: return null to text
            return reason to if (colon >= 0) text.substring(colon + 2) else null
        }
    }
}
