package com.lekaspos.core.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyFormatTest {

    private val myr = CurrencySpec.MYR

    @Test
    fun formatsRinggit() {
        assertEquals("RM0.00", MoneyFormat.format(0, myr))
        assertEquals("RM12.50", MoneyFormat.format(1250, myr))
        assertEquals("RM1,234,567.89", MoneyFormat.format(123_456_789, myr))
        assertEquals("-RM0.05", MoneyFormat.format(-5, myr))
        assertEquals("1,000.00", MoneyFormat.format(100_000, myr, withSymbol = false))
        assertEquals("RM999.99", MoneyFormat.format(99_999, myr))
        assertEquals("RM100.00", MoneyFormat.format(10_000, myr))
    }

    @Test
    fun formatsOtherDecimalCounts() {
        val rupiah = CurrencySpec(code = "IDR", symbol = "Rp", decimals = 0, cashStep = 0)
        assertEquals("Rp1,500,000", MoneyFormat.format(1_500_000, rupiah))
        val dinar = CurrencySpec(code = "KWD", symbol = "KD", decimals = 3, cashStep = 0, symbolBefore = false)
        assertEquals("1.234 KD", MoneyFormat.format(1234, dinar))
        val euroStyle = CurrencySpec(code = "EUR", symbol = "€", groupSeparator = '.', decimalSeparator = ',', cashStep = 0)
        assertEquals("€1.234,50", MoneyFormat.format(123_450, euroStyle))
    }

    @Test
    fun plainFormatIsMachineReadable() {
        assertEquals("-1234.50", MoneyFormat.plain(-123_450, 2))
        assertEquals("0.05", MoneyFormat.plain(5, 2))
        assertEquals("7", MoneyFormat.plain(7, 0))
    }

    @Test
    fun parsesUserText() {
        assertEquals(1250L, MoneyFormat.parse("12.5", myr))
        assertEquals(123_450L, MoneyFormat.parse("RM 1,234.50", myr))
        assertEquals(1200L, MoneyFormat.parse("rm12", myr))
        assertEquals(1250L, MoneyFormat.parse("12.50 RM", myr))
        assertEquals(-300L, MoneyFormat.parse("-3", myr))
        assertEquals(-300L, MoneyFormat.parse("RM-3", myr))
        assertEquals(50L, MoneyFormat.parse(".5", myr))
        assertEquals(1200L, MoneyFormat.parse("12.", myr))
        assertEquals(0L, MoneyFormat.parse("0", myr))
    }

    @Test
    fun rejectsInexactOrMalformedText() {
        assertNull(MoneyFormat.parse("12.505", myr)) // more decimals than the currency has
        assertNull(MoneyFormat.parse("1,23.00", myr))
        assertNull(MoneyFormat.parse("12,34", myr))
        assertNull(MoneyFormat.parse("abc", myr))
        assertNull(MoneyFormat.parse("", myr))
        assertNull(MoneyFormat.parse("1.2.3", myr))
        assertNull(MoneyFormat.parse("99999999999999999999", myr))
        assertNull(MoneyFormat.parse("1e3", myr))
    }

    @Test
    fun parsesPlainImportValues() {
        assertEquals(123_450L, MoneyFormat.parsePlain("1234.5", 2))
        assertEquals(-5L, MoneyFormat.parsePlain("-0.05", 2))
        assertNull(MoneyFormat.parsePlain("1,234.50", 2))
    }

    @Test
    fun keypadFillsFromTheRight() {
        assertEquals(1250L, MoneyFormat.keypad("1250", myr))
        assertEquals(0L, MoneyFormat.keypad("", myr))
        assertEquals(12L, MoneyFormat.keypad("00012", myr))
        assertNull(MoneyFormat.keypad("12a", myr))
    }

    @Test
    fun quantitiesRoundTrip() {
        assertEquals("3", MoneyFormat.formatQty(3000))
        assertEquals("0.253", MoneyFormat.formatQty(253))
        assertEquals("1.5", MoneyFormat.formatQty(1500))
        assertEquals("1.01", MoneyFormat.formatQty(1010))
        assertEquals("-0.25", MoneyFormat.formatQty(-250))
        assertEquals(253L, MoneyFormat.parseQty("0.253"))
        assertEquals(1500L, MoneyFormat.parseQty("1.5"))
        assertEquals(3000L, MoneyFormat.parseQty("3"))
        assertNull(MoneyFormat.parseQty("1.2345"))
        for (q in listOf(1L, 10L, 999L, 1000L, 1234L, 50_000L, -7L)) {
            assertEquals(q, MoneyFormat.parseQty(MoneyFormat.formatQty(q)))
        }
    }

    @Test
    fun formatThenParseIsIdentity() {
        for (v in listOf(0L, 1L, 5L, 99L, 100L, 1234L, 99_999L, 123_456_789L, -1L, -123_456L)) {
            assertEquals(v, MoneyFormat.parse(MoneyFormat.format(v, myr), myr), "value $v")
        }
    }
}
