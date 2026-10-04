package com.lekaspos.core.text

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DigitEntryTest {

    private fun DigitEntry.type(vararg keys: String): DigitEntry = also { for (k in keys) press(k) }

    @Test
    fun digitsFillFromTheRightWithoutLeadingZeros() {
        val e = DigitEntry(9).type("0", "1", "2", "5", "0")
        assertEquals("1250", e.digits)
        assertEquals("125", e.type(DigitEntry.DELETE).digits)
        assertEquals("12500", e.type("00").digits)
    }

    @Test
    fun aShownAmountIsReplacedByTheFirstKey() {
        // A line's quantity of 2: typing 3 means 3 pieces, not 23.
        val e = DigitEntry(5)
        e.preset("2")
        assertTrue(e.replacing)
        assertTrue(e.press("3"))
        assertEquals("3", e.digits)
        assertFalse(e.replacing)
        // From then on keys add up as usual.
        assertEquals("35", e.type("5").digits)
    }

    @Test
    fun okKeepsAShownAmountAndDeleteClearsIt() {
        val e = DigitEntry(9)
        e.preset("450")
        assertEquals("450", e.digits) // OK now takes 4.50 as it is
        assertTrue(e.press(DigitEntry.DELETE))
        assertEquals("", e.digits)
        assertFalse(e.replacing)
        // A recount after a first count of 5: 6 is 6.
        e.preset("5")
        assertEquals("6", e.type("6").digits)
    }

    @Test
    fun anEmptyPresetTypesAsUsual() {
        val e = DigitEntry(9)
        e.preset("")
        assertFalse(e.replacing)
        e.preset("000")
        assertFalse(e.replacing)
        assertEquals("7", e.type("7").digits)
    }

    @Test
    fun setTypesAfterTheDigits() {
        val e = DigitEntry(9)
        e.set("12")
        assertFalse(e.replacing)
        assertEquals("123", e.type("3").digits)
    }

    @Test
    fun noMoreThanTheMostDigits() {
        val e = DigitEntry(3).type("1", "2", "3", "4")
        assertEquals("123", e.digits)
        assertFalse(e.press("5"))
        e.preset("98765")
        assertEquals("987", e.digits)
        assertFailsWith<IllegalArgumentException> { e.press("x") }
        assertFailsWith<IllegalArgumentException> { DigitEntry(0) }
    }

    @Test
    fun putBringsBackASavedState() {
        val e = DigitEntry(9)
        e.preset("2")
        val saved = e.digits to e.replacing
        e.press("9") // the first key of a scanner burst, undone
        e.put(saved.first, saved.second)
        assertEquals("2", e.digits)
        assertTrue(e.replacing)
    }
}
