package com.lekaspos.ui.products

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.testing.TestDb
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** D-066: any photo or picture becomes a small square JPEG the tiles can show. */
@RunWith(AndroidJUnit4::class)
class PicturesTest {

    private val dir = File(TestDb.context.cacheDir, "pictures-test").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, b: Bitmap, format: Bitmap.CompressFormat): File =
        File(dir, name).also { f -> FileOutputStream(f).use { b.compress(format, 90, it) } }

    @Test
    fun aLargePhotoBecomesASmallSquare() {
        val photo = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val stored = Pictures.fromFile(file("photo.jpg", photo, Bitmap.CompressFormat.JPEG))
        assertTrue(stored.length < 20_000, "a picture is small: ${stored.length}")
        val shown = assertNotNull(Pictures.decode(stored))
        assertEquals(Pictures.SIDE, shown.width)
        assertEquals(Pictures.SIDE, shown.height)
        val c = shown.getPixel(Pictures.SIDE / 2, Pictures.SIDE / 2)
        assertTrue(Color.red(c) > 200 && Color.green(c) < 60, "still red: ${Integer.toHexString(c)}")
    }

    @Test
    fun aTransparentPictureIsOnWhite() {
        val logo = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val shown = assertNotNull(Pictures.decode(Pictures.fromFile(file("logo.png", logo, Bitmap.CompressFormat.PNG))))
        val c = shown.getPixel(5, 5)
        assertTrue(Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240, "white, not black: ${Integer.toHexString(c)}")
    }

    @Test
    fun whatIsNoPictureIsSaidSo() {
        val text = File(dir, "notes.txt").apply { writeText("not a picture") }
        assertFailsWith<Pictures.Unreadable> { Pictures.fromFile(text) }
        assertEquals(null, Pictures.decode("not base64 at all!"))
    }
}
