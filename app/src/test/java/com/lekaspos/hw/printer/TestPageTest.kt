package com.lekaspos.hw.printer

import com.lekaspos.core.escpos.EscPosText
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.text.TextWidth
import com.lekaspos.data.settings.DeviceSettings
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The test page must test what receipts use: text mode where receipts print text, every line on the paper. */
class TestPageTest {

    private fun texts(cfg: DeviceSettings) = TestPage.lines("Kedai Runcit Maju", cfg).filterIsInstance<PrintLine.Text>()

    @Test
    fun everyLineFitsThePaper() {
        for (paper in listOf(58, 80, 81)) {
            val cfg = DeviceSettings(paper = paper, printMode = DeviceSettings.MODE_IMAGE)
            for (l in texts(cfg)) {
                val limit = if (l.big) cfg.cols / 2 else cfg.cols
                assertTrue(TextWidth.of(l.text) <= limit, "'${l.text}' is wider than $limit on $paper mm")
            }
        }
        val longName = TestPage.lines("Kedai Runcit dan Borong Maju Jaya Sdn Bhd", DeviceSettings())
        assertTrue(longName.filterIsInstance<PrintLine.Text>().all { TextWidth.of(it.text) <= 32 })
    }

    @Test
    fun autoModeOnALatinPrinterPrintsText() {
        val profile = DeviceSettings().profile()
        assertTrue(texts(DeviceSettings()).all { EscPosText.canEncode(it.text, profile.textMode) })
    }

    @Test
    fun chineseLineOnlyWhereItCanPrint() {
        fun hasChinese(cfg: DeviceSettings) = texts(cfg).any { it.text.contains("牛奶") }
        assertEquals(false, hasChinese(DeviceSettings()))
        assertEquals(false, hasChinese(DeviceSettings(printMode = DeviceSettings.MODE_TEXT)))
        assertTrue(hasChinese(DeviceSettings(chinesePrinter = true)))
        assertTrue(hasChinese(DeviceSettings(printMode = DeviceSettings.MODE_IMAGE)))
        val chinese = DeviceSettings(chinesePrinter = true)
        assertTrue(texts(chinese).all { EscPosText.canEncode(it.text, chinese.profile().textMode) })
    }
}
