package com.lekaspos.core.barcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodeTest {

    @Test
    fun restoresTheLeadingZeroASpreadsheetDropped() {
        assertEquals("01234565", Gtin.restoreLeadingZero("1234565")) // EAN-8
        assertEquals("036000291452", Gtin.restoreLeadingZero("36000291452")) // UPC-A
        assertEquals("1234567", Gtin.restoreLeadingZero("1234567")) // 01234567 is not valid: a shop's own code
        assertEquals("036000291452", Gtin.restoreLeadingZero("036000291452")) // a UPC-A already
        assertEquals("4006381333931", Gtin.restoreLeadingZero("4006381333931"))
        assertEquals("123456A", Gtin.restoreLeadingZero("123456A"))
        // The form older imports stored, so a re-import finds that product instead of adding it again.
        assertEquals("1234565", Gtin.withoutLeadingZero("01234565"))
        assertEquals("36000291452", Gtin.withoutLeadingZero("036000291452"))
        assertEquals(null, Gtin.withoutLeadingZero("4006381333931"))
        assertEquals(null, Gtin.withoutLeadingZero("12345670"))
        // A scan of the short form finds the code stored with its 0, and the other way round.
        assertEquals(listOf("1234565", "01234565"), Gtin.lookupVariants("1234565"))
        assertEquals(listOf("36000291452", "036000291452"), Gtin.lookupVariants("36000291452"))
        assertEquals(listOf("1234567"), Gtin.lookupVariants("1234567"))
    }

    @Test
    fun validatesKnownCodes() {
        assertTrue(Gtin.isValid("4006381333931")) // EAN-13
        assertFalse(Gtin.isValid("4006381333932"))
        assertTrue(Gtin.isValid("9780306406157")) // ISBN-13
        assertTrue(Gtin.isValid("036000291452")) // UPC-A
        assertTrue(Gtin.isValid("96385074")) // EAN-8
        assertFalse(Gtin.isValid("40063813339")) // bad length
        assertFalse(Gtin.isValid("400638133393A"))
    }

    @Test
    fun computesCheckDigits() {
        assertEquals("4006381333931", Gtin.withCheckDigit("400638133393"))
        assertEquals(2, Gtin.checkDigit("03600029145"))
    }

    /** 2026-10 review: typed, imported or camera codes came in forms no scan could reach. */
    @Test
    fun barcodesAreStoredInTheFormAScanFinds() {
        assertEquals("9556001234568", Gtin.canonical(" 9 556001 234568 ")) // as printed under the bars
        assertEquals("9556001234568", Gtin.canonical("955-6001-234568"))
        assertEquals("9556001234568", Gtin.canonical("09556001234568")) // GTIN-14 → its EAN-13
        assertEquals("ABC123", Gtin.canonical("ABC123\n")) // a camera code with a line break
        assertEquals("01234565", Gtin.canonical("1234565")) // the 0 a spreadsheet dropped
        assertEquals("ABC-123", Gtin.canonical("ABC-123")) // a shop's own code keeps its dash
        assertEquals("12 34", Gtin.canonical("12 34")) // digits that are no GTIN stay as typed
        assertEquals("036000291452", Gtin.canonical("036000291452"))
    }

    @Test
    fun lookupVariantsCoverUpcAndEanForms() {
        assertEquals(listOf("036000291452", "0036000291452"), Gtin.lookupVariants("036000291452"))
        assertEquals(listOf("0036000291452", "036000291452"), Gtin.lookupVariants(" 0036000291452 "))
        // An EAN-13 also finds its 14-digit form (stored from a distributor's file before canonical forms).
        assertEquals(listOf("9556001000013", "09556001000013"), Gtin.lookupVariants("9556001000013"))
        assertEquals(listOf("ABC-1"), Gtin.lookupVariants("ABC-1"))
        assertEquals(emptyList(), Gtin.lookupVariants("  "))
    }

    @Test
    fun parsesWeightLabels() {
        val t = ScaleTemplate("20IIIIIWWWWWC")
        val code = Gtin.withCheckDigit("201234501253")
        val parsed = t.parse(code)
        assertEquals(ScaleCode(itemCode = "12345", weightMilli = 1253, priceMinor = null), parsed)
    }

    @Test
    fun parsesPriceLabels() {
        val t = ScaleTemplate("21IIIIIPPPPPC")
        assertEquals(ScaleCode("00042", null, 1590), t.parse(Gtin.withCheckDigit("210004201590")))
    }

    @Test
    fun rejectsLabelsThatDoNotFit() {
        val t = ScaleTemplate("20IIIIIWWWWWC")
        val good = Gtin.withCheckDigit("201234501253")
        val badCheck = good.dropLast(1) + ((good.last() - '0' + 1) % 10)
        assertNull(t.parse(badCheck))
        assertNull(t.parse(Gtin.withCheckDigit("221234501253"))) // other prefix
        assertNull(t.parse("20123450125")) // too short
        assertNull(t.parse("20A2345012530"))
    }

    @Test
    fun parseAnyTriesTemplatesInOrder() {
        val templates = ScaleTemplate.defaults()
        assertEquals(1590L, ScaleTemplate.parseAny(templates, Gtin.withCheckDigit("210004201590"))?.priceMinor)
        assertNull(ScaleTemplate.parseAny(templates, "9556001000013"))
    }

    @Test
    fun parseAllGivesEveryLayoutThatFits() {
        // Two scales: one prints a 5-digit PLU and the weight, the other a 6-digit PLU and the price.
        val templates = listOf(ScaleTemplate("20IIIIIWWWWWC"), ScaleTemplate("2IIIIIIPPPPPC"))
        val label = Gtin.withCheckDigit("201234501253")
        assertEquals(
            listOf(ScaleCode("12345", 1253, null), ScaleCode("012345", null, 1253)),
            ScaleTemplate.parseAll(templates, label),
        )
        val price = Gtin.withCheckDigit("210004201590")
        assertEquals(listOf(ScaleCode("00042", null, 1590)), ScaleTemplate.parseAll(ScaleTemplate.defaults(), price))
        assertEquals(emptyList(), ScaleTemplate.parseAll(templates, "9556001000013"))
    }

    @Test
    fun rejectsInvalidTemplates() {
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20WWWWWC") } // no item code
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20IIIIICWWWWW") } // C not last
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20IIIIIQQQQQC") } // bad char
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20IIIII") } // no W or P
    }
}
