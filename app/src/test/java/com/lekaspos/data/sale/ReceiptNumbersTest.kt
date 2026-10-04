package com.lekaspos.data.sale

import kotlin.test.assertEquals
import org.junit.Test

/** 2026-10 review: a receipt was found only by its full number with letters and zeros. */
class ReceiptNumbersTest {

    @Test
    fun aNumberAloneFindsThisTillsSaleAndRefund() {
        assertEquals(listOf("123", "KQ-000123", "KQ-R000123"), ReceiptNumbers.candidates(" 123 ", "KQ-"))
        assertEquals(listOf("R45", "KQ-R000045"), ReceiptNumbers.candidates("r45", "KQ-"))
        assertEquals(listOf("AB-000777"), ReceiptNumbers.candidates("ab-000777", "KQ-"))
        assertEquals(emptyList(), ReceiptNumbers.candidates("  ", "KQ-"))
    }

    @Test
    fun aNumberAloneAlsoFindsTheOtherTillsReceipts() {
        assertEquals(
            listOf("123", "KQ-000123", "KQ-R000123", "AB-000123", "AB-R000123"),
            ReceiptNumbers.candidates("123", listOf("KQ-", "AB-")),
        )
        assertEquals(listOf("R7", "KQ-R000007", "AB-R000007"), ReceiptNumbers.candidates("R7", listOf("KQ-", "AB-")))
        assertEquals(listOf("AB-000777"), ReceiptNumbers.candidates("AB-000777", listOf("KQ-", "AB-")))
    }

    @Test
    fun prefixesAreWhatComesBeforeTheNumber() {
        assertEquals(listOf("KQ-"), ReceiptNumbers.prefixesOf("KQ-000123"))
        assertEquals(listOf("KQ-R", "KQ-"), ReceiptNumbers.prefixesOf("KQ-R000045"))
        assertEquals(listOf("SHOP1/"), ReceiptNumbers.prefixesOf("SHOP1/004500"))
        assertEquals(emptyList(), ReceiptNumbers.prefixesOf("NODIGITS"))
        assertEquals(true, ReceiptNumbers.isNumber(" r12 "))
        assertEquals(false, ReceiptNumbers.isNumber("KQ-12"))
    }
}
