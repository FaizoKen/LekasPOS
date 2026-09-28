package com.lekaspos.core.escpos

import com.lekaspos.core.receipt.PrintLine
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class EscPosTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun basicCommands() {
        val b = EscPos().init().bold(true).size(doubleWidth = true, doubleHeight = true).cut().bytes()
        assertEquals("1B 40 1B 45 01 1D 21 11 1D 56 42 00", hex(b))
        assertEquals("1B 70 00 19 FA", hex(EscPos().drawer(DrawerPulse()).bytes()))
        assertEquals("1B 70 01 19 FA", hex(EscPos().drawer(DrawerPulse(pin = 1)).bytes()))
        assertEquals("1B 64 04", hex(EscPos().feed(4).bytes()))
    }

    @Test
    fun rasterIsSentInBands() {
        val img = MonoImage.blank(16, 3)
        assertEquals("1D 76 30 00 02 00 03 00 00 00 00 00 00 00", hex(EscPos().raster(img).bytes()))
        val tall = MonoImage.blank(8, 300)
        val b = EscPos().raster(tall).bytes()
        assertEquals(3 * 8 + 300, b.size) // three band headers (128 + 128 + 44 rows)
        assertEquals(44, b[128 + 8 + 128 + 8 + 6].toInt())
    }

    @Test
    fun qrCommandCarriesTheDataLength() {
        val data = "LEKAS".toByteArray()
        val b = EscPos().qr(data).bytes()
        assertTrue(hex(b).startsWith("1D 28 6B 04 00 31 41 32 00 1D 28 6B 03 00 31 43 06 1D 28 6B 03 00 31 45 31"))
        val store = indexOf(b, bytes(0x1D, 0x28, 0x6B, 8, 0, 0x31, 0x50, 0x30))
        assertTrue(store > 0)
        assertContentEquals(data, b.copyOfRange(store + 8, store + 8 + data.size))
        assertTrue(hex(b).endsWith("1D 28 6B 03 00 31 51 30"))
    }

    @Test
    fun latinTextLosesAccentsOnly() {
        assertEquals("Cafe x2 - Nasi Lemak", String(EscPosText.encode("Café ×2 – Nasi Lemak", TextMode.LATIN), Charsets.US_ASCII))
        assertEquals("??", String(EscPosText.encode("牛奶", TextMode.LATIN), Charsets.US_ASCII))
        assertEquals("ab", String(EscPosText.encode("a\nb", TextMode.LATIN), Charsets.US_ASCII))
    }

    @Test
    fun canEncodeTellsWhenAnImageIsNeeded() {
        assertTrue(EscPosText.canEncode("Café ×2 – RM1.00", TextMode.LATIN))
        assertEquals(false, EscPosText.canEncode("牛奶", TextMode.LATIN))
        assertTrue(EscPosText.canEncode("Susu 牛奶", TextMode.GB18030))
        assertEquals(false, EscPosText.canEncode("பால்", TextMode.GB18030)) // Tamil: image only
    }

    @Test
    fun chineseModeUsesGb18030() {
        val b = EscPosText.encode("Susu 牛奶", TextMode.GB18030)
        val cjk = "牛奶".toByteArray(java.nio.charset.Charset.forName("GB18030"))
        assertEquals(4, cjk.size)
        assertEquals("53 75 73 75 20 " + hex(cjk), hex(b))
    }

    @Test
    fun receiptJob() {
        val lines = listOf(
            PrintLine.Text("  SHOP", bold = true, big = true),
            PrintLine.Text("Item            1.00"),
            PrintLine.Qr("x"),
        )
        val p = PrinterProfile(cols = 32, dots = 384, cut = true, feedLines = 3)
        val b = ReceiptEncoder.text(lines, p, drawer = DrawerPulse())
        assertEquals("1B 40 1B 70 00 19 FA 1B 74 00 1B 45 01 1D 21 11", hex(b.copyOfRange(0, 16)))
        assertTrue(indexOf(b, bytes(0x1D, 0x21, 0x00, 0x1B, 0x45, 0x00)) > 0)
        assertTrue(indexOf(b, bytes(0x1D, 0x28, 0x6B)) < 0) // no native QR unless enabled
        assertTrue(hex(b).endsWith("1B 64 03 1D 56 42 00"))

        val native = ReceiptEncoder.text(lines, p.copy(nativeQr = true, cut = false, textMode = TextMode.GB18030))
        assertTrue(indexOf(native, bytes(0x1C, 0x26)) > 0)
        assertTrue(indexOf(native, bytes(0x1D, 0x28, 0x6B)) > 0)
        assertTrue(indexOf(native, bytes(0x1D, 0x56)) < 0)
    }

    @Test
    fun copiesRepeatTheBody() {
        val lines = listOf(PrintLine.Text("A"))
        val one = ReceiptEncoder.text(lines, PrinterProfile(feedLines = 0, cut = false))
        val two = ReceiptEncoder.text(lines, PrinterProfile(feedLines = 0, cut = false), copies = 2)
        assertEquals(one.size + 2, two.size) // "A\n" once more
    }

    @Test
    fun imageJobAndDrawerOnly() {
        val img = MonoImage.blank(384, 10)
        val b = ReceiptEncoder.image(img, PrinterProfile(dots = 384, feedLines = 2, cut = false))
        assertEquals(2 + 8 + 48 * 10 + 3, b.size)
        assertEquals("1B 40 1B 70 00 19 FA", hex(ReceiptEncoder.drawerOnly(DrawerPulse())))
    }
}
