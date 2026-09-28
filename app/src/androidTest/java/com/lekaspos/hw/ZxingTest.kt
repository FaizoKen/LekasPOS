package com.lekaspos.hw

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.EAN13Writer
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.hw.camera.BarcodeDecoder
import com.lekaspos.hw.printer.Images
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ZXing is pinned to 3.3.3 because newer versions call Java 8 APIs missing below API 24
 * (D-025): these tests run on the API 21 emulator in CI.
 */
@RunWith(AndroidJUnit4::class)
class ZxingTest {

    /** A camera-like luminance frame [w]×[h] with [m] drawn in the middle. */
    private fun frame(m: BitMatrix, w: Int, h: Int, vertical: Boolean): ByteArray {
        val y = ByteArray(w * h) { 0xFF.toByte() }
        val mw = if (vertical) m.height else m.width
        val mh = if (vertical) m.width else m.height
        val left = (w - mw) / 2
        val top = (h - mh) / 2
        for (r in 0 until mh) {
            for (c in 0 until mw) {
                val black = if (vertical) m.get(r, m.height - 1 - c) else m.get(c, r)
                if (black) y[(top + r) * w + left + c] = 0
            }
        }
        return y
    }

    @Test
    fun decodesEan13FromAFrame() {
        val code = Gtin.withCheckDigit("955600123456") // a valid EAN-13
        val m = EAN13Writer().encode(code, BarcodeFormat.EAN_13, 380, 120)
        val decoder = BarcodeDecoder()
        assertEquals(code, decoder.decode(frame(m, 640, 480, vertical = false), 640, 480, rotate = false))
        // Portrait phone: the sensor sees the bars vertically; the decoder turns the frame first.
        assertEquals(code, decoder.decode(frame(m, 480, 640, vertical = true), 480, 640, rotate = true))
        assertEquals(null, decoder.decode(ByteArray(640 * 480) { 0xFF.toByte() }, 640, 480, rotate = false))
    }

    @Test
    fun encodesReceiptQrCodes() {
        val img = assertNotNull(Images.qr("https://e.example/r/AB12-0-000001?t=10.05", 192))
        assertTrue(img.width <= 192 && img.width == img.height)
        assertTrue(img.inkRows() > img.height / 2)
    }
}
