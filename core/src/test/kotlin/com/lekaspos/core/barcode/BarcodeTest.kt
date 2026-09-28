package com.lekaspos.core.barcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodeTest {

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

    @Test
    fun lookupVariantsCoverUpcAndEanForms() {
        assertEquals(listOf("036000291452", "0036000291452"), Gtin.lookupVariants("036000291452"))
        assertEquals(listOf("0036000291452", "036000291452"), Gtin.lookupVariants(" 0036000291452 "))
        assertEquals(listOf("9556001000013"), Gtin.lookupVariants("9556001000013"))
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
    fun rejectsInvalidTemplates() {
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20WWWWWC") } // no item code
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20IIIIICWWWWW") } // C not last
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20IIIIIQQQQQC") } // bad char
        assertFailsWith<IllegalArgumentException> { ScaleTemplate("20IIIII") } // no W or P
    }
}
