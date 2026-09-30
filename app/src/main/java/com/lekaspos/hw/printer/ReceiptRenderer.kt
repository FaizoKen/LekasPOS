package com.lekaspos.hw.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.lekaspos.core.escpos.MonoImage
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
import kotlin.math.ceil

/**
 * Draws laid-out receipt lines on a Canvas: a bitmap for image-mode printing and sharing, or a
 * PDF page. Text is placed on the same column grid as the printer's own font; runs separated
 * by two or more spaces start at their grid column, so amounts line up even for scripts whose
 * glyphs are wider or narrower than a column (Chinese, Tamil…).
 */
class ReceiptRenderer(private val cols: Int, private val widthPx: Int) {

    private val cell = widthPx.toFloat() / cols
    private val lineH = cell * 2f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Color.BLACK
        textSize = 20f
        textSize = 20f * cell / measureText("M")
    }
    private val baseSize = paint.textSize
    private val imagePaint = Paint()

    fun height(lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?): Int = heightOf(lines, lineH, logo, qr)

    fun draw(canvas: Canvas, lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?) {
        var y = PADDING.toFloat()
        for (l in lines) {
            when (l) {
                is PrintLine.Text -> {
                    val scale = if (l.big) 2f else 1f
                    paint.textSize = baseSize * scale
                    paint.isFakeBoldText = l.bold
                    val baseline = y + lineH * scale * 0.78f
                    for ((col, run) in runs(l.text)) canvas.drawText(run, col * cell * scale, baseline, paint)
                }
                PrintLine.Logo -> if (logo != null) drawMono(canvas, logo, y)
                is PrintLine.Qr -> if (qr != null) drawMono(canvas, qr, y)
                is PrintLine.Feed -> Unit
            }
            y += advance(l, lineH, logo, qr)
        }
    }

    /** White bitmap with the receipt drawn on it (ARGB for sharing, RGB_565 for printing). */
    fun bitmap(lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, height(lines, logo, qr), config)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        draw(canvas, lines, logo, qr)
        return bmp
    }

    /** The receipt as printer dots (threshold: the text is black on white already). */
    fun mono(lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?): MonoImage {
        val bmp = bitmap(lines, logo, qr, Bitmap.Config.RGB_565)
        try {
            return MonoImage.fromGray(Images.gray(bmp), bmp.width, bmp.height, dither = false, threshold = 150)
        } finally {
            bmp.recycle()
        }
    }

    private fun drawMono(canvas: Canvas, img: MonoImage, y: Float) {
        val bmp = Images.toBitmap(img)
        val x = ((widthPx - img.width) / 2).coerceAtLeast(0).toFloat()
        canvas.drawBitmap(bmp, x, y, imagePaint)
        bmp.recycle()
    }

    companion object {
        private const val PADDING = 8
        private const val GAP = 8

        /** How far [l] moves down the page; [draw] and [heightOf] share it so the page fits every line. */
        fun advance(l: PrintLine, lineH: Float, logo: MonoImage?, qr: MonoImage?): Float = when (l) {
            is PrintLine.Text -> if (l.big) lineH * 2f else lineH
            PrintLine.Logo -> if (logo != null) (logo.height + GAP).toFloat() else 0f
            is PrintLine.Qr -> if (qr != null) (qr.height + GAP).toFloat() else 0f
            is PrintLine.Feed -> lineH * l.lines
        }

        /**
         * Page height in pixels. Summed in floats exactly as [draw] advances: rounding each line down
         * lost 0.38 px per line at 42 columns, and the last lines of a long receipt were cut off.
         */
        fun heightOf(lines: List<PrintLine>, lineH: Float, logo: MonoImage?, qr: MonoImage?): Int {
            var y = PADDING.toFloat()
            for (l in lines) y += advance(l, lineH, logo, qr)
            return ceil(y).toInt() + PADDING
        }

        /** Splits a padded line into (start column, text) runs at gaps of 2+ spaces. */
        fun runs(text: String): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>(3)
            val n = text.length
            var i = 0
            var col = 0
            while (i < n) {
                while (i < n && text[i] == ' ') {
                    i++
                    col++
                }
                if (i >= n) break
                val start = i
                val startCol = col
                while (i < n) {
                    if (text[i] == ' ' && (i + 1 == n || text[i + 1] == ' ')) break
                    val cp = text.codePointAt(i)
                    col += TextWidth.of(cp)
                    i += Character.charCount(cp)
                }
                out.add(startCol to text.substring(start, i))
            }
            return out
        }
    }
}
