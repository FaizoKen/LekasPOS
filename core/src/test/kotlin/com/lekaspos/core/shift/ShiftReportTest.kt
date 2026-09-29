package com.lekaspos.core.shift

import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ShiftReportTest {

    private val cash = ShiftCash(
        openingFloat = 20_000L, cashSales = 153_450L, cashRefunds = -1_250L, voided = 3_400L,
        cashIn = 5_000L, cashOut = 2_000L, drops = 100_000L, creditRepayments = 4_500L,
    )

    @Test
    fun expectedCashFollowsEveryMovement() {
        // 200.00 + 1534.50 − 12.50 − 34.00 + 50.00 − 20.00 − 1000.00 + 45.00
        assertEquals(76_300L, cash.expected)
        assertEquals(0L, cash.difference(76_300L))
        assertEquals(-300L, cash.difference(76_000L))
        assertEquals(200L, cash.difference(76_500L))
        // A voided refund takes its cash back into the drawer.
        assertEquals(0L, ShiftCash(cashRefunds = -1_000L, voided = -1_000L).expected)
        assertFailsWith<ArithmeticException> { ShiftCash(openingFloat = Long.MAX_VALUE, cashSales = 1L).expected }
    }

    private val report = ShiftReport(
        storeName = "Kedai Runcit Ali", deviceNo = 3, openedBy = "Siti", openedAt = 1_759_120_000_000L,
        closedBy = "Ali", closedAt = 1_759_150_000_000L, sales = 120, salesTotal = 245_610L, refunds = 2,
        refundsTotal = -1_250L, voids = 1, voidsTotal = 3_400L, discounts = 1_500L, tax = 0L,
        methods = listOf(
            MethodTotal("Cash", PaymentKind.CASH, 98, 148_800L),
            MethodTotal("E-wallet / QR", PaymentKind.EWALLET, 20, 80_160L),
            MethodTotal("Customer credit", PaymentKind.CREDIT, 2, 12_000L),
        ),
        cash = cash, counted = 76_000L, creditCharged = 12_000L,
        creditRepaid = listOf(MethodTotal("Cash", PaymentKind.CASH, 1, 4_500L)),
    )

    @Test
    fun sectionsAndBlindClose() {
        val layout = ShiftReportLayout(CurrencySpec.MYR, ShiftText.EN, TimeZone.getTimeZone("Asia/Kuala_Lumpur"))
        val full = layout.sections(report)
        val cashRows = full.first { it.title == "Cash drawer" }.rows.associate { it.label to it.value }
        assertEquals("763.00", cashRows["Expected cash"])
        assertEquals("760.00", cashRows["Counted cash"])
        assertEquals("-3.00", cashRows["Over / short"])
        assertEquals("-1,000.00", cashRows["Cash drops"])
        assertTrue(full.any { it.title == "Customer credit" })
        assertEquals("Sales (120)", full.first { it.title == "Sales" }.rows[0].label)

        // Blind close: the counted cash only, never what was expected.
        val blind = layout.sections(report, showCash = false)
        val blindCash = blind.first { it.title == "Cash drawer" }.rows
        assertEquals(listOf("Counted cash"), blindCash.map { it.label })
        assertFalse(blind.flatMap { it.rows }.any { it.label == "Expected cash" || it.label == "Over / short" })

        val open = layout.sections(report.copy(closedAt = null, closedBy = null, counted = null))
        assertEquals("still open", open[0].rows.first { it.label == "Closed" }.value)
        assertNull(open.first { it.title == "Cash drawer" }.rows.firstOrNull { it.label == "Counted cash" })
    }

    @Test
    fun printedSlipFitsThePaper() {
        for (lang in listOf("en", "ms")) {
            val layout = ShiftReportLayout(CurrencySpec.MYR, ShiftText.forLanguage(lang), TimeZone.getTimeZone("UTC"))
            for (cols in listOf(32, 42, 48)) {
                val lines = layout.lines(report, cols)
                assertTrue(lines.size > 20)
                for (l in lines) {
                    val text = (l as PrintLine.Text).text
                    assertTrue(TextWidth.of(text) <= cols, "'$text' is wider than $cols")
                }
                val cashLine = lines.map { (it as PrintLine.Text).text }.first { it.startsWith(if (lang == "ms") "Tunai dijangka" else "Expected cash") }
                assertTrue(cashLine.endsWith("763.00"))
                assertEquals(cols, TextWidth.of(cashLine))
            }
        }
    }

    @Test
    fun creditBalances() {
        assertEquals(1_000L, CreditMath.delta(CreditKind.CHARGE, 1_000L))
        assertEquals(-1_000L, CreditMath.delta(CreditKind.CHARGE, -1_000L)) // reversal
        assertEquals(-400L, CreditMath.delta(CreditKind.PAYMENT, 400L))
        assertEquals(-250L, CreditMath.delta(CreditKind.ADJUST, -250L))
        assertFailsWith<IllegalArgumentException> { CreditMath.delta(99, 1L) }

        assertFalse(CreditMath.overLimit(9_000L, 5_000L, 0L)) // no limit
        assertFalse(CreditMath.overLimit(5_000L, 5_000L, 10_000L)) // exactly at the limit
        assertTrue(CreditMath.overLimit(5_000L, 5_001L, 10_000L))
        assertFalse(CreditMath.overLimit(20_000L, -1_000L, 10_000L)) // reversals never blocked
        assertEquals(5_000L, CreditMath.available(5_000L, 10_000L))
        assertEquals(0L, CreditMath.available(12_000L, 10_000L))
        assertNull(CreditMath.available(12_000L, 0L))
    }
}
