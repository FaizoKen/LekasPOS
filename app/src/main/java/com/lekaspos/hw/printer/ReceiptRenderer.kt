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

    /** Draws the lines; with [fromY]..[toY] only those near that band of the page (the rest is clipped anyway). */
    fun draw(
        canvas: Canvas,
        lines: List<PrintLine>,
        logo: MonoImage?,
        qr: MonoImage?,
        fromY: Float = Float.NEGATIVE_INFINITY,
        toY: Float = Float.POSITIVE_INFINITY,
    ) {
        var y = PADDING.toFloat()
        for (l in lines) {
            val adv = advance(l, lineH, logo, qr)
            // A generous margin: glyphs (accents, descenders) may reach a little outside their line.
            if (y > toY + lineH * 2f || y + adv < fromY - lineH * 2f) {
                y += adv
                continue
            }
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
            y += adv
        }
    }

    /** White bitmap with the receipt drawn on it (for sharing; printing uses [mono]). */
    fun bitmap(lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, height(lines, logo, qr), config)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        draw(canvas, lines, logo, qr)
        return bmp
    }

    /**
     * The receipt as printer dots (threshold: the text is black on white already). Drawn in bands of
     * [BAND_ROWS] rows into one small bitmap (2026-10 review): the whole page as a bitmap plus a
     * luminance array took 6 bytes per dot, tens of MB for a long receipt on a 1 GB phone.
     */
    fun mono(lines: List<PrintLine>, logo: MonoImage?, qr: MonoImage?): MonoImage {
        val h = height(lines, logo, qr)
        val bpr = (widthPx + 7) ushr 3
        val out = ByteArray(bpr * h)
        val bandH = minOf(BAND_ROWS, h)
        val band = Bitmap.createBitmap(widthPx, bandH, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(band)
            val px = IntArray(widthPx * bandH)
            var top = 0
            while (top < h) {
                val rows = minOf(bandH, h - top)
                band.eraseColor(Color.WHITE)
                canvas.save()
                canvas.translate(0f, -top.toFloat())
                draw(canvas, lines, logo, qr, top.toFloat(), (top + rows).toFloat())
                canvas.restore()
                band.getPixels(px, 0, widthPx, 0, 0, widthPx, rows)
                for (y in 0 until rows) {
                    val row = (top + y) * bpr
                    val src = y * widthPx
                    for (x in 0 until widthPx) {
                        val c = px[src + x]
                        if (c == Color.WHITE) continue // most of the page
                        // Same luminance and threshold as Images.gray + MonoImage.fromGray(threshold = 150).
                        val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                        if (lum < MONO_THRESHOLD) {
                            val i = row + (x ushr 3)
                            out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }
                top += rows
            }
        } finally {
            band.recycle()
        }
        return MonoImage(widthPx, h, out)
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

        /** Rows per band in [mono]: 576 dots × 256 rows is under 1 MB of bitmap and pixel buffer. */
        const val BAND_ROWS = 256
        const val MONO_THRESHOLD = 150

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
