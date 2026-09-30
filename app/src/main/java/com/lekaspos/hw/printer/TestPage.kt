package com.lekaspos.hw.printer

import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
import com.lekaspos.data.settings.DeviceSettings

/** The printer test page: paper width, character set, bold/double size, QR and cutting. */
object TestPage {

    fun lines(storeName: String, cfg: DeviceSettings): List<PrintLine> {
        val cols = cfg.cols
        val out = ArrayList<PrintLine>()
        fun center(s: String, bold: Boolean = false) {
            for (line in TextWidth.wrap(s, cols)) out.add(PrintLine.Text(TextWidth.center(line, cols).trimEnd(), bold))
        }
        center(storeName.ifBlank { "LekasPOS" }, bold = true)
        center("PRINTER TEST")
        out.add(PrintLine.Text("=".repeat(cols)))
        out.add(PrintLine.Text("Paper ${if (cfg.paper == 58) "58" else "80"} mm, $cols cols, ${cfg.dots} dots"))
        val ruler = StringBuilder()
        for (i in 1..cols) ruler.append((i % 10).toString())
        out.add(PrintLine.Text(ruler.toString()))
        out.add(PrintLine.Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ".take(cols)))
        out.add(PrintLine.Text("abcdefghijklmnopqrstuvwxyz".take(cols)))
        out.add(PrintLine.Text("0123456789 RM1,234.50 -+*/%"))
        out.add(PrintLine.Text("Cafe creme / Café crème"))
        // Only where receipts could print it: in Auto mode a Chinese line on a Latin printer would turn
        // the whole page into a picture, and the page would no longer test text printing.
        if (cfg.chinesePrinter || cfg.printMode == DeviceSettings.MODE_IMAGE) {
            out.add(PrintLine.Text("中文 Chinese: 牛奶 面包"))
        }
        out.add(PrintLine.Text("Bold line", bold = true))
        out.add(PrintLine.Text(TextWidth.padEnd("TOTAL", cols / 2 - 7) + "RM12.34", bold = true, big = true))
        out.add(PrintLine.Text("-".repeat(cols)))
        out.add(PrintLine.Qr("https://github.com/FaizoKen/LekasPOS"))
        center("If every line fits the paper,")
        center("printing works.")
        return out
    }
}
