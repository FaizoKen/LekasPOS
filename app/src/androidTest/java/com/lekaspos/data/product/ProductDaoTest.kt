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

    @Test
    fun duplicateBarcodeResolvesToTheMostRecentlyChanged() {
        val older = TestDb.product(db, "Older", 100, codes = listOf("9780306406157"))
        val newer = TestDb.product(db, "Newer", 200, codes = listOf("9780306406157"))
        db.writeBlocking { tx -> tx.update("UPDATE product_barcode SET updated_at = updated_at - 60000 WHERE product_id = ?", older) }
        assertEquals(newer, find("9780306406157")?.product?.id)
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
