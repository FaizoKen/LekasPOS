package com.lekaspos.domain.print

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.escpos.ReceiptEncoder
import com.lekaspos.core.escpos.TextMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.data.settings.StoreSettings
import com.lekaspos.hw.printer.ReceiptRenderer
import com.lekaspos.testing.TestDb
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptBuilderTest {

    private lateinit var db: Db
    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private val store = StoreSettings(
        name = "Kedai Maju", address = "12 Jalan Besar", sstNo = "W10-1808-32000123",
        einvoiceQr = true, einvoiceUrl = "https://e.example/r/{receipt}?t={total}",
    )

    @Before
    fun setUp() {
        db = TestDb.fresh()
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    @Test
    fun receiptComesFromTheStoredSale() {
        val sst = db.writeBlocking { tx -> TaxRateDao.insert(tx, "SST", "S", 600, System.currentTimeMillis()) }
        val milk = TestDb.product(db, "Susu Segar 1L", 503L, taxRateId = sst)
        val bread = TestDb.product(db, "Roti", 350L)
        val draft = TestDb.saleDraft(db, listOf(milk to 2_000L, bread to 1_000L))
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, draft, tz) }

        val doc = assertNotNull(db.readBlocking { ReceiptBuilder.build(it, sale.id, copy = false, store, tz) })
        assertEquals(sale.receiptNo, doc.receiptNo)
        assertEquals("Owner", doc.cashier)
        assertEquals(listOf("Susu Segar 1L", "Roti"), doc.items.map { it.name })
        assertEquals(draft.total, doc.total)
        assertEquals(draft.rounding, doc.rounding)
        assertEquals(listOf("SST"), doc.taxes.map { it.name })
        assertEquals(draft.tax, doc.taxes.single().amount)
        assertEquals(draft.payments.single().tendered, doc.payments.single().tendered)
        assertEquals("https://e.example/r/${sale.receiptNo}?t=${MoneyFormat.plain(draft.total, 2)}", doc.qrData)

        val lines = ReceiptBuilder.layout(doc, 32, store, logo = false, tz)
        val text = lines.filterIsInstance<PrintLine.Text>().map { it.text }
        assertTrue(text.any { it.startsWith("Incl. SST 6%") }, text.joinToString("\n"))
        assertTrue(lines.any { it is PrintLine.Qr })
        assertEquals(false, ReceiptBuilder.needsImage(lines, TextMode.LATIN))

        // Text job and image job both encode without error.
        val cfg = DeviceSettings(printerAddress = "x")
        assertTrue(ReceiptEncoder.text(lines, cfg.profile()).isNotEmpty())
        val mono = ReceiptRenderer(cfg.cols, cfg.dots).mono(lines, null, null)
        assertEquals(cfg.dots, mono.width)
        assertTrue(mono.inkRows() > 20)

        val copy = assertNotNull(db.readBlocking { ReceiptBuilder.build(it, sale.id, copy = true, store, tz) })
        assertTrue(copy.reprint)
    }

    @Test
    fun chineseNamesNeedAnImageOnLatinPrinters() {
        val p = TestDb.product(db, "牛奶 Susu", 385L)
        val sale = db.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L)), tz) }
        val doc = assertNotNull(db.readBlocking { ReceiptBuilder.build(it, sale.id, copy = false, store, tz) })
        val lines = ReceiptBuilder.layout(doc, 32, store, logo = false, tz)
        assertTrue(ReceiptBuilder.needsImage(lines, TextMode.LATIN))
        assertEquals(false, ReceiptBuilder.needsImage(lines, TextMode.GB18030))
    }
}
