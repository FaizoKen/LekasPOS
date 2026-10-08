package com.lekaspos.core.shift

import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.staff.ActionTotal
import com.lekaspos.core.staff.Checks
import com.lekaspos.core.staff.Tally
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** D-068: what the till asks by itself, and what the last close left in the drawer. */
class ShiftGuideTest {

    private val siti = 11L
    private val ali = 12L
    private val today = 20_000L

    @Test
    fun whatTheTillAsks() {
        // No shift open: asked to start one only where the store uses shifts.
        assertEquals(ShiftGuide.Ask.OPEN, ShiftGuide.ask(required = true, handover = true, null, null, siti, today))
        assertEquals(ShiftGuide.Ask.NONE, ShiftGuide.ask(required = false, handover = true, null, null, siti, today))
        // One's own shift today: nothing.
        assertEquals(ShiftGuide.Ask.NONE, ShiftGuide.ask(true, true, siti, today, siti, today))
        // Another person's shift today: the handover, if the store asks for it.
        assertEquals(ShiftGuide.Ask.HANDOVER, ShiftGuide.ask(true, true, ali, today, siti, today))
        assertEquals(ShiftGuide.Ask.NONE, ShiftGuide.ask(true, false, ali, today, siti, today))
        // Yesterday's shift never closed: a new day, whoever opened it, shifts required or not (one is in use).
        assertEquals(ShiftGuide.Ask.NEW_DAY, ShiftGuide.ask(true, true, siti, today - 1, siti, today))
        assertEquals(ShiftGuide.Ask.NEW_DAY, ShiftGuide.ask(false, false, ali, today - 3, siti, today))
    }

    @Test
    fun whatWasLeftInTheDrawer() {
        val left = LeftInDrawer(10_000L, siti, 1_760_000_000_000L)
        assertEquals(left, LeftInDrawer.decode(left.encode()))
        assertNull(LeftInDrawer.decode(null))
        assertNull(LeftInDrawer.decode("garbage"))
        assertNull(LeftInDrawer.decode("-5|1|2"))
        assertNull(LeftInDrawer.decode("1|x|2"))
    }

    @Test
    fun anOpeningCountShortOfWhatWasLeftIsAWarning() {
        val c = Checks.of(listOf(ActionTotal(AuditAction.FLOAT_DIFFERENCE, 1, -2_000L)))
        assertEquals(Tally(1, -2_000L), c.floatDiffs)
        assertTrue(c.warning)
        assertTrue(!Checks.of(listOf(ActionTotal(AuditAction.FLOAT_DIFFERENCE, 1, 500L))).warning) // more than left: a typo, not missing
    }
}
