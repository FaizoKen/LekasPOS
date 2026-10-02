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
}
