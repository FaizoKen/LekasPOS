package com.lekaspos.hw.printer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.escpos.MonoImage
import com.lekaspos.core.receipt.PrintLine
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Picture receipts are drawn in bands of [ReceiptRenderer.BAND_ROWS] rows (2026-10 review, to keep a
 * long receipt from taking tens of MB): the dots must match drawing the whole page at once.
 */
@RunWith(AndroidJUnit4::class)
class BandedRenderTest {

    /** The page drawn whole, then thresholded: how [ReceiptRenderer.mono] worked before the bands. */
    private fun whole(r: ReceiptRenderer, lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?): MonoImage {
        val bmp = r.bitmap(lines, logo, qr, Bitmap.Config.RGB_565)
        try {
            val gray = Images.gray(bmp)
            return MonoImage.fromGray(gray, bmp.width, bmp.height, dither = false, threshold = ReceiptRenderer.MONO_THRESHOLD)
        } finally {
            bmp.recycle()
        }
    }

    private fun receipt(): List<PrintLine> {
        val out = ArrayList<PrintLine>()
        out.add(PrintLine.Logo)
        out.add(PrintLine.Text("   KEDAI RUNCIT", bold = true, big = true))
        out.add(PrintLine.Text("=".repeat(32)))
        for (i in 1..60) {
            out.add(PrintLine.Text("Item $i Café gula 1kg".padEnd(27) + "$i.90"))
            if (i % 7 == 0) out.add(PrintLine.Text("  2 x 4.20".padEnd(28) + "8.40", bold = true))
        }
        out.add(PrintLine.Text("TOTAL".padEnd(9) + "RM123.45", bold = true, big = true))
        out.add(PrintLine.Feed(1))
        out.add(PrintLine.Qr("https://e.example/r/AB12-0-000001?t=123.45"))
        out.add(PrintLine.Text("      Terima kasih!"))
        return out
    }

    @Test
    fun bandsMatchTheWholePage() {
        val r = ReceiptRenderer(32, 384)
        val stripes = IntArray(200 * 60) { if ((it / 200 + it % 200) % 9 < 4) 0 else 255 }
        val logo = MonoImage.fromGray(stripes, 200, 60, dither = false)
        val qr = assertNotNull(Images.qr("https://e.example/r/AB12-0-000001?t=123.45", 192))
        val lines = receipt()
        val banded = r.mono(lines, logo, qr)
        val full = whole(r, lines, logo, qr)
        assertTrue(banded.height > ReceiptRenderer.BAND_ROWS * 3, "the test needs several bands, got ${banded.height} rows")
        assertEquals(full.width, banded.width)
        assertEquals(full.height, banded.height)
        var differ = 0
        var ink = 0
        for (i in full.data.indices) {
            differ += Integer.bitCount((full.data[i].toInt() xor banded.data[i].toInt()) and 0xFF)
            ink += Integer.bitCount(full.data[i].toInt() and 0xFF)
        }
        // Rendering is deterministic, so this is expected to be 0; a band drawn at the wrong offset would
        // differ by thousands of dots.
        assertTrue(ink > 10_000, "too little ink: $ink")
        assertTrue(differ * 1000 <= ink, "$differ of $ink dots differ")
    }

    @Test
    fun aPageShorterThanOneBand() {
        val r = ReceiptRenderer(48, 576)
        val lines = listOf(PrintLine.Text("Susu".padEnd(44) + "3.85"))
        val banded = r.mono(lines, null, null)
        assertEquals(whole(r, lines, null, null).data.toList(), banded.data.toList())
    }
}
