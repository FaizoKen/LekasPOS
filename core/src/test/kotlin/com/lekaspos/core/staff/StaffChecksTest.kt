package com.lekaspos.core.staff

import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.TileColor
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.shift.ShiftReport
import com.lekaspos.core.shift.ShiftReportLayout
import com.lekaspos.core.shift.ShiftText
import com.lekaspos.core.text.TextWidth
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** D-067: what the owner should look at, from the activity log. */
class StaffChecksTest {

    private val rows = listOf(
        ActionTotal(AuditAction.BILL_CANCEL, 2, 4_500L),
        ActionTotal(AuditAction.BILL_CANCEL_AFTER_PAY, 1, 2_340L),
        ActionTotal(AuditAction.LINE_REMOVE, 5, 1_200L),
        ActionTotal(AuditAction.LINE_REMOVE_AFTER_PAY, 2, 650L),
        ActionTotal(AuditAction.SALE_VOID, 1, 3_890L),
        ActionTotal(AuditAction.REFUND, 1, -420L),
        ActionTotal(AuditAction.LINE_DISCOUNT, 2, 1_000L),
        ActionTotal(AuditAction.PRICE_OVERRIDE, 1, 250L),
        ActionTotal(AuditAction.DRAWER_OPEN, 3, 0L),
        ActionTotal(AuditAction.REPRINT, 4, 0L),
        ActionTotal(AuditAction.CASH_OUT, 1, 5_000L),
        ActionTotal(AuditAction.CASH_DROP, 1, 50_000L),
        ActionTotal(AuditAction.STOCK_WRITE_OFF, 2, 780L),
        ActionTotal(AuditAction.SHIFT_CONTINUED, 1, 0L),
        ActionTotal(AuditAction.SIGN_IN, 30, 0L),
    )

    @Test
    fun foldsTheActivityLog() {
        val c = Checks.of(rows)
        assertEquals(Tally(3, 6_840L), c.cleared) // after the total was shown too
        assertEquals(Tally(7, 1_850L), c.removed)
        assertEquals(Tally(3, 2_990L), c.afterPay)
        assertEquals(Tally(1, 3_890L), c.voids)
        assertEquals(Tally(1, -420L), c.refunds)
        assertEquals(3L, c.discounts) // counts only: percent and sen amounts do not add up
        assertEquals(3L, c.drawerOpens)
        assertEquals(4L, c.copies)
        assertEquals(Tally(1, 5_000L), c.cashOut) // a cash drop to the bank is not cash taken out
        assertEquals(Tally(2, 780L), c.writeOffs)
        assertEquals(1L, c.continued)
        assertTrue(c.warning)
        assertFalse(c.isEmpty)
        // Sign-ins and everything else are not checks.
        assertTrue(Checks.of(listOf(ActionTotal(AuditAction.SIGN_IN, 9, 0L), ActionTotal(AuditAction.CASH_DROP, 1, 1L))).isEmpty)
        assertTrue(Checks.ACTIONS.none { it == AuditAction.SIGN_IN || it == AuditAction.CASH_DROP })
    }

    @Test
    fun aWrongItemTakenOffIsNoWarning() {
        val c = Checks.of(listOf(ActionTotal(AuditAction.LINE_REMOVE, 1, 350L), ActionTotal(AuditAction.BILL_CANCEL, 1, 900L)))
        assertFalse(c.warning)
        assertTrue(Checks.of(listOf(ActionTotal(AuditAction.DRAWER_OPEN, 1, 0L))).warning)
    }

    @Test
    fun theMostToLookAtComesFirst() {
        val honest = StaffCheck(1, "Aminah", sales = Tally(80, 120_000L), checks = Checks.of(listOf(ActionTotal(AuditAction.LINE_REMOVE, 3, 900L))))
        val short = StaffCheck(2, "Badrul", sales = Tally(70, 100_000L), shifts = 2, overShort = -5_000L, shortShifts = 1)
        val afterPay = StaffCheck(3, "Chong", checks = Checks.of(listOf(ActionTotal(AuditAction.LINE_REMOVE_AFTER_PAY, 1, 800L))))
        val quiet = StaffCheck(4, "Devi", sales = Tally(10, 9_000L))
        assertEquals(listOf("Chong", "Badrul", "Aminah", "Devi"), StaffCheck.rank(listOf(quiet, honest, short, afterPay)).map { it.name })
    }

    @Test
    fun theShiftReportShowsItsChecksToWhoeverSeesTheCash() {
        val layout = ShiftReportLayout(CurrencySpec.MYR, ShiftText.EN, TimeZone.getTimeZone("UTC"))
        val report = ShiftReport(storeName = "Kedai", deviceNo = 3, openedBy = "Siti", openedAt = 0L, checks = Checks.of(rows))
        val checks = layout.sections(report).first { it.title == "Checks" }.rows.associate { it.label to it.value }
        assertEquals("68.40", checks["Bills cleared (3)"])
        assertEquals("18.50", checks["Items taken off (7)"])
        assertEquals("29.90", checks["After the total was shown (3)"])
        assertEquals("3", checks["Drawer opened, no sale"])
        assertEquals("4", checks["Receipt copies"])
        // A blind close (the cashier counts without seeing the expected cash) has no checks either.
        assertNull(layout.sections(report, showCash = false).firstOrNull { it.title == "Checks" })
        // Nothing to look at: no section.
        assertNull(layout.sections(report.copy(checks = Checks())).firstOrNull { it.title == "Checks" })
        for (lang in listOf("en", "ms")) {
            val l = ShiftReportLayout(CurrencySpec.MYR, ShiftText.forLanguage(lang), TimeZone.getTimeZone("UTC"))
            for (cols in listOf(32, 42, 48)) {
                for (line in l.lines(report, cols)) assertTrue(TextWidth.of((line as PrintLine.Text).text) <= cols)
            }
        }
    }

    @Test
    fun tileColours() {
        assertEquals(TileColor.RED, TileColor.of(TileColor.RED, TileColor.BLUE)) // the product's own first
        assertEquals(TileColor.BLUE, TileColor.of(TileColor.NONE, TileColor.BLUE)) // else its category's
        assertEquals(TileColor.NONE, TileColor.of(TileColor.NONE, TileColor.NONE))
        assertEquals(TileColor.BLUE, TileColor.of(99, TileColor.BLUE)) // a newer till's colour counts as none
        assertEquals(TileColor.NONE, TileColor.known(-1))
        assertEquals(12, TileColor.ALL.size)
        assertEquals(TileColor.ALL, TileColor.ALL.distinct())
    }
}
