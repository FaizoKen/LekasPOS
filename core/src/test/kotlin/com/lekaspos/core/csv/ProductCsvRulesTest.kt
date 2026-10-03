package com.lekaspos.core.csv

import com.lekaspos.core.csv.ProductCsv.Column
import com.lekaspos.core.csv.ProductCsv.Problem
import com.lekaspos.core.csv.ProductCsv.TaxChoice
import com.lekaspos.core.money.CurrencySpec
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/** 2026-10 review of the product import: tax cells, header names, the template's examples, limits. */
class ProductCsvRulesTest {

    private val myr = CurrencySpec.MYR

    @Test
    fun placeholdersAndNoTaxWordsMeanNoTax() {
        // "-" has an empty search key: it never matched, and the row was skipped as "no such tax".
        for (t in listOf("", " ", "-", "–", "—", ".", "N/A", "n.a.", "NA", "nil", "None", "No tax", "Exempt", "Tax exempt",
            "Zero rated", "0", "0%", "0.00 %", "SST 0%", "N", "No", "tiada", "Tiada cukai", "Dikecualikan", "x")) {
            assertTrue(ProductCsv.isNoTax(t), t)
        }
        for (t in listOf("SST 10%", "6%", "10", "Y", "Service tax 6%", "Taxable")) assertFalse(ProductCsv.isNoTax(t), t)
    }

    @Test
    fun taxCellsPickARateByNameThenPercentage() {
        val rates = listOf("Service tax 6%" to 600, "Sales tax 10%" to 1_000)
        assertEquals(TaxChoice.Rate(0), ProductCsv.taxChoice("service TAX 6%", rates))
        assertEquals(TaxChoice.Rate(0), ProductCsv.taxChoice("6%", rates))
        assertEquals(TaxChoice.Rate(1), ProductCsv.taxChoice("SST 10%", rates))
        assertEquals(TaxChoice.Rate(1), ProductCsv.taxChoice("10", rates))
        for (none in listOf("-", "N/A", "Exempt", "0%", "N", "tiada")) assertSame(TaxChoice.None, ProductCsv.taxChoice(none, rates), none)
        // A percentage the store has no rate for, or a "yes" with two rates to choose from.
        assertSame(TaxChoice.Unmatched, ProductCsv.taxChoice("SST 8%", rates))
        assertSame(TaxChoice.Unmatched, ProductCsv.taxChoice("Y", rates))
        assertSame(TaxChoice.Unmatched, ProductCsv.taxChoice("GST standard", rates))
        // A Y/N column with the store's only rate.
        val one = listOf("SST" to 1_000)
        assertEquals(TaxChoice.Rate(0), ProductCsv.taxChoice("Y", one))
        assertEquals(TaxChoice.Rate(0), ProductCsv.taxChoice("sst", one)) // by name
        assertSame(TaxChoice.None, ProductCsv.taxChoice("N", one))
        assertSame(TaxChoice.Unmatched, ProductCsv.taxChoice("Y", emptyList()))
        assertSame(TaxChoice.Unmatched, ProductCsv.taxChoice("6%", emptyList()))
    }

    @Test
    fun commonHeaderNamesInEnglishAndMalayAreKnown() {
        val cases = mapOf(
            Column.NAME to listOf("Item Name", "Product Name", "Nama Barang", "Nama Produk", "NAME", "Item", "Description"),
            Column.PRICE to listOf("Unit Price", "Selling Price", "Selling Price (RM)", "Harga Jual", "Harga (RM)", "Price/RM", "PRICE MYR", "Retail price"),
            Column.BARCODES to listOf("Bar Code", "Barcode No.", "Barcode #", "Kod Bar", "EAN-13", "barcodes"),
            Column.STOCK to listOf("Stock", "Qty", "Qty.", "Kuantiti", "Stock On Hand", "Quantity"),
            Column.COST to listOf("Cost Price (RM)", "Harga Beli", "Unit cost", "Kos"),
            Column.SKU to listOf("Item Code", "Kod Produk", "SKU", "PLU"),
            Column.TAX to listOf("Tax %", "SST", "Kadar Cukai", "Tax code"),
        )
        for ((column, headers) in cases) {
            for (header in headers) {
                val h = ProductCsv.header(listOf(header))
                assertEquals(0, h.index[column], "$header → $column")
            }
        }
        // A file from another shop system: code, description, price, quantity.
        val h = ProductCsv.header(listOf("No", "Item Code", "Description", "Selling Price (RM)", "Qty", "Bar Code"))
        assertEquals(mapOf(Column.SKU to 1, Column.NAME to 2, Column.PRICE to 3, Column.STOCK to 4, Column.BARCODES to 5), h.index)
        assertEquals(listOf("No"), h.unknown)
        assertTrue(h.missing.isEmpty())
    }

    @Test
    fun aWeakHeaderGivesWayToAStrongOne() {
        val h = ProductCsv.header(listOf("Description", "Name", "Price", "Harga"))
        assertEquals(1, h.index[Column.NAME])
        assertEquals(2, h.index[Column.PRICE])
        assertEquals(listOf("Description", "Harga"), h.unknown) // named as ignored, in file order
        // "sen" is no currency word to drop: prices in sen are not read as ringgit.
        assertEquals(listOf(Column.PRICE), ProductCsv.header(listOf("name", "Price (sen)")).missing)
    }

    @Test
    fun noHeaderNameBelongsToTwoColumns() {
        val owner = HashMap<String, Column>()
        for (c in Column.values()) {
            for (a in listOf(c.header) + c.aliases + c.weak) {
                val key = ProductCsv.headerKey(a)
                val before = owner.put(key, c)
                assertTrue(before == null || before == c, "\"$a\" is both $before and $c")
            }
        }
    }

    @Test
    fun theTemplatesExampleRowsAreNeverImported() {
        val h = ProductCsv.header(listOf("name", "price", "sku", "barcode"))
        for (name in listOf("${ProductCsv.EXAMPLE_PREFIX}Milo 1kg", "Example - Gula", "CONTOH – Beras", "contoh: Teh", " EXAMPLE—x")) {
            assertSame(ProductCsv.Parsed.Example, ProductCsv.parse(listOf(name, "1.00", "S1", "9556001234567"), h, myr), name)
        }
        // Even with problems: an example is skipped, not reported.
        assertSame(ProductCsv.Parsed.Example, ProductCsv.parse(listOf("EXAMPLE – Milo", "abc"), h, myr))
        for (name in listOf("Contoh barang", "Example product", "Milo EXAMPLE – 1kg")) {
            assertIs<ProductCsv.Parsed.Ok>(ProductCsv.parse(listOf(name, "1.00"), h, myr), name)
        }
    }

    @Test
    fun textAmountsAndQuantitiesHaveLimits() {
        val h = ProductCsv.header(listOf("name", "price", "cost", "sku", "category", "unit", "stock", "low stock"))
        fun problems(vararg f: String) = (ProductCsv.parse(f.toList(), h, myr) as? ProductCsv.Parsed.Bad)?.problems.orEmpty().toSet()
        val ok = arrayOf("Gula", "99999999.99", "99999999.99", "S".repeat(ProductCsv.MAX_NAME), "C".repeat(ProductCsv.MAX_NAME),
            "U".repeat(ProductCsv.MAX_UNIT), "99999", "99999")
        assertIs<ProductCsv.Parsed.Ok>(ProductCsv.parse(ok.toList(), h, myr))
        assertEquals(9_999_999_999L, ProductCsv.MAX_AMOUNT)
        fun with(i: Int, v: String) = ok.copyOf().also { it[i] = v }
        assertEquals(setOf(Problem.TOO_LARGE to Column.PRICE), problems(*with(1, "100000000.00")))
        assertEquals(setOf(Problem.TOO_LARGE to Column.PRICE), problems(*with(1, "RM 1,000,000,000")))
        assertEquals(setOf(Problem.TOO_LARGE to Column.COST), problems(*with(2, "100000000")))
        assertEquals(setOf(Problem.TOO_LONG to Column.SKU), problems(*with(3, "S".repeat(ProductCsv.MAX_NAME + 1))))
        assertEquals(setOf(Problem.TOO_LONG to Column.CATEGORY), problems(*with(4, "C".repeat(ProductCsv.MAX_NAME + 1))))
        assertEquals(setOf(Problem.TOO_LONG to Column.UNIT), problems(*with(5, "U".repeat(ProductCsv.MAX_UNIT + 1))))
        assertEquals(setOf(Problem.TOO_LARGE to Column.STOCK), problems(*with(6, "100000")))
        assertEquals(setOf(Problem.TOO_LARGE to Column.STOCK), problems(*with(6, "-100000")))
        assertEquals(setOf(Problem.TOO_LARGE to Column.LOW_STOCK), problems(*with(7, "1000000")))
        // The largest quantity times the largest amount still fits in a Long (stock value).
        assertTrue(ProductCsv.MAX_QTY <= Long.MAX_VALUE / ProductCsv.MAX_AMOUNT)
    }

    @Test
    fun aRecordLongerThanTheLimitStopsTheRead() {
        val field = "x".repeat(8_000)
        val tooLong = "name,price\n" + List(9) { field }.joinToString(",") + "\n" // 72,008 characters
        val e = assertFailsWith<CsvReader.Malformed> {
            val r = CsvReader(StringReader(tooLong))
            while (r.next() != null) Unit
        }
        assertEquals(2, e.line)
        // Quoted fields and line breaks inside them count too.
        val quoted = "name,price\n" + List(9) { "\"$field\n\"" }.joinToString(",") + "\n"
        assertFailsWith<CsvReader.Malformed> {
            val r = CsvReader(StringReader(quoted))
            while (r.next() != null) Unit
        }
        // Just under the limit is read.
        val fits = "name,price\n" + List(8) { field }.joinToString(",") + "\n"
        val r = CsvReader(StringReader(fits))
        r.next()
        assertEquals(8, r.next()?.size)
    }
}
