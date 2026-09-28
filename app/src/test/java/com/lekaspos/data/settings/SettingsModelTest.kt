package com.lekaspos.data.settings

import com.lekaspos.core.escpos.TextMode
import com.lekaspos.core.money.CurrencySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SettingsModelTest {

    @Test
    fun defaultsFollowTheMalaysianAssumptions() {
        val s = StoreSettings.from(emptyMap(), defaultLanguage = "ms")
        assertEquals("ms", s.receiptLanguage)
        assertTrue(s.pricesIncludeTax)
        assertEquals(5L, s.cashStep)
        assertEquals(2, s.templates().size)
        assertEquals(false, s.einvoiceQr)
    }

    @Test
    fun storeSettingsRoundTrip() {
        val s = StoreSettings(
            name = "Kedai Maju", address = "1 Jalan A\nKajang", brn = "2026", sstNo = "W10", tin = "C123", receiptFooter = "Terima kasih",
            receiptLanguage = "ms", printLogo = true, receiptCopies = 2, einvoiceQr = true, einvoiceUrl = "https://x/{receipt}",
            pricesIncludeTax = false, currency = CurrencySpec.MYR.copy(cashStep = 0L), scaleTemplates = listOf("20IIIIIWWWWWC"),
        )
        assertEquals(s, StoreSettings.from(s.toMap(), defaultLanguage = "en"))
    }

    @Test
    fun badStoredValuesFallBackToSafeOnes() {
        val s = StoreSettings.from(
            mapOf(
                SettingKeys.RECEIPT_COPIES to "9",
                SettingKeys.CURRENCY_DECIMALS to "7",
                SettingKeys.RECEIPT_LANG to "fr",
                SettingKeys.CASH_STEP to "-5",
                SettingKeys.SCALE_TEMPLATES to "20IIIIIWWWWWC, NOT-A-TEMPLATE",
            ),
            defaultLanguage = "en",
        )
        assertEquals(3, s.receiptCopies)
        assertEquals(2, s.currency.decimals)
        assertEquals("en", s.receiptLanguage)
        assertEquals(5L, s.cashStep)
        assertEquals(1, s.templates().size)
    }

    @Test
    fun paperSizesAndPrinterProfile() {
        assertEquals(32 to 384, DeviceSettings(paper = 58).let { it.cols to it.dots })
        assertEquals(48 to 576, DeviceSettings(paper = 80).let { it.cols to it.dots })
        assertEquals(42 to 512, DeviceSettings(paper = 81).let { it.cols to it.dots })
        assertEquals(TextMode.GB18030, DeviceSettings(chinesePrinter = true).profile().textMode)
        assertEquals(TextMode.LATIN, DeviceSettings().profile().textMode)
        assertEquals(false, DeviceSettings().hasPrinter)
        assertEquals(1, DeviceSettings(drawerPin = 1).drawer().pin)
    }
}
