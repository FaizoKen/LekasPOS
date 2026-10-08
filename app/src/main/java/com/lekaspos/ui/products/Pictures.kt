package com.lekaspos.ui.products

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * Product pictures (D-066): a photo or any picture becomes a square JPEG of [SIDE] pixels, kept as
 * base64 text in the database (about 13 KB). Its middle square is kept, turned upright, on white
 * (a transparent PNG would turn black in a JPEG). Off the main thread.
 */
object Pictures {
    const val SIDE = 240
    private const val QUALITY = 80

    /** Larger "pictures" are not pictures a shop takes of a product. */
    private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

    /** Not a picture this phone can read (or too large to). */
    class Unreadable(cause: Throwable? = null) : Exception(cause)

    /** Where the camera app writes a new photo (FileProvider path `photos/`). */
    fun captureFile(ctx: Context): File = File(File(ctx.cacheDir, "photos").apply { mkdirs() }, "capture.jpg")

    /** A picked picture ([uri]) as a stored one. Copied to a file first: the EXIF turn needs a path below Android 7. */
    fun fromUri(ctx: Context, uri: Uri): String {
        val tmp = File(File(ctx.cacheDir, "photos").apply { mkdirs() }, "picked.tmp")
        try {
            val input = try {
                ctx.contentResolver.openInputStream(uri)
            } catch (e: Exception) { // SecurityException, FileNotFoundException: a file Drive could not fetch
                throw Unreadable(e)
            } ?: throw Unreadable()
            input.use { copy(it, tmp) }
            return fromFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun copy(input: InputStream, to: File) {
        FileOutputStream(to).use { out ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_SOURCE_BYTES) throw Unreadable()
                out.write(buf, 0, n)
            }
        }
    }

    /** [file] (a photo or picture) as a stored picture: base64 of a [SIDE] × [SIDE] JPEG. */
    fun fromFile(file: File): String {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw Unreadable()
            var sample = 1
            while (min(w, h) / (sample * 2) >= SIDE) sample *= 2
            val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw Unreadable()
            val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            try {
                val side = min(src.width, src.height).toFloat()
                val m = Matrix()
                m.postTranslate(-src.width / 2f, -src.height / 2f)
                m.postRotate(turn(file).toFloat())
                m.postScale(SIDE / side, SIDE / side)
                m.postTranslate(SIDE / 2f, SIDE / 2f)
                val canvas = Canvas(out)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                val bytes = ByteArrayOutputStream(32 * 1024)
                if (!out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)) throw Unreadable()
                return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            } finally {
                out.recycle()
                src.recycle()
            }
        } catch (e: OutOfMemoryError) {
            throw Unreadable(e)
        }
    }

    /**
     * Degrees the photo must be turned to stand upright (its EXIF orientation; mirrored ones as their turn).
     * The platform reader, on the app's own copy of the file: androidx's would be a new library for one tag.
     */
    @SuppressLint("ExifInterface")
    private fun turn(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    /** A stored picture for the screen (16-bit: half the memory of a full-colour one), or null if it is damaged. */
    fun decode(data: String): Bitmap? = try {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
