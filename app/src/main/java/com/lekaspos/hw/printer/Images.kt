package com.lekaspos.hw.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lekaspos.core.escpos.MonoImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Bitmap ↔ printer-image conversions, the store logo file and QR codes. */
object Images {

    /** Luminance 0 (black) … 255 (white) per pixel; transparent pixels count as white paper. */
    fun gray(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            px[i] = (lum * a + 255 * (255 - a)) / 255
        }
        return px
    }

    fun toBitmap(img: MonoImage): Bitmap {
        val px = IntArray(img.width * img.height)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) px[y * img.width + x] = if (img.isBlack(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
    }

    /** QR code as printer dots, about [maxDots] wide (whole modules only). */
    fun qr(data: String, maxDots: Int): MonoImage? {
        val matrix = try {
            QRCodeWriter().encode(
                data, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        } catch (e: Exception) {
            return null
        }
        val modules = matrix.width
        val scale = (maxDots / modules).coerceIn(1, 8)
        val size = modules * scale
        val gray = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) gray[y * size + x] = if (matrix.get(x / scale, y / scale)) 0 else 255
        return MonoImage.fromGray(gray, size, size, dither = false)
    }

    // ------------------------------------------------------------------ store logo (this device)

    private const val LOGO_FILE = "receipt_logo.png"
    private const val LOGO_MAX_WIDTH = 576
    private const val LOGO_MAX_PIXELS = 2_000_000L

    fun logoFile(context: Context): File = File(context.filesDir, LOGO_FILE)

    fun hasLogo(context: Context): Boolean = logoFile(context).exists()

    /** Copies a picked image into app storage, scaled to at most 80 mm printer width. Blocking. */
    @Throws(IOException::class)
    fun saveLogo(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("cannot open image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        var sample = 1
        // By the width and by the pixel count: a long screenshot (1080 × 20,000) picked as the logo
        // was decoded whole (~86 MB) and ended the app (2026-10 review).
        while (bounds.outWidth / (sample * 2) >= LOGO_MAX_WIDTH ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > LOGO_MAX_PIXELS
        ) {
            sample *= 2
        }
        val decoded = try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: OutOfMemoryError) {
            throw IOException("the image is too large", e)
        } ?: throw IOException("cannot decode image")
        val scaled = if (decoded.width > LOGO_MAX_WIDTH) {
            Bitmap.createScaledBitmap(decoded, LOGO_MAX_WIDTH, decoded.height * LOGO_MAX_WIDTH / decoded.width, true)
        } else {
            decoded
        }
        val tmp = File(context.filesDir, "$LOGO_FILE.tmp")
        FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!tmp.renameTo(logoFile(context))) throw IOException("cannot save logo")
    }

    fun deleteLogo(context: Context) {
        logoFile(context).delete()
    }

    /** The logo dithered for a printer [dots] wide (at most half the paper height tall). Blocking. */
    fun logo(context: Context, dots: Int): MonoImage? {
        val f = logoFile(context)
        if (!f.exists()) return null
        val src = BitmapFactory.decodeFile(f.path) ?: return null
        try {
            var w = minOf(dots, src.width)
            var h = src.height * w / src.width
            if (h > dots / 2) {
                h = dots / 2
                w = src.width * h / src.height
            }
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled !== src) scaled.recycle()
            val img = MonoImage.fromGray(gray(bmp), w, h, dither = true)
            bmp.recycle()
            return img
        } finally {
            src.recycle()
        }
    }
}
