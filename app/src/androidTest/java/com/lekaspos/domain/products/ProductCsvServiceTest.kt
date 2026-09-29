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
import java.io.StringReader
import kotlin.test.assertEquals
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
        assertEquals(1, r.stockSet) // Milo: 24 → 30 (as a count)
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
