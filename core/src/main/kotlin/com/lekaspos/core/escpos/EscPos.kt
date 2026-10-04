package com.lekaspos.core.escpos

import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer

/** How text bytes are produced for the printer. */
enum class TextMode {
    /** ASCII; other Latin letters lose their accents, anything else prints as '?'. */
    LATIN,

    /** Chinese-capable printers: CJK characters as GB18030 double-byte codes (FS &). */
    GB18030,
}

/** ESC p cash-drawer pulse. [pin] 0 = connector pin 2, 1 = pin 5. */
data class DrawerPulse(val pin: Int = 0, val onMs: Int = 50, val offMs: Int = 500) {
    init {
        require(pin == 0 || pin == 1) { "pin must be 0 or 1" }
        require(onMs in 2..510 && offMs in 2..510) { "pulse times out of range" }
    }
}

/** What the receipt encoder needs to know about a printer. */
data class PrinterProfile(
    /** Characters per line in the normal font (58 mm: 32; 80 mm: 48 or 42). */
    val cols: Int = 32,
    /** Printable dots per line (58 mm: 384; 80 mm: 576 or 512). */
    val dots: Int = 384,
    val textMode: TextMode = TextMode.LATIN,
    /** `ESC t n` code page for LATIN mode (0 = PC437, the power-on default of most printers). */
    val codePage: Int = 0,
    val cut: Boolean = true,
    /** Blank lines fed after the receipt so it can be torn off. */
    val feedLines: Int = 4,
    /** Print QR codes with the printer's own `GS ( k` command instead of a raster image. */
    val nativeQr: Boolean = false,
)

/** ESC/POS command builder (Epson-compatible subset understood by common 58/80 mm printers). */
class EscPos(initialSize: Int = 2048) {
    private val out = ByteArrayOutputStream(initialSize)

    fun init(): EscPos = raw(ESC, '@'.code)

    fun codePage(n: Int): EscPos = raw(ESC, 't'.code, n and 0xFF)

    fun chineseMode(on: Boolean): EscPos = if (on) raw(FS, '&'.code) else raw(FS, '.'.code)

    /** 0 left, 1 centre, 2 right. */
    fun align(a: Int): EscPos = raw(ESC, 'a'.code, a)

    fun bold(on: Boolean): EscPos = raw(ESC, 'E'.code, if (on) 1 else 0)

    fun size(doubleWidth: Boolean, doubleHeight: Boolean): EscPos =
        raw(GS, '!'.code, (if (doubleWidth) 0x10 else 0) or (if (doubleHeight) 0x01 else 0))

    fun text(bytes: ByteArray): EscPos {
        out.write(bytes, 0, bytes.size)
        return this
    }

    fun lf(): EscPos = raw(LF)

    fun feed(lines: Int): EscPos = if (lines <= 0) this else raw(ESC, 'd'.code, lines.coerceAtMost(255))

    /** Feed to the cutter and cut (GS V 66 0); printers without a cutter ignore it. */
    fun cut(): EscPos = raw(GS, 'V'.code, 66, 0)

    fun drawer(p: DrawerPulse): EscPos = raw(ESC, 'p'.code, p.pin, p.onMs / 2, p.offMs / 2)

    /** GS v 0 raster image, sent in bands so small printer buffers keep up. */
    fun raster(img: MonoImage, bandRows: Int = RASTER_BAND_ROWS): EscPos {
        val bpr = img.bytesPerRow
        var y = 0
        while (y < img.height) {
            val rows = minOf(bandRows, img.height - y)
            raw(GS, 'v'.code, '0'.code, 0, bpr and 0xFF, bpr ushr 8, rows and 0xFF, rows ushr 8)
            out.write(img.data, y * bpr, rows * bpr)
            y += rows
        }
        return this
    }

    /** QR code (model 2) printed by the printer itself: GS ( k functions 165, 167, 169, 180, 181. */
    fun qr(data: ByteArray, moduleDots: Int = 6, errorLevel: Char = 'M'): EscPos {
        val ec = when (errorLevel) {
            'L' -> 48
            'Q' -> 50
            'H' -> 51
            else -> 49
        }
        raw(GS, '('.code, 'k'.code, 4, 0, 49, 65, 50, 0)
        raw(GS, '('.code, 'k'.code, 3, 0, 49, 67, moduleDots.coerceIn(1, 16))
        raw(GS, '('.code, 'k'.code, 3, 0, 49, 69, ec)
        val len = data.size + 3
        raw(GS, '('.code, 'k'.code, len and 0xFF, len ushr 8, 49, 80, 48)
        out.write(data, 0, data.size)
        raw(GS, '('.code, 'k'.code, 3, 0, 49, 81, 48)
        return this
    }

    fun raw(vararg bytes: Int): EscPos {
        for (b in bytes) out.write(b)
        return this
    }

    fun size(): Int = out.size()

    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        const val ESC = 0x1B
        const val GS = 0x1D
        const val FS = 0x1C
        const val LF = 0x0A
        const val RASTER_BAND_ROWS = 128
    }
}

/** Text → printer bytes. */
object EscPosText {

    private val gb18030: Charset? = try {
        Charset.forName("GB18030")
    } catch (e: Exception) {
        null
    }

    // One character each: the layout gives every character one column, so "..." for "…" would push
    // the amount off the line. € and £ have no one-character stand-in: Auto prints them as a picture.
    private val replacements = mapOf(
        '×' to 'x', '–' to '-', '—' to '-', '‘' to '\'', '’' to '\'', '“' to '"', '”' to '"',
        '•' to '*', '…' to '.', '¥' to 'Y', '\u00A0' to ' ', '·' to '.',
        // A distributor's product list brings brand marks ("MILO® 1kg"): as a space they keep the
        // column count; one of them turned every receipt with that product into a picture, about 30
        // times the data and slow on a Bluetooth printer (2026-10 review).
        '®' to ' ', '™' to ' ', '©' to ' ', '°' to 'o', '²' to '2', '³' to '3', '¹' to '1', 'µ' to 'u',
    )

    /** Printer bytes of [s]: one byte per column, two for a Chinese character in GB18030 mode. */
    fun encode(s: String, mode: TextMode): ByteArray {
        val out = ByteArrayOutputStream(s.length + 8)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val wide = if (mode == TextMode.GB18030) doubleByte(cp) else null
            when {
                cp in 0x20..0x7E -> out.write(cp)
                wide != null -> out.write(wide, 0, wide.size)
                else -> latin(cp, out)
            }
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    /** True when [encode] would print every character of [s] (nothing becomes '?'). */
    fun canEncode(s: String, mode: TextMode): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x20..0x7E || cp < 0x20) continue
            if (TextWidth.of(cp) == 0) continue // combining accents, zero-width marks: dropped
            if (mode == TextMode.GB18030 && doubleByte(cp) != null) continue
            if (cp <= 0xFFFF && replacements.containsKey(cp.toChar())) continue
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            if (decomposed.none { it.code in 0x20..0x7E }) return false
        }
        return true
    }

    /**
     * GB18030 bytes of [cp] when a Chinese printer prints it as one two-column character: only the
     * two-byte (GBK) area. Four-byte codes (emoji, rare CJK, Korean) are garbage on GBK-only
     * printers and would break the column count, so they are left to the picture.
     */
    private fun doubleByte(cp: Int): ByteArray? {
        val cs = gb18030 ?: return null
        if (cp < 0x2E80 || cp > 0xFFFF || TextWidth.of(cp) != 2) return null
        val b = cp.toChar().toString().toByteArray(cs)
        return if (b.size == 2) b else null
    }

    private fun latin(cp: Int, out: ByteArrayOutputStream) {
        if (cp < 0x20) return // control characters never reach the printer
        val width = TextWidth.of(cp)
        if (width == 0) return // a combining accent or zero-width mark: the base letter is enough
        if (cp <= 0xFFFF) {
            replacements[cp.toChar()]?.let { r ->
                out.write(r.code)
                return
            }
        }
        // é → e, ñ → n: keep the ASCII base letter of a decomposable character.
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        var wrote = false
        for (c in decomposed) {
            if (c.code in 0x20..0x7E) {
                out.write(c.code)
                wrote = true
            }
        }
        // One '?' per column, so the amounts after it stay in their place.
        if (!wrote) repeat(width) { out.write('?'.code) }
    }
}

/** Turns laid-out receipt lines into one ESC/POS job. */
object ReceiptEncoder {

    /**
     * Text-mode job. [logo] and [qrImage] are pre-rendered rasters (the app draws them for the
     * printer's width); without [qrImage] a QR line uses the printer's own QR command when the
     * profile allows it, else it is skipped. [drawer] opens the cash drawer before printing.
     */
    fun text(
        lines: List<PrintLine>,
        p: PrinterProfile,
        logo: MonoImage? = null,
        qrImage: MonoImage? = null,
        drawer: DrawerPulse? = null,
        copies: Int = 1,
    ): ByteArray {
        val e = EscPos()
        e.init()
        if (drawer != null) e.drawer(drawer)
        if (p.textMode == TextMode.GB18030) e.chineseMode(true) else e.codePage(p.codePage)
        for (copy in 0 until copies.coerceIn(1, 5)) {
            for (l in lines) {
                when (l) {
                    is PrintLine.Text -> {
                        if (l.bold) e.bold(true)
                        if (l.big) e.size(doubleWidth = true, doubleHeight = true)
                        e.text(EscPosText.encode(l.text, p.textMode)).lf()
                        if (l.big) e.size(doubleWidth = false, doubleHeight = false)
                        if (l.bold) e.bold(false)
                    }
                    PrintLine.Logo -> if (logo != null) e.raster(logo.centered(p.dots))
                    is PrintLine.Qr -> when {
                        qrImage != null -> e.raster(qrImage.centered(p.dots))
                        p.nativeQr -> {
                            e.align(1)
                            e.qr(l.data.toByteArray(Charsets.UTF_8))
                            e.lf().align(0)
                        }
                    }
                    is PrintLine.Feed -> e.feed(l.lines)
                }
            }
            e.feed(p.feedLines)
            if (p.cut) e.cut()
        }
        return e.bytes()
    }

    /** Whole receipt already rendered as one image (any script, any font). */
    fun image(receipt: MonoImage, p: PrinterProfile, drawer: DrawerPulse? = null, copies: Int = 1): ByteArray {
        val e = EscPos(receipt.data.size + 256)
        e.init()
        if (drawer != null) e.drawer(drawer)
        for (copy in 0 until copies.coerceIn(1, 5)) {
            e.raster(receipt.centered(p.dots))
            e.feed(p.feedLines)
            if (p.cut) e.cut()
        }
        return e.bytes()
    }

    /** Only opens the cash drawer. */
    fun drawerOnly(drawer: DrawerPulse): ByteArray = EscPos(16).init().drawer(drawer).bytes()
}
