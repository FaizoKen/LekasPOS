package com.lekaspos.core.escpos

import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
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
        assertEquals("????", String(EscPosText.encode("牛奶", TextMode.LATIN), Charsets.US_ASCII)) // one '?' per column
        assertEquals("ab", String(EscPosText.encode("a\nb", TextMode.LATIN), Charsets.US_ASCII))
        assertEquals("Cafe", String(EscPosText.encode("Cafe\u0301", TextMode.LATIN), Charsets.US_ASCII)) // decomposed é
    }

    @Test
    fun canEncodeTellsWhenAnImageIsNeeded() {
        assertTrue(EscPosText.canEncode("Café ×2 – RM1.00", TextMode.LATIN))
        assertTrue(EscPosText.canEncode("Cafe\u0301 a\u200Bb", TextMode.LATIN))
        assertEquals(false, EscPosText.canEncode("牛奶", TextMode.LATIN))
        assertTrue(EscPosText.canEncode("Susu 牛奶", TextMode.GB18030))
        assertEquals(false, EscPosText.canEncode("பால்", TextMode.GB18030)) // Tamil: image only
        assertEquals(false, EscPosText.canEncode("Tiket €5", TextMode.LATIN)) // no one-column stand-in
        assertEquals(false, EscPosText.canEncode("Tea £2", TextMode.LATIN))
    }

    /** 2026-10 review: a brand mark in a product's name made every receipt with it a picture. */
    @Test
    fun brandMarksPrintAsTextInTheirColumn() {
        val name = "MILO® 1kg Nescafé™ 5°C"
        assertTrue(EscPosText.canEncode(name, TextMode.LATIN))
        val bytes = EscPosText.encode(name, TextMode.LATIN)
        assertEquals("MILO  1kg Nescafe  5oC", String(bytes, Charsets.US_ASCII))
        assertEquals(com.lekaspos.core.text.TextWidth.of(name), bytes.size) // the column count is kept
    }

    @Test
    fun textKeepsTheColumnCountOfTheLayout() {
        // The layout measures with TextWidth; the printer must get exactly that many columns.
        val lines = listOf(
            "Coklat… ×2 – “Best” ‘buy’ • ¥1 · x\u00A0y", "Café crème", "Cafe\u0301 soft\u00ADhyphen", "牛奶 Susu 1L",
            "Kuih 😀 raya",
        )
        for (s in lines) {
            val latin = EscPosText.encode(s, TextMode.LATIN)
            assertEquals(TextWidth.of(s), latin.size, "'$s' in LATIN mode")
        }
        val ascii = String(EscPosText.encode(lines[0], TextMode.LATIN), Charsets.US_ASCII)
        assertEquals("Coklat. x2 - \"Best\" 'buy' * Y1 . x y", ascii)
    }

    @Test
    fun chineseModeSendsOnlyTwoByteCharacters() {
        // Emoji and CJK extension B are four-byte GB18030: GBK-only printers print garbage, so a picture.
        assertEquals(false, EscPosText.canEncode("Susu 😀", TextMode.GB18030))
        assertEquals(false, EscPosText.canEncode("\uD840\uDC00", TextMode.GB18030)) // U+20000
        assertEquals("a?b", String(EscPosText.encode("a😀b", TextMode.GB18030), Charsets.US_ASCII))
        assertEquals("a??b", String(EscPosText.encode("a\uD840\uDC00b", TextMode.GB18030), Charsets.US_ASCII))
        val milk = "牛奶 Susu"
        assertEquals(TextWidth.of(milk), EscPosText.encode(milk, TextMode.GB18030).size)
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
