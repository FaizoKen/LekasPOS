package com.lekaspos.core.csv

import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CsvTest {

    private fun readAll(text: String): List<List<String>> {
        val r = CsvReader(StringReader(text))
        val out = ArrayList<List<String>>()
        while (true) out.add(r.next() ?: break)
        return out
    }

    @Test
    fun writerQuotesOnlyWhenNeeded() {
        val sb = StringBuilder()
        val w = CsvWriter(sb)
        w.row("Milo 1kg", "12.50", null, "Susu \"Dutch Lady\"", "a,b", "two\nlines", " padded")
        assertEquals("Milo 1kg,12.50,,\"Susu \"\"Dutch Lady\"\"\",\"a,b\",\"two\nlines\",\" padded\"\r\n", sb.toString())
        assertEquals(1L, w.rows)
    }

    @Test
    fun readerRoundTripsWhatTheWriterWrites() {
        val rows = listOf(
            listOf("name", "price", "note"),
            listOf("Roti \"Gardenia\"", "3.50", "line one\nline two"),
            listOf("牛奶 Milk", "", "a,b;c"),
            listOf("", "0", " x "),
        )
        val sb = StringBuilder()
        sb.append(CsvWriter.BOM)
        val w = CsvWriter(sb)
        for (r in rows) w.row(r)
        assertEquals(rows, readAll(sb.toString()))
    }

    @Test
    fun readerHandlesLineEndsDelimitersAndBlankLines() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), readAll("a,b\rc,d"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), readAll("a,b\n\n\r\nc,d\n"))
        assertEquals(listOf(listOf("nama", "harga"), listOf("Gula", "2,80")), readAll("nama;harga\r\nGula;2,80\r\n"))
        assertEquals(listOf(listOf("a", "b c"), listOf("1", "2")), readAll("a\tb c\n1\t2"))
        // Commas inside quotes do not fool the detection of a semicolon file.
        assertEquals(listOf(listOf("x, y", "z"), listOf("1", "2")), readAll("\"x, y\";z\n1;2"))
        assertEquals(listOf(listOf("a", "", "")), readAll("a,,"))
        val r = CsvReader(StringReader("h1,h2\n\"multi\nline\",x\nlast,y"))
        r.next()
        assertEquals(1, r.recordLine)
        r.next()
        assertEquals(2, r.recordLine)
        assertEquals(listOf("last", "y"), r.next())
        assertEquals(4, r.recordLine)
        assertNull(r.next())
    }

    @Test
    fun malformedInputIsReportedWithItsLine() {
        val e = assertFailsWith<CsvReader.Malformed> { readAll("a,b\n\"open,never closed\n") }
        assertEquals(2, e.line)
        assertFailsWith<CsvReader.Malformed> { readAll("\"quoted\"junk,b") }
        assertFailsWith<CsvReader.Malformed> { readAll("x".repeat(CsvReader.MAX_FIELD + 1)) }
    }

    private val myr = CurrencySpec.MYR

    @Test
    fun headersMatchLooselyInEnglishAndMalay() {
        val h = ProductCsv.header(listOf("Nama Produk", "HARGA (RM)", "Kod Bar", "Kos", "Stok", "Sold_By", "Colour"))
        assertEquals(0, h.index[ProductCsv.Column.NAME])
        assertEquals(1, h.index[ProductCsv.Column.PRICE])
        assertEquals(2, h.index[ProductCsv.Column.BARCODES])
        assertEquals(3, h.index[ProductCsv.Column.COST])
        assertEquals(4, h.index[ProductCsv.Column.STOCK])
        assertEquals(5, h.index[ProductCsv.Column.SOLD_BY])
        assertEquals(listOf("Colour"), h.unknown)
        assertTrue(h.missing.isEmpty())
        assertEquals(listOf(ProductCsv.Column.PRICE), ProductCsv.header(listOf("name", "sku")).missing)
        // The exported header reads back as itself.
        val exported = ProductCsv.header(ProductCsv.COLUMNS.map { it.header })
        assertEquals(ProductCsv.COLUMNS.withIndex().associate { it.value to it.index }, exported.index)
    }

    @Test
    fun rowsAreValidatedField() {
        val h = ProductCsv.header(ProductCsv.COLUMNS.map { it.header })
        val ok = ProductCsv.parse(
            listOf("Milo 1kg", "9556001 | 9556002", "MILO1", "Minuman", "pcs", "RM 18.90", "15.2", "6%", "piece", "yes", "24", "5", "yes"),
            h, myr,
        )
        assertIs<ProductCsv.Parsed.Ok>(ok)
        val r = ok.row
        assertEquals(1_890L, r.price)
        assertEquals(1_520L, r.cost)
        assertEquals(listOf("9556001", "9556002"), r.barcodes)
        assertEquals(24_000L, r.stock)
        assertEquals(5_000L, r.lowStock)
        assertEquals(SellMode.UNIT, r.sellMode)
        assertEquals("6%", r.tax)

        // Round trip through the export format.
        val again = ProductCsv.parse(ProductCsv.format(r, myr), h, myr)
        assertEquals(r, (again as ProductCsv.Parsed.Ok).row)

        val bad = ProductCsv.parse(listOf("", "", "", "", "", "12.345", "-1", "", "sometimes", "maybe", "1.2345", "-2", ""), h, myr)
        assertIs<ProductCsv.Parsed.Bad>(bad)
        val problems = bad.problems.map { it.first }.toSet()
        assertEquals(
            setOf(
                ProductCsv.Problem.NAME_MISSING, ProductCsv.Problem.PRICE_BAD, ProductCsv.Problem.COST_BAD,
                ProductCsv.Problem.SOLD_BY_BAD, ProductCsv.Problem.YES_NO_BAD, ProductCsv.Problem.STOCK_BAD,
                ProductCsv.Problem.LOW_STOCK_BAD,
            ),
            problems,
        )
        val noPrice = ProductCsv.parse(listOf("Gula"), ProductCsv.header(listOf("name", "price")), myr)
        assertEquals(listOf(ProductCsv.Problem.PRICE_MISSING), (noPrice as ProductCsv.Parsed.Bad).problems.map { it.first })

        // Columns not in the file stay null (left unchanged by an import).
        val minimal = ProductCsv.parse(listOf("Gula", "2.80"), ProductCsv.header(listOf("name", "price")), myr) as ProductCsv.Parsed.Ok
        assertNull(minimal.row.cost)
        assertNull(minimal.row.tax)
        assertNull(minimal.row.trackStock)
    }

    @Test
    fun textASpreadsheetWouldRunIsDefused() {
        val sb = StringBuilder()
        val w = CsvWriter(sb)
        w.row("=HYPERLINK(\"http://x\",\"y\")", "@SUM(A1)", "+60123", "-5.00", "-", "- Gula", "1,234.50", "12%", "Milo")
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\",'@SUM(A1),+60123,-5.00,'-,'- Gula,\"1,234.50\",12%,Milo\r\n", sb.toString())
        // Read back through the product import, the names are what they were.
        for (s in listOf("=1+1", "@me", "- Gula", "-", "+ promo")) assertEquals(s, CsvWriter.undefuse(CsvWriter.defuse(s)))
        assertEquals("'-5", CsvWriter.undefuse("'-5")) // an apostrophe the owner typed stays
        assertEquals("Milo", CsvWriter.undefuse("Milo"))
    }

    @Test
    fun anExportedRowComesBackToTheSameProduct() {
        val h = ProductCsv.header(ProductCsv.COLUMNS.map { it.header })
        val r = ProductCsv.Row("Bawang merah", 800L, sellMode = SellMode.WEIGHT, id = 4_398_046_511_123L)
        val fields = ProductCsv.format(r, myr)
        assertEquals("#4398046511123", fields.last())
        assertEquals(4_398_046_511_123L, (ProductCsv.parse(fields, h, myr) as ProductCsv.Parsed.Ok).row.id)
        // A spreadsheet that dropped the # still gives the number; junk is simply no number.
        val plain = fields.toMutableList().also { it[it.size - 1] = "4398046511123" }
        assertEquals(4_398_046_511_123L, (ProductCsv.parse(plain, h, myr) as ProductCsv.Parsed.Ok).row.id)
        val junk = fields.toMutableList().also { it[it.size - 1] = "abc" }
        assertNull((ProductCsv.parse(junk, h, myr) as ProductCsv.Parsed.Ok).row.id)
    }

    @Test
    fun aBarcodeASpreadsheetTurnedIntoANumberIsRefused() {
        val h = ProductCsv.header(listOf("name", "price", "barcode"))
        for (code in listOf("9.55600E+12", "9.556E+12", "9,55600E+12")) {
            val bad = ProductCsv.parse(listOf("Milo", "18.90", code), h, myr)
            assertEquals(listOf(ProductCsv.Problem.BARCODE_BAD), (bad as ProductCsv.Parsed.Bad).problems.map { it.first }, code)
        }
        assertIs<ProductCsv.Parsed.Ok>(ProductCsv.parse(listOf("Milo", "18.90", "9556001234567"), h, myr))
    }

    @Test
    fun wordsForSellModesYesNoAndPercentages() {
        assertEquals(SellMode.WEIGHT, ProductCsv.sellMode("Timbang"))
        assertEquals(SellMode.WEIGHT, ProductCsv.sellMode("kg"))
        assertEquals(SellMode.OPEN_PRICE, ProductCsv.sellMode("Harga terbuka"))
        assertEquals(SellMode.UNIT, ProductCsv.sellMode("biji"))
        assertEquals(true, ProductCsv.yesNo("Ya"))
        assertEquals(false, ProductCsv.yesNo("tidak"))
        assertNull(ProductCsv.yesNo("perhaps"))
        assertEquals(600, ProductCsv.percentBp("6%"))
        assertEquals(600, ProductCsv.percentBp("SST 6%"))
        assertEquals(650, ProductCsv.percentBp("6.5 %"))
        assertEquals(1_000, ProductCsv.percentBp("10"))
        assertNull(ProductCsv.percentBp("exempt"))
        assertNull(ProductCsv.percentBp("150%"))
    }
}
