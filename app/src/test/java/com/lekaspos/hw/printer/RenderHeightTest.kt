package com.lekaspos.hw.printer

import com.lekaspos.core.escpos.MonoImage
import com.lekaspos.core.receipt.PrintLine
import kotlin.test.assertTrue
import org.junit.Test

/** The image receipt's page is tall enough for every line (ReceiptRenderer.heightOf). */
class RenderHeightTest {

    private val padding = 8f

    @Test
    fun longReceiptsKeepTheirLastLineAt42Columns() {
        val lineH = 512f / 42 * 2f // 24.38 px: rounding each line down used to lose 0.38 px per line
        val lines = List(60) { PrintLine.Text("line $it") }
        val bottom = padding + 60 * lineH
        val h = ReceiptRenderer.heightOf(lines, lineH, null, null)
        assertTrue(h >= bottom + padding - 0.5f, "height $h cuts the last line (ends at $bottom)")
        assertTrue(h <= bottom + padding + 1f, "height $h is taller than needed")
    }

    @Test
    fun logoQrBigTextAndFeedsAreCounted() {
        val lineH = 384f / 32 * 2f
        val logo = MonoImage.blank(200, 50)
        val qr = MonoImage.blank(192, 192)
        val lines = listOf(
            PrintLine.Logo, PrintLine.Text("SHOP", big = true), PrintLine.Feed(2), PrintLine.Qr("x"), PrintLine.Text("end"),
        )
        val bottom = padding + (50 + 8) + lineH * 2 + lineH * 2 + (192 + 8) + lineH
        val h = ReceiptRenderer.heightOf(lines, lineH, logo, qr)
        assertTrue(h >= bottom + padding - 0.5f, "height $h cuts content ending at $bottom")
        assertTrue(h <= bottom + padding + 1f, "height $h is taller than needed")
        // Without the pictures nothing is drawn for them, and no room is kept.
        val bare = ReceiptRenderer.heightOf(lines, lineH, null, null)
        assertTrue(bare <= padding * 2 + lineH * 5 + 1f, "height $bare")
    }
}
