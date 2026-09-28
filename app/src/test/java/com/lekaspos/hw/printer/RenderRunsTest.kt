package com.lekaspos.hw.printer

import kotlin.test.assertEquals
import org.junit.Test

/** Image receipts place each text run at its printer column (ReceiptRenderer.runs). */
class RenderRunsTest {

    @Test
    fun runsStartAtTheirColumns() {
        assertEquals(listOf(0 to "Milo 1kg", 27 to "18.90"), ReceiptRenderer.runs("Milo 1kg".padEnd(27) + "18.90"))
        assertEquals(listOf(2 to "2 x 4.20", 28 to "8.40"), ReceiptRenderer.runs("  2 x 4.20".padEnd(28) + "8.40"))
        assertEquals(listOf(10 to "RM12.34"), ReceiptRenderer.runs(" ".repeat(10) + "RM12.34"))
        assertEquals(listOf(0 to "abc"), ReceiptRenderer.runs("abc "))
        assertEquals(emptyList(), ReceiptRenderer.runs("    "))
    }

    @Test
    fun wideCharactersTakeTwoColumns() {
        // "牛奶 Susu" is 9 columns wide, so the amount after 3 spaces starts at column 12.
        assertEquals(listOf(0 to "牛奶 Susu", 12 to "3.85"), ReceiptRenderer.runs("牛奶 Susu   3.85"))
    }
}
