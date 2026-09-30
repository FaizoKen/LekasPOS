package com.lekaspos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.sync.FieldVersions
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.settings.SettingKeys
import com.lekaspos.data.settings.SettingsDao
import com.lekaspos.testing.TestDb
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Local edits of synced master data: per-field versions and outbox events (references/sync.md §4). */
@RunWith(AndroidJUnit4::class)
class LwwWriteTest {

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = TestDb.fresh()
        db.syncEnabled = true
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    private fun outbox(): List<Triple<Int, Int, String>> = db.readBlocking { r ->
        r.queryList("SELECT entity, op, payload FROM outbox ORDER BY seq") { Triple(it.getInt(0), it.getInt(1), it.getString(2)) }
    }

    private fun create(name: String, price: Long, code: String): Product = db.writeBlocking { tx ->
        val p = Product(id = tx.nextId(), name = name, price = price)
        ProductDao.create(tx, p, listOf(Barcode(tx.nextId(), p.id, code, BarcodeKind.BARCODE)), System.currentTimeMillis())
        p
    }

    @Test
    fun createAndEditWriteOnlyTheChangedFields() {
        val p = create("Milo 1kg", 1890L, "9556001")
        val created = outbox()
        assertEquals(listOf(Entity.PRODUCT to EventOp.LWW, Entity.BARCODE to EventOp.LWW), created.map { it.first to it.second })
        assertTrue(created[0].third.contains("\"name\":\"Milo 1kg\""))
        assertEquals(1, db.readBlocking { ProductDao.search(it, "milo") }.size)

        val changed = db.writeBlocking { tx -> ProductDao.update(tx, p, p.copy(price = 1990L), System.currentTimeMillis()) }
        assertEquals(setOf("price"), changed)
        val fver = FieldVersions.decode(db.readBlocking { it.stringOrNull("SELECT fver FROM product WHERE id = ?", p.id) })
        assertEquals(setOf("price"), fver.fields())
        val last = outbox().last()
        assertTrue(last.third.contains("\"price\":1990") && !last.third.contains("name"), last.third)

        // Nothing changed → nothing written.
        val size = outbox().size
        db.writeBlocking { tx -> ProductDao.update(tx, p.copy(price = 1990L), p.copy(price = 1990L), System.currentTimeMillis()) }
        assertEquals(size, outbox().size)

        db.writeBlocking { tx -> ProductDao.update(tx, p.copy(price = 1990L), p.copy(price = 1990L, name = "Milo Activ-Go 1kg"), System.currentTimeMillis()) }
        assertEquals(1, db.readBlocking { ProductDao.search(it, "activ") }.size)
    }

    @Test
    fun deleteIsATombstoneThatHidesTheProductAndItsBarcodes() {
        val p = create("Roti", 350L, "9556002")
        db.writeBlocking { tx -> ProductDao.delete(tx, p.id, System.currentTimeMillis()) }
        assertEquals(1L, db.readBlocking { it.long("SELECT deleted FROM product WHERE id = ?", p.id) })
        assertEquals(0, db.readBlocking { ProductDao.search(it, "roti") }.size)
        assertEquals(null, db.readBlocking { ProductDao.findByCode(it, listOf("9556002")) })
        assertEquals(0, db.readBlocking { ProductDao.barcodes(it, p.id) }.size)
        // The row stays for sales history.
        assertNotNull(db.readBlocking { ProductDao.get(it, p.id) })
    }

    @Test
    fun barcodeOwnersAreFoundForDuplicateWarnings() {
        val a = create("A", 100L, "12345")
        val b = create("B", 200L, "67890")
        assertEquals(listOf(a.id to "A"), db.readBlocking { ProductDao.codeOwners(it, "12345", b.id) })
        assertEquals(emptyList(), db.readBlocking { ProductDao.codeOwners(it, "12345", a.id) })
    }

    @Test
    fun categoriesTaxRatesAndSettings() {
        val now = System.currentTimeMillis()
        val cat = db.writeBlocking { tx -> CategoryDao.insert(tx, "Minuman", 0, 0, now) }
        db.writeBlocking { tx -> CategoryDao.update(tx, cat, "Minuman sejuk", 0, 1, now) }
        assertEquals(listOf("Minuman sejuk"), db.readBlocking { CategoryDao.list(it) }.map { it.name })
        db.writeBlocking { tx -> CategoryDao.delete(tx, cat, now) }
        assertEquals(0, db.readBlocking { CategoryDao.list(it) }.size)

        val sst = db.writeBlocking { tx -> TaxRateDao.insert(tx, "SST", "S", 600, now) }
        assertEquals(600, db.readBlocking { TaxRateDao.get(it, sst) }?.rateBp)

        val before = outbox().size
        db.writeBlocking { tx ->
            SettingsDao.putChanged(tx, SettingsDao.all(tx.db), mapOf(SettingKeys.STORE_NAME to "Kedai", SettingKeys.STORE_PHONE to ""), now)
        }
        db.writeBlocking { tx ->
            SettingsDao.putChanged(tx, SettingsDao.all(tx.db), mapOf(SettingKeys.STORE_NAME to "Kedai", SettingKeys.STORE_PHONE to "03"), now)
        }
        val events = outbox().drop(before)
        assertEquals(3, events.size) // name + phone, then only the phone
        assertTrue(events.all { it.first == Entity.SETTING })
        assertEquals("03", db.readBlocking { SettingsDao.all(it) }[SettingKeys.STORE_PHONE])
    }

    private fun versioned(sql: String, id: Long): Set<String> =
        FieldVersions.decode(db.readBlocking { it.stringOrNull(sql, id) }).fields()

    @Test
    fun catalogEditsWriteOnlyWhatTheUserChanged() {
        val now = System.currentTimeMillis()
        // Category: the screen showed colour 1; another till set colour 7 meanwhile; the user renames.
        val catId = db.writeBlocking { tx -> CategoryDao.insert(tx, "Minuman", 1, 0, now) }
        val shownCat = assertNotNull(db.readBlocking { CategoryDao.get(it, catId) })
        db.writeBlocking { tx -> CategoryDao.update(tx, catId, "Minuman", 7, 0, now) }
        assertEquals(setOf("color"), versioned("SELECT fver FROM category WHERE id = ?", catId))
        var before = outbox().size
        db.writeBlocking { tx -> CategoryDao.update(tx, shownCat, shownCat.copy(name = "Minuman sejuk"), now) }
        val cat = assertNotNull(db.readBlocking { CategoryDao.get(it, catId) })
        assertEquals("Minuman sejuk", cat.name)
        assertEquals(7, cat.color)
        val catEvent = outbox().drop(before).single().third
        assertTrue(catEvent.contains("\"name\":\"Minuman sejuk\""), catEvent)
        assertTrue(!catEvent.contains("color") && !catEvent.contains("sort"), catEvent)
        // Saving the values as they are stored writes nothing.
        before = outbox().size
        db.writeBlocking { tx -> CategoryDao.update(tx, catId, "Minuman sejuk", 7, 0, now) }
        assertEquals(before, outbox().size)

        // Tax rate: another till raised the rate while this one fixed the name; the new rate stays.
        val sst = db.writeBlocking { tx -> TaxRateDao.insert(tx, "SST", "S", 600, now) }
        val shownTax = assertNotNull(db.readBlocking { TaxRateDao.get(it, sst) })
        db.writeBlocking { tx -> TaxRateDao.update(tx, sst, "SST", "S", 800, now) }
        db.writeBlocking { tx -> TaxRateDao.update(tx, shownTax, shownTax.copy(name = "Cukai jualan"), now) }
        val tax = assertNotNull(db.readBlocking { TaxRateDao.get(it, sst) })
        assertEquals("Cukai jualan", tax.name)
        assertEquals(800, tax.rateBp)
        assertEquals(setOf("rate_bp", "name"), versioned("SELECT fver FROM tax_rate WHERE id = ?", sst))
        before = outbox().size
        db.writeBlocking { tx -> TaxRateDao.update(tx, sst, "Cukai jualan", "S", 800, now) }
        assertEquals(before, outbox().size)

        // Barcode: only the changed field is stamped, and a screen's stale values are not written back.
        val p = create("Milo 1kg", 1890L, "9556001")
        val code = db.readBlocking { ProductDao.barcodes(it, p.id) }.single()
        db.writeBlocking { tx -> ProductDao.updateBarcode(tx, code.copy(packPrice = 2_000L), now) }
        assertEquals(setOf("pack_price"), versioned("SELECT fver FROM product_barcode WHERE id = ?", code.id))
        db.writeBlocking { tx -> ProductDao.updateBarcode(tx, code, code.copy(code = "9556009"), now) }
        val stored = assertNotNull(db.readBlocking { ProductDao.barcode(it, code.id) })
        assertEquals("9556009", stored.code)
        assertEquals(2_000L, stored.packPrice)
        assertEquals(setOf("pack_price", "code"), versioned("SELECT fver FROM product_barcode WHERE id = ?", code.id))
    }

    @Test
    fun anEditScreenKeepsAFieldAnotherTillChangedWhileItWasOpen() {
        val now = System.currentTimeMillis()
        val shown = create("Milo 1kg", 1890L, "9556001") // what the edit screen loaded
        // A delivery on another till moves the average cost while the screen is open.
        db.writeBlocking { tx -> ProductDao.update(tx, shown, shown.copy(cost = 1_200L), now) }
        val changed = db.writeBlocking { tx ->
            val current = assertNotNull(ProductDao.get(tx.db, shown.id))
            ProductDao.update(tx, shown, shown.copy(name = "Milo 2kg"), current, now)
        }
        assertEquals(setOf("name", "name_key"), changed)
        val stored = assertNotNull(db.readBlocking { ProductDao.get(it, shown.id) })
        assertEquals("Milo 2kg", stored.name)
        assertEquals(1_200L, stored.cost)
        assertEquals(1, db.readBlocking { ProductDao.search(it, "2kg") }.size)
        // The user typing the value another till already stored writes nothing.
        val same = db.writeBlocking { tx ->
            ProductDao.update(tx, shown, stored.copy(cost = 1_200L), assertNotNull(ProductDao.get(tx.db, shown.id)), now)
        }
        assertEquals(emptySet<String>(), same)
    }

    @Test
    fun noOutboxWhileSyncIsOff() {
        db.syncEnabled = false
        create("C", 100L, "555")
        assertEquals(0, outbox().size)
    }
}
