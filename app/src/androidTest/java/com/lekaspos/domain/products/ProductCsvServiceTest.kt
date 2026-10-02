package com.lekaspos.domain.products

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.csv.CsvWriter
import com.lekaspos.core.csv.ProductCsv
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.SellMode
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.testing.TestGraph
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductCsvServiceTest {

    private val graphs = ArrayList<AppGraph>()

    private fun graph(): AppGraph = TestGraph.create().also { graphs.add(it) }

    @After
    fun tearDown() {
        for (g in graphs) TestGraph.destroy(g)
    }

    private suspend fun seedCatalog(g: AppGraph): Map<String, Long> {
        val db = g.db()
        return db.write(reserveIds = 20L) { tx ->
            val now = System.currentTimeMillis()
            val drinks = CategoryDao.insert(tx, "Minuman", 0, 0, now)
            val sst = TaxRateDao.insert(tx, "Service tax 6%", "ST6", 600, now)
            val milo = tx.nextId()
            ProductDao.create(tx, Product(milo, "Milo 1kg", sku = "MILO1", categoryId = drinks, price = 1_890L, cost = 1_520L, taxRateId = sst, lowStock = 6_000L), listOf(Barcode(tx.nextId(), milo, "9556001000011"), Barcode(tx.nextId(), milo, "9556001000028")), now)
            StockDao.insertMovement(tx, milo, MovementKind.OPENING, 24_000L, 1_520L, null, null, null, now)
            val onion = tx.nextId()
            ProductDao.create(tx, Product(onion, "Bawang merah", sku = "ONION1", unit = "kg", sellMode = SellMode.WEIGHT, price = 800L, cost = 550L), emptyList(), now)
            StockDao.insertMovement(tx, onion, MovementKind.OPENING, 2_500L, 550L, null, null, null, now)
            val hidden = tx.nextId()
            ProductDao.create(tx, Product(hidden, "Old stock", price = 100L, active = false, trackStock = false), listOf(Barcode(tx.nextId(), hidden, "123")), now)
            mapOf("milo" to milo, "onion" to onion, "hidden" to hidden, "sst" to sst)
        }
    }

    /**
     * 2026-10 review: a SKU column holding a placeholder (or one shared code) merged every row into
     * ONE product carrying all their barcodes, while the preview promised them all as new.
     */
    @Test
    fun rowsSharingASkuAreNeverMergedIntoOneProduct() = runBlocking {
        val g = graph()
        val file = "name,price,sku,barcode\n" +
            "Milo 1kg,18.90,-,9556001000011\nGula 1kg,2.80,-,9556002000010\nBeras 5kg,16.50,-,9556003000019\n" +
            "Teh A,5.00,TEH,9556004000018\nTeh B,5.50,teh,9556005000017\n"
        val preview = g.productCsv.preview { StringReader(file) }
        assertEquals(4, preview.newProducts)
        assertEquals(1, preview.badRows) // the second "TEH"
        val result = g.productCsv.import({ StringReader(file) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(preview.newProducts, result.created)
        val names = g.db().read { r -> listOf("9556001000011", "9556002000010", "9556003000019").map { ProductDao.findByCode(r, listOf(it))?.product?.name } }
        assertEquals(listOf("Milo 1kg", "Gula 1kg", "Beras 5kg"), names)
    }

    @Test
    fun anExportImportsIntoAnEmptyStoreAsTheSameCatalogue() = runBlocking {
        val a = graph()
        seedCatalog(a)
        val out = StringBuilder()
        assertEquals(3, a.productCsv.export(out))
        assertTrue(out.startsWith(CsvWriter.BOM))

        val b = graph()
        b.db().write(reserveIds = 2L) { tx -> TaxRateDao.insert(tx, "Service tax 6%", "ST6", 600, System.currentTimeMillis()) }
        val preview = b.productCsv.preview { StringReader(out.toString()) }
        assertEquals(3, preview.newProducts)
        assertEquals(0, preview.badRows)
        assertEquals(listOf("Minuman"), preview.newCategories)
        val result = b.productCsv.import({ StringReader(out.toString()) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(3, result.created)
        assertEquals(1, result.categoriesCreated)

        val db = b.db()
        val milo = assertNotNull(db.read { ProductDao.bySku(it, "MILO1") })
        val p = assertNotNull(db.read { ProductDao.get(it, milo) })
        assertEquals(1_890L, p.price)
        assertEquals(1_520L, p.cost)
        assertEquals(6_000L, p.lowStock)
        assertNotNull(p.taxRateId)
        assertEquals(setOf("9556001000011", "9556001000028"), db.read { ProductDao.barcodes(it, milo) }.map { it.code }.toSet())
        assertEquals(24_000L, db.read { StockDao.level(it, milo) }) // opening stock
        val onion = assertNotNull(db.read { ProductDao.bySku(it, "ONION1") })
        val o = assertNotNull(db.read { ProductDao.get(it, onion) })
        assertEquals(SellMode.WEIGHT, o.sellMode)
        assertEquals("kg", o.unit)
        assertEquals(2_500L, db.read { StockDao.level(it, onion) })
        val old = assertNotNull(db.read { r -> ProductDao.ownerOf(r, "123") })
        val h = assertNotNull(db.read { ProductDao.get(it, old) })
        assertFalse(h.active)
        assertFalse(h.trackStock)
        assertEquals(1L, db.read { AuditDao.countByAction(it, AuditAction.PRODUCT_IMPORT) })
    }

    /** D-042: exported, edited in a spreadsheet, imported again — the same products, none twice. */
    @Test
    fun anExportedFileImportedAgainUpdatesTheSameProducts() = runBlocking {
        val g = graph()
        seedCatalog(g)
        val db = g.db()
        val leaves = db.write(reserveIds = 2L) { tx ->
            tx.nextId().also { ProductDao.create(tx, Product(it, "=Daun kari", price = 100L), emptyList(), System.currentTimeMillis()) }
        }
        val out = StringBuilder()
        assertEquals(4, g.productCsv.export(out))
        assertTrue(out.contains("'=Daun kari")) // a spreadsheet does not run it as a formula
        val edited = out.toString().split("\r\n").joinToString("\r\n") { if (it.startsWith("'=Daun kari,")) it.replace(",1.00,", ",1.20,") else it }
        val preview = g.productCsv.preview { StringReader(edited) }
        assertEquals(0, preview.newProducts)
        assertEquals(4, preview.updates)
        val result = g.productCsv.import({ StringReader(edited) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(0, result.created)
        assertEquals(4, result.updated)
        val p = assertNotNull(db.read { ProductDao.get(it, leaves) })
        assertEquals("=Daun kari", p.name)
        assertEquals(120L, p.price)
    }

    @Test
    fun previewFindsProblemsAndTheImportSkipsThem() = runBlocking {
        val g = graph()
        val ids = seedCatalog(g)
        val csv = listOf(
            "Nama;Harga;Kod bar;SKU;Kategori;Kos;Cukai;Stok",
            "Milo 1kg (baharu);19.50;9556001000011;;;;;30",
            "Susu pekat;4.20;9556009999999;SUSU1;Tin;3.10;tiada;12",
            "Gula;abc;;;;;;",
            "Kicap;5.00;9556009999999;;;;;",
            "Bawang merah besar;9.00;;ONION1;;;;",
            "Maggi;1.20;9556001000028|9556008888888;;;;SST 10%;",
            ";2.00;;;;;;",
            "\"Teh \"\"Boh\"\"\";6.90;;BOH1;Minuman;5.00;6%;",
        ).joinToString("\r\n")
        val p = g.productCsv.preview { StringReader(csv) }
        assertEquals(8, p.rows)
        assertEquals(2, p.updates) // Milo by barcode, the onions by SKU
        assertEquals(2, p.newProducts) // Susu, Teh
        assertEquals(4, p.badRows)
        val problems = p.issues.map { it.line to it.problem }.toSet()
        assertTrue((4 to ProductCsv.Problem.PRICE_BAD) in problems)
        assertTrue((5 to ProductCsv.Problem.BARCODE_TWICE) in problems)
        assertTrue((7 to ProductCsv.Problem.TAX_UNKNOWN) in problems)
        assertTrue((8 to ProductCsv.Problem.NAME_MISSING) in problems)
        assertEquals(listOf("Tin"), p.newCategories)
        assertTrue(p.hasStock)
        assertTrue(p.importable)

        val r = g.productCsv.import({ StringReader(csv) }, setStock = true, staffId = null, approvedBy = null)
        assertEquals(2, r.created)
        assertEquals(2, r.updated)
        assertEquals(4, r.skipped)
        assertEquals(2, r.stockSet) // Milo: 24 → 30 (as a count); Susu: 12 opening stock
        val db = g.db()
        val milo = assertNotNull(db.read { ProductDao.get(it, ids.getValue("milo")) })
        assertEquals("Milo 1kg (baharu)", milo.name)
        assertEquals(1_950L, milo.price)
        assertEquals(1_520L, milo.cost) // empty cell: unchanged
        assertEquals(ids["sst"], milo.taxRateId) // empty tax cell: unchanged
        assertEquals(30_000L, db.read { StockDao.level(it, milo.id) })
        val onion = assertNotNull(db.read { ProductDao.get(it, ids.getValue("onion")) })
        assertEquals("Bawang merah besar", onion.name)
        assertEquals(900L, onion.price)
        assertEquals("kg", onion.unit) // not in the file: unchanged
        assertEquals(SellMode.WEIGHT, onion.sellMode)
        val susuId = assertNotNull(db.read { ProductDao.bySku(it, "SUSU1") })
        val susu = assertNotNull(db.read { ProductDao.get(it, susuId) })
        assertNull(susu.taxRateId)
        assertEquals(12_000L, db.read { StockDao.level(it, susuId) })
        val teh = assertNotNull(db.read { r -> ProductDao.bySku(r, "BOH1")?.let { ProductDao.get(r, it) } })
        assertEquals("Teh \"Boh\"", teh.name)
        assertEquals(ids["sst"], teh.taxRateId) // 6% matched by percentage
        assertNull(db.read { ProductDao.ownerOf(it, "9556008888888") }) // Maggi was skipped
    }

    /** 2026-10 review: a deleted category came back as a new one, a deleted tax rate back onto products. */
    @Test
    fun deletedCategoriesAndTaxRatesAreNeitherExportedNorBroughtBack() = runBlocking {
        val g = graph()
        val ids = seedCatalog(g)
        val db = g.db()
        val milo = ids.getValue("milo")
        val drinks = assertNotNull(db.read { ProductDao.get(it, milo) }?.categoryId)
        db.write(reserveIds = 2L) { tx ->
            CategoryDao.delete(tx, drinks, System.currentTimeMillis())
            TaxRateDao.delete(tx, ids.getValue("sst"), System.currentTimeMillis())
        }
        val out = StringBuilder()
        assertEquals(3, g.productCsv.export(out))
        assertFalse(out.contains("Minuman"))
        assertFalse(out.contains("Service tax"))
        val r = g.productCsv.import({ StringReader(out.toString()) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(3, r.updated)
        assertEquals(0, r.categoriesCreated)
        assertEquals(emptyList(), db.read { CategoryDao.list(it) })
        val p = assertNotNull(db.read { ProductDao.get(it, milo) })
        assertEquals(drinks, p.categoryId) // blank cells: unchanged
        assertEquals(ids["sst"], p.taxRateId)
    }

    /** 2026-10 review: with a barcode on two products, the file updates the one the till scans. */
    @Test
    fun aBarcodeOnTwoProductsUpdatesTheOneAScanPicks() = runBlocking {
        val g = graph()
        val ids = seedCatalog(g)
        val db = g.db()
        val twin = db.write(reserveIds = 4L) { tx ->
            val id = tx.nextId()
            ProductDao.create(tx, Product(id, "Milo (till 2)", price = 1_800L), listOf(Barcode(tx.nextId(), id, "9556001000011")), 0L)
            tx.exec("UPDATE product_barcode SET created_at = ? WHERE product_id = ?", 0L, ids.getValue("milo"))
            tx.exec("UPDATE product_barcode SET created_at = ? WHERE product_id = ?", 1L, id)
            id
        }
        val picked = assertNotNull(db.read { ProductDao.findByCode(it, listOf("9556001000011")) }).product.id
        assertEquals(twin, picked)
        val csv = "name,price,barcode\r\nMilo 1kg,19.90,9556001000011\r\n"
        val r = g.productCsv.import({ StringReader(csv) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(1, r.updated)
        assertEquals(1_990L, db.read { ProductDao.get(it, twin) }?.price)
        assertEquals(1_890L, db.read { ProductDao.get(it, ids.getValue("milo")) }?.price)
    }

    /** 2026-10 review: a spreadsheet stored the barcodes as numbers and dropped their leading 0. */
    @Test
    fun barcodesWithoutTheirLeadingZeroFindTheirProducts() = runBlocking {
        val g = graph()
        val db = g.db()
        val (coke, mints) = db.write(reserveIds = 6L) { tx ->
            val now = System.currentTimeMillis()
            val c = tx.nextId()
            ProductDao.create(tx, Product(c, "Coke 330ml", price = 250L), listOf(Barcode(tx.nextId(), c, "0036000291452")), now)
            val m = tx.nextId()
            ProductDao.create(tx, Product(m, "Mints", price = 150L), listOf(Barcode(tx.nextId(), m, "01234565")), now)
            c to m
        }
        val csv = "name,price,barcode\r\nCoke 330ml,2.60,36000291452\r\nMints,1.60,1234565\r\n"
        assertEquals(2, g.productCsv.preview { StringReader(csv) }.updates)
        val r = g.productCsv.import({ StringReader(csv) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(0, r.created)
        assertEquals(2, r.updated)
        assertEquals(260L, db.read { ProductDao.get(it, coke) }?.price)
        assertEquals(listOf("0036000291452"), db.read { ProductDao.barcodes(it, coke) }.map { it.code }) // no second form
        assertEquals(listOf("01234565"), db.read { ProductDao.barcodes(it, mints) }.map { it.code })
    }

    /** A product an older version stored without the leading 0 is updated, not imported twice. */
    @Test
    fun aBarcodeStoredWithoutItsLeadingZeroIsStillFound() = runBlocking {
        val g = graph()
        val db = g.db()
        val mints = db.write(reserveIds = 3L) { tx ->
            val m = tx.nextId()
            ProductDao.create(tx, Product(m, "Mints", price = 150L), listOf(Barcode(tx.nextId(), m, "1234565")), System.currentTimeMillis())
            m
        }
        val csv = "name,price,barcode\r\nMints,1.60,1234565\r\n"
        val r = g.productCsv.import({ StringReader(csv) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(0, r.created)
        assertEquals(1, r.updated)
        assertEquals(160L, db.read { ProductDao.get(it, mints) }?.price)
        // The full EAN-8 is added, so a scan of the printed barcode finds it from now on.
        assertEquals(setOf("1234565", "01234565"), db.read { ProductDao.barcodes(it, mints) }.map { it.code }.toSet())
    }

    /** 2026-10 review: a code an older version kept with its dashes made a second product on the next import. */
    @Test
    fun aBarcodeStoredAsWrittenWithDashesIsStillFound() = runBlocking {
        val g = graph()
        val db = g.db()
        val milo = db.write(reserveIds = 3L) { tx ->
            val m = tx.nextId()
            ProductDao.create(tx, Product(m, "Milo", price = 500L), listOf(Barcode(tx.nextId(), m, "955-6001-234568")), System.currentTimeMillis())
            m
        }
        val csv = "name,price,barcode\r\nMilo,5.20,955-6001-234568\r\n"
        assertEquals(1, g.productCsv.preview { StringReader(csv) }.updates)
        val r = g.productCsv.import({ StringReader(csv) }, setStock = false, staffId = null, approvedBy = null)
        assertEquals(0, r.created)
        assertEquals(1, r.updated)
        assertEquals(520L, db.read { ProductDao.get(it, milo) }?.price)
        // The digits are added, so a scan of the printed barcode finds it from now on.
        assertEquals(setOf("955-6001-234568", "9556001234568"), db.read { ProductDao.barcodes(it, milo) }.map { it.code }.toSet())
    }

    /** 2026-10 review: an import that stops part-way logs the chunks it committed. */
    @Test
    fun anImportThatStopsPartWayIsLogged() = runBlocking {
        val g = graph()
        val csv = buildString {
            append("name,price\r\n")
            for (i in 1..ProductCsvService.CHUNK + 50) append("Item $i,1.00\r\n")
        }
        // The file can no longer be read after its last row (a card pulled out).
        val failing = object : Reader() {
            private val inner = StringReader(csv)

            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                val n = inner.read(cbuf, off, len)
                if (n < 0) throw IOException("file gone")
                return n
            }

            override fun close() = inner.close()
        }
        assertFailsWith<IOException> { g.productCsv.import({ failing }, setStock = false, staffId = null, approvedBy = null) }
        val db = g.db()
        assertEquals(ProductCsvService.CHUNK.toLong(), db.read { ProductDao.count(it) }) // the first chunk stays
        val log = db.read { AuditDao.byAction(it, AuditAction.PRODUCT_IMPORT, null) }.single()
        assertTrue(log.detail.orEmpty().startsWith("created ${ProductCsvService.CHUNK}, "))
        assertTrue(log.detail.orEmpty().endsWith("stopped early"))
    }

    @Test
    fun filesWithoutTheRequiredColumnsOrBrokenQuotesAreRefused() = runBlocking {
        val g = graph()
        val noPrice = g.productCsv.preview { StringReader("name,sku\nGula,G1\n") }
        assertEquals(listOf(ProductCsv.Column.PRICE), noPrice.missing)
        assertFalse(noPrice.importable)
        val broken = g.productCsv.preview { StringReader("name,price\n\"Gula,2.80\n") }
        assertNotNull(broken.malformed)
        assertFalse(broken.importable)
        val template = StringBuilder()
        g.productCsv.template(template)
        val t = g.productCsv.preview { StringReader(template.toString()) }
        assertEquals(2, t.newProducts)
        assertEquals(0, t.badRows)
    }
}
