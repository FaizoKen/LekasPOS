package com.lekaspos.core.text

import kotlin.test.assertEquals
import org.junit.Test

class TextWidthTest {

    @Test
    fun widths() {
        assertEquals(5, TextWidth.of("hello"))
        assertEquals(4, TextWidth.of("牛奶"))
        assertEquals(1, TextWidth.of("é")) // e + combining acute
        assertEquals(6, TextWidth.of("Ｍｉｌｏ".substring(0, 3)))
        assertEquals(0, TextWidth.of("​"))
    }

    @Test
    fun takeNeverSplitsWideCharacters() {
        assertEquals("牛", TextWidth.take("牛奶", 3))
        assertEquals("ab", TextWidth.take("abc", 2))
        assertEquals("", TextWidth.take("牛", 1))
    }

    @Test
    fun wrapBreaksAtSpaces() {
        assertEquals(listOf("Milo Activ-Go", "1kg pack"), TextWidth.wrap("Milo Activ-Go 1kg pack", 14))
        assertEquals(listOf(""), TextWidth.wrap("", 10))
        assertEquals(listOf("a b"), TextWidth.wrap("  a   b ", 10))
    }

    @Test
    fun wrapSplitsLongWordsAndCjk() {
        assertEquals(listOf("abcde", "fghij", "k"), TextWidth.wrap("abcdefghijk", 5))
        assertEquals(listOf("牛奶", "牛奶", "牛奶"), TextWidth.wrap("牛奶牛奶牛奶", 5))
        assertEquals(listOf("牛"), TextWidth.wrap("牛", 1)) // wider than the line: still progresses
    }

    @Test
    fun padding() {
        assertEquals("ab   ", TextWidth.padEnd("ab", 5))
        assertEquals("   ab", TextWidth.padStart("ab", 5))
        assertEquals(" 牛奶  ", TextWidth.center("牛奶", 7))
        assertEquals("toolong", TextWidth.padEnd("toolong", 3))
    }
}
