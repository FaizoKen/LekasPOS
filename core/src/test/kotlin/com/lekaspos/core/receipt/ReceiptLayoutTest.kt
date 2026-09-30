package com.lekaspos.core.receipt

import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.text.TextWidth
import com.lekaspos.core.time.Days
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ReceiptLayoutTest {

    private val kl = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private val soldAt = Days.fromYmd(20260929) * Days.DAY_MS + 6 * 3_600_000L // 14:00 local

    private fun doc(total: Long = 6295L) = ReceiptDoc(
        store = StoreInfo(
            name = "Kedai Runcit Maju", address = "12 Jalan Besar\n43000 Kajang", phone = "03-1234 5678",
            brn = "202601234567", sstNo = "W10-1808-32000123",
        ),
        receiptNo = "AB12-0-000042",
        soldAt = soldAt,
        cashier = "Owner",
        items = listOf(
            ReceiptItem(name = "Milo Activ-Go 1kg", qty = 1000L, unitPrice = 1890L, gross = 1890L),
            ReceiptItem(name = "Gardenia Original Classic Bread 400g", qty = 2000L, unitPrice = 420L, gross = 840L, discount = 84L),
            ReceiptItem(name = "Udang Harimau", qty = 253L, unit = "kg", weighed = true, unitPrice = 12900L, gross = 3264L),
            ReceiptItem(name = "牛奶 Susu Segar 1L", qty = 1000L, unitPrice = 385L, gross = 385L),
        ),
        subtotal = 6379L,
        lineDiscounts = 84L,
        tax = 356L,
        taxes = listOf(ReceiptTax("SST", 600, 356L)),
        pricesIncludeTax = true,
        rounding = 0L,
        total = total,
        payments = listOf(ReceiptPayment("Cash", 6295L, tendered = 10000L, change = 3705L)),
        change = 3705L,
        qrData = "https://example.com/e?r=AB12-0-000042",
    )

    private fun texts(lines: List<PrintLine>) = lines.filterIsInstance<PrintLine.Text>()

    /** [left] and [right] on one 32-column line. */
    private fun row(left: String, right: String) = left.padEnd(32 - right.length) + right

    @Test
    fun everyLineFitsThePaper() {
        for (cols in listOf(32, 42, 48)) {
            val lines = ReceiptLayout(cols, CurrencySpec.MYR, ReceiptText.EN, kl).layout(doc(), logo = true)
            for (l in texts(lines)) {
                val limit = if (l.big) cols / 2 else cols
                assertTrue(TextWidth.of(l.text) <= limit, "'${l.text}' is wider than $limit at $cols cols")
            }
            assertEquals(PrintLine.Logo, lines[0])
            assertTrue(lines.any { it is PrintLine.Qr })
        }
    }

    @Test
    fun layoutOn58mmPaper() {
        val lines = texts(ReceiptLayout(32, CurrencySpec.MYR, ReceiptText.EN, kl).layout(doc()))
        val t = lines.map { it.text }
        assertTrue("       Kedai Runcit Maju" in t) // too wide for double size on 58 mm: centred, bold
        assertTrue("No: AB12-0-000042" in t)
        assertTrue("Date: 29/09/2026 14:00" in t)
        assertTrue(row("Milo Activ-Go 1kg", "18.90") in t)
        assertTrue("Gardenia Original Classic Bread" in t)
        assertTrue(row("  2 x 4.20", "8.40") in t)
        assertTrue(row("  Discount", "-0.84") in t)
        assertTrue(row("  0.253 kg x 129.00/kg", "32.64") in t)
        assertTrue(row("Subtotal", "63.79") in t)
        assertTrue(row("Item discounts", "-0.84") in t)
        assertTrue(row("Cash", "100.00") in t)
        assertTrue(row("Change", "37.05") in t)
        assertTrue(row("Incl. SST 6%", "3.56") in t)
        assertTrue("Items: 5" in t)
        val total = lines.single { it.big && it.text.startsWith("TOTAL") }
        assertEquals("TOTAL    RM62.95", total.text)
        assertTrue(lines.none { it.text.contains("Rounding") })
    }

    @Test
    fun largeTotalsFallBackToNormalSize() {
        val lines = texts(ReceiptLayout(32, CurrencySpec.MYR, ReceiptText.EN, kl).layout(doc(total = 1_234_567L)))
        val total = lines.single { it.text.startsWith("TOTAL") }
        assertEquals(false, total.big)
        assertTrue(total.bold)
        assertEquals(row("TOTAL", "RM12,345.67"), total.text)
    }

    @Test
    fun refundInMalay() {
        // A refund's payment is stored with a negative amount and nothing tendered.
        val refund = doc(total = -1890L).copy(
            refund = true, refReceiptNo = "AB12-0-000041", rounding = -5L,
            payments = listOf(ReceiptPayment("Tunai", -1890L)), change = 0L,
        )
        val t = texts(ReceiptLayout(32, CurrencySpec.MYR, ReceiptText.MS, kl).layout(refund)).map { it.text }
        assertTrue(t.any { it.trim() == "BAYARAN BALIK" })
        assertTrue("Resit asal: AB12-0-000041" in t)
        assertTrue(t.any { it.startsWith("Pelarasan") && it.endsWith("-0.05") })
        assertTrue(row("Tunai", "-18.90") in t)
        assertTrue(t.any { it.trim() == "Imbas untuk minta e-invois" })
    }

    @Test
    fun exclusiveTaxIsListedBeforeTheTotal() {
        val excl = doc().copy(pricesIncludeTax = false)
        val t = texts(ReceiptLayout(32, CurrencySpec.MYR, ReceiptText.EN, kl).layout(excl)).map { it.text }
        val taxAt = t.indexOfFirst { it.startsWith("SST 6%") }
        val totalAt = t.indexOfFirst { it.startsWith("TOTAL") }
        assertTrue(taxAt in 0 until totalAt)
    }

    @Test
    fun percentText() {
        assertEquals("6%", ReceiptLayout.percent(600))
        assertEquals("12.5%", ReceiptLayout.percent(1250))
        assertEquals("0.25%", ReceiptLayout.percent(25))
        assertEquals("10.05%", ReceiptLayout.percent(1005))
        assertEquals("0%", ReceiptLayout.percent(0))
    }
}
