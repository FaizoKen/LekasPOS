package com.lekaspos.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTextTest {

    @Test
    fun stripsLatinAccentsAndLowercases() {
        assertEquals("cafe creme", SearchText.normalize("Café Crème"))
        assertEquals("nestle", SearchText.normalize("NESTLÉ"))
    }

    @Test
    fun punctuationSeparatesTokens() {
        assertEquals("coca cola 1 5l", SearchText.normalize("Coca-Cola 1.5L"))
        assertEquals("milo activ go", SearchText.normalize("  MILO   Activ-Go  "))
        assertEquals("f n 100 plus", SearchText.normalize("F&N (100 Plus)"))
        assertEquals("", SearchText.normalize("--- ..."))
    }

    @Test
    fun cjkBecomesOneTokenPerCharacter() {
        assertEquals("李 锦 记 蚝 油", SearchText.normalize("李锦记 蚝油"))
        assertEquals("nestle 牛 奶", SearchText.normalize("Nestlé牛奶"))
        assertEquals("牛 奶 1kg", SearchText.normalize("牛奶1kg"))
    }

    @Test
    fun otherScriptsKeepTheirVowelSigns() {
        assertEquals("தமிழ்", SearchText.normalize("தமிழ்"))
        assertEquals("ไข่ไก่", SearchText.normalize("ไข่ไก่"))
    }

    @Test
    fun ftsQueryRequiresEveryTokenAsPrefix() {
        assertEquals("milo* 1k*", SearchText.ftsQuery("Milo 1k"))
        assertEquals("李* 锦*", SearchText.ftsQuery("李锦"))
        assertNull(SearchText.ftsQuery("   "))
        assertNull(SearchText.ftsQuery("*-\"()"))
    }

    @Test
    fun ftsQueryCannotInjectOperators() {
        // FTS operators are uppercase or punctuation; normalization removes both
        assertEquals("a* or* b* near* c*", SearchText.ftsQuery("a OR b NEAR \"c\""))
        assertEquals("x* not* y*", SearchText.ftsQuery("-x NOT y*"))
    }

    @Test
    fun ftsQueryIsCappedAtSixTokens() {
        assertEquals("a* b* c* d* e* f*", SearchText.ftsQuery("a b c d e f g h"))
    }

    @Test
    fun sortKeyMatchesNormalization() {
        assertEquals("abc", SearchText.key("Ábc"))
    }

    @Test
    fun recognisesSingleCjkCharacterTokens() {
        assertTrue(SearchText.isCjkChar("奶"))
        assertTrue(SearchText.isCjkChar("𠀀")) // supplementary plane (surrogate pair)
        assertFalse(SearchText.isCjkChar("m"))
        assertFalse(SearchText.isCjkChar("牛奶"))
        assertFalse(SearchText.isCjkChar(""))
    }
}
