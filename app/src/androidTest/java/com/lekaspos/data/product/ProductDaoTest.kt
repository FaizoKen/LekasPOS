package com.lekaspos.data.product

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.data.db.Db
import com.lekaspos.testing.TestDb
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ProductDaoTest {

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = TestDb.fresh()
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    private fun find(code: String) = db.readBlocking { ProductDao.findByCode(it, Gtin.lookupVariants(code)) }

    private fun search(q: String) = db.readBlocking { ProductDao.search(it, q, 50) }.map { it.name }

    @Test
    fun findsByBarcodeIncludingUpcAndEanForms() {
        val id = TestDb.product(db, "Coca-Cola 1.5L", 450, codes = listOf("036000291452"))
        assertEquals(id, find("036000291452")?.product?.id)
        assertEquals(id, find("0036000291452")?.product?.id) // scanner sends UPC-A as EAN-13
        assertNull(find("9999999999994"))
    }

    /** 2026-10 review: a newer product with only the padded form took over an older product's scans. */
    @Test
    fun theCodeExactlyAsScannedComesBeforeItsOtherForm() {
        val own = TestDb.product(db, "Kuih (own code)", 150, codes = listOf("1234565"))
        val ean8 = TestDb.product(db, "Biskut (EAN-8)", 300, codes = listOf("01234565"))
        assertEquals(own, find("1234565")?.product?.id)
        assertEquals(ean8, find("01234565")?.product?.id)
        // An EAN-13 also finds a product stored with the 14-digit form.
        val gtin14 = TestDb.product(db, "Susu (GTIN-14)", 500, codes = listOf("09556001234568"))
        assertEquals(gtin14, find("9556001234568")?.product?.id)
    }

    @Test
    fun packBarcodeCarriesItsPackSizeAndPrice() {
        val id = db.writeBlocking { tx ->
            val pid = tx.nextId()
            ProductDao.insert(
                tx, Product(id = pid, name = "Milo 1kg", price = 2_500),
                listOf(Barcode(tx.nextId(), pid, "9556001000013"), Barcode(tx.nextId(), pid, "19556001000010", packQty = 12_000, packPrice = 28_000)),
                System.currentTimeMillis(), tx.hlcNow(),
            )
            pid
        }
        val pack = find("19556001000010")
        assertNotNull(pack)
        assertEquals(id, pack.product.id)
        assertEquals(12_000L, pack.packQty)
        assertEquals(28_000L, pack.packPrice)
        assertEquals(1_000L, find("9556001000013")?.packQty)
    }

    @Test
    fun scalePluIsOnlyFoundAsScaleKind() {
        db.writeBlocking { tx ->
            val pid = tx.nextId()
            ProductDao.insert(
                tx, Product(id = pid, name = "Ayam Segar", unit = "kg", sellMode = 1, price = 1_290),
                listOf(Barcode(tx.nextId(), pid, "12345", BarcodeKind.SCALE_PLU)), System.currentTimeMillis(), tx.hlcNow(),
            )
        }
        assertNull(find("12345"))
        assertEquals("Ayam Segar", db.readBlocking { ProductDao.findByCode(it, listOf("12345"), BarcodeKind.SCALE_PLU) }?.product?.name)
    }

    @Test
    fun deletedBarcodesAndProductsAreIgnored() {
        TestDb.product(db, "Old Code Item", 100, codes = listOf("4006381333931"))
        db.writeBlocking { tx -> tx.update("UPDATE product_barcode SET deleted = 1 WHERE code = ?", "4006381333931") }
        assertNull(find("4006381333931"))
        val gone = TestDb.product(db, "Deleted Item", 100, codes = listOf("96385074"))
        db.writeBlocking { tx -> tx.update("UPDATE product SET deleted = 1 WHERE id = ?", gone) }
        assertNull(find("96385074"))
    }

    /** 2026-10 review: the same product on every till, whatever was imported when. */
    @Test
    fun duplicateBarcodeResolvesToTheBarcodeCreatedLastOnEveryTill() {
        val code = "9780306406157"
        val older = TestDb.product(db, "Older", 100, codes = listOf(code))
        val newer = TestDb.product(db, "Newer", 200, codes = listOf(code))
        db.writeBlocking { tx ->
            tx.update("UPDATE product_barcode SET created_at = created_at - 60000 WHERE product_id = ?", older)
        }
        assertEquals(newer, find(code)?.product?.id)
        // A change of the older barcode imported later stamps only this till's updated_at: no effect.
        db.writeBlocking { tx -> tx.update("UPDATE product_barcode SET updated_at = ? WHERE product_id = ?", Long.MAX_VALUE, older) }
        assertEquals(newer, find(code)?.product?.id)
        // The CSV import matches the same product first.
        assertEquals(listOf(newer, older), db.readBlocking { ProductDao.owners(it, listOf(code)) })
        assertEquals(newer, db.readBlocking { ProductDao.ownerOf(it, code) })
        // Created in the same millisecond: the higher id wins, on every till.
        db.writeBlocking { tx -> tx.update("UPDATE product_barcode SET created_at = 1000 WHERE code = ?", code) }
        assertEquals(maxOf(older, newer), find(code)?.product?.id)
    }

    @Test
    fun aDeletedProductIsNotSellableFromAStaleTile() {
        val id = TestDb.product(db, "Roti", 350)
        assertNotNull(db.readBlocking { ProductDao.sellableById(it, id) })
        db.writeBlocking { tx -> ProductDao.delete(tx, id, System.currentTimeMillis()) }
        assertNull(db.readBlocking { ProductDao.sellableById(it, id) })
    }

    @Test
    fun aStockCountPageIncludesSwitchedOffProductsOfItsCategory() {
        TestDb.product(db, "Milo", 100, categoryId = 5L)
        TestDb.product(db, "Milo Lama", 100, categoryId = 5L, active = false)
        TestDb.product(db, "Roti", 100, categoryId = 6L)
        fun names(all: Boolean) =
            db.readBlocking { ProductDao.byCategory(it, 5L, null, 60, includeInactive = all) }.map { it.name }
        assertEquals(listOf("Milo"), names(all = false))
        assertEquals(listOf("Milo", "Milo Lama"), names(all = true))
        val found = db.readBlocking { ProductDao.search(it, "milo", 50, includeInactive = true) }
        assertEquals(listOf(5L, 5L), found.map { it.categoryId }) // the count screen keeps its category's
    }

    @Test
    fun searchMatchesTokenPrefixesAccentsAndCjk() {
        TestDb.product(db, "Milo Activ-Go 1kg", 2_500, sku = "MILO-1K")
        TestDb.product(db, "Crème Caramel", 450)
        TestDb.product(db, "牛奶 1L", 690)
        TestDb.product(db, "Maggi Mee Kari", 480)
        TestDb.product(db, "Hidden Milo", 100, active = false)
        assertEquals(listOf("Milo Activ-Go 1kg"), search("milo"))
        assertEquals(listOf("Milo Activ-Go 1kg"), search("mil act"))
        assertEquals(listOf("Milo Activ-Go 1kg"), search("ACTIV go"))
        assertEquals(listOf("Milo Activ-Go 1kg"), search("1kg"))
        assertEquals(listOf("Milo Activ-Go 1kg"), search("milo-1"))
        assertEquals(listOf("Crème Caramel"), search("creme"))
        assertEquals(listOf("牛奶 1L"), search("奶"))
        assertEquals(emptyList(), search("zzz"))
        assertEquals(emptyList(), search("  -  "))
    }

    /** D-058: one-letter words ("Julie's" → "julie s") filter the candidates of the longer words. */
    @Test
    fun oneLetterWordsFilterTheLongerOnes() {
        TestDb.product(db, "Julie's Cream Crackers", 450)
        TestDb.product(db, "Julie's Peanut Butter", 600)
        TestDb.product(db, "Jacob's Cream Crackers", 500)
        TestDb.product(db, "F&N Orange 325ml", 220)
        TestDb.product(db, "Julie Sardin", 300, active = false)
        assertEquals(listOf("Julie's Cream Crackers", "Julie's Peanut Butter"), search("julie's"))
        assertEquals(listOf("Julie's Cream Crackers"), search("julie s cr"))
        assertEquals(listOf("Jacob's Cream Crackers", "Julie's Cream Crackers"), search("s cream"))
        assertEquals(listOf("F&N Orange 325ml"), search("f n ora"))
        assertEquals(emptyList(), search("julie x"))
        // Name order: "julie s cream…" sorts before "julie sardin" (a space before a letter).
        assertEquals(listOf("Julie's Cream Crackers", "Julie's Peanut Butter", "Julie Sardin"), db.readBlocking { ProductDao.search(it, "julie s", 50, includeInactive = true) }.map { it.name })
    }

    @Test
    fun switchedOffProductsAreFoundOnlyWhenAsked() {
        TestDb.product(db, "Milo 1kg", 2_500, codes = listOf("9556001000013"))
        TestDb.product(db, "Milo Lama", 2_000, codes = listOf("9556001000020"), active = false)
        fun names(q: String, all: Boolean) = db.readBlocking { ProductDao.search(it, q, 50, includeInactive = all) }.map { it.name }
        // Word search (FTS), one-letter name prefix and barcode prefix: selling never sees the
        // switched-off product; product management and pickers do.
        for (q in listOf("milo", "m", "9556001")) {
            assertEquals(listOf("Milo 1kg"), names(q, all = false), q)
            assertEquals(listOf("Milo 1kg", "Milo Lama"), names(q, all = true), q)
        }
        val off = db.readBlocking { ProductDao.search(it, "lama", 50, includeInactive = true) }.single()
        assertEquals(false, off.active)
    }

    @Test
    fun oneLetterSearchIsANamePrefix() {
        TestDb.product(db, "Maggi Mee Kari", 480)
        TestDb.product(db, "Milo", 480)
        TestDb.product(db, "Sos Cili Maggi", 380)
        assertEquals(listOf("Maggi Mee Kari", "Milo"), search("m"))
    }

    @Test
    fun digitSearchMatchesBarcodePrefixes() {
        TestDb.product(db, "Kicap Manis", 350, codes = listOf("9556001000013"))
        TestDb.product(db, "Sardin", 520, codes = listOf("9556002000012"))
        TestDb.product(db, "100 Plus", 250, codes = listOf("8888002000013"))
        assertEquals(listOf("Kicap Manis", "Sardin"), search("95560"))
        assertEquals(listOf("Kicap Manis"), search("9556001"))
        assertEquals(listOf("100 Plus"), search("100")) // name token, not a barcode prefix
    }

    @Test
    fun resultsAreSortedByName() {
        for (n in listOf("Teh C", "Teh B", "Teh A")) TestDb.product(db, n, 100)
        assertEquals(listOf("Teh A", "Teh B", "Teh C"), search("teh"))
    }

    @Test
    fun categoryPagingVisitsEveryProductExactlyOnce() {
        val cat = 77L
        val ids = (1..130).map { i -> TestDb.product(db, "Item ${i % 7}", 100, categoryId = cat) } // many equal names
        TestDb.product(db, "Other category", 100, categoryId = 78L)
        val seen = ArrayList<Long>()
        var last: ProductListItem? = null
        while (true) {
            val page = db.readBlocking { ProductDao.byCategory(it, cat, last, 60) }
            if (page.isEmpty()) break
            seen.addAll(page.map { it.id })
            last = page.last()
        }
        assertEquals(ids.size, seen.size)
        assertEquals(ids.toSet(), seen.toSet())
        val keys = seen.map { id -> db.readBlocking { ProductDao.get(it, id) }!!.name }
        assertTrue(keys.zipWithNext().all { (a, b) -> a <= b })
    }
}
