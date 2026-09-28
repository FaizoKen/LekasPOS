package com.lekaspos.core.escpos

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MonoImageTest {

    @Test
    fun thresholdPacksMostSignificantBitFirst() {
        val gray = IntArray(10 * 2) { 255 }
        gray[0] = 0
        gray[9] = 10
        gray[10 + 1] = 127
        val img = MonoImage.fromGray(gray, 10, 2, dither = false)
        assertEquals(2, img.bytesPerRow)
        assertEquals(0x80, img.data[0].toInt() and 0xFF)
        assertEquals(0x40, img.data[1].toInt() and 0xFF) // x = 9 → second byte, bit 6
        assertTrue(img.isBlack(1, 1))
        assertFalse(img.isBlack(2, 1))
        assertEquals(2, img.inkRows())
    }

    @Test
    fun ditheringKeepsTheToneOfGray() {
        val w = 64
        val h = 64
        val img = MonoImage.fromGray(IntArray(w * h) { 128 }, w, h, dither = true)
        var black = 0
        for (y in 0 until h) for (x in 0 until w) if (img.isBlack(x, y)) black++
        val ratio = black.toDouble() / (w * h)
        assertTrue(ratio in 0.4..0.6, "ratio $ratio")
        val white = MonoImage.fromGray(IntArray(w * h) { 255 }, w, h, dither = true)
        assertEquals(0, white.inkRows())
    }

    @Test
    fun paddingAndCentering() {
        val img = MonoImage(8, 1, byteArrayOf(0xFF.toByte()))
        val padded = img.padLeft(4)
        assertEquals(12, padded.width)
        assertEquals((0 until 12).map { it >= 4 }, (0 until 12).map { padded.isBlack(it, 0) })
        val centered = img.centered(32)
        assertEquals(20, centered.width)
        assertTrue(centered.isBlack(12, 0))
        assertFalse(centered.isBlack(11, 0))
        assertTrue(img.centered(8) === img)
    }

    @Test
    fun rowsCopiesABand() {
        val img = MonoImage.fromGray(IntArray(8 * 4) { if (it / 8 == 2) 0 else 255 }, 8, 4, dither = false)
        val band = img.rows(2, 2)
        assertEquals(2, band.height)
        assertTrue(band.isBlack(0, 0))
        assertFalse(band.isBlack(0, 1))
    }
}
