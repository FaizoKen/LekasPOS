package com.lekaspos.domain.sell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.MovementKind
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PriceCheckTest {

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = TestGraph.create()
        runBlocking { graph.cart.load() }
    }

    @After
    fun tearDown() = TestGraph.destroy(graph)

    @Test
    fun barcodesPacksNamesAndStockWithoutTouchingTheBill() = runBlocking {
        val db = graph.db()
        val milo = TestDb.product(db, "Milo 3in1", 150L, listOf("9556001000011"))
        db.writeBlocking { tx ->
            ProductDao.addBarcode(tx, Barcode(tx.nextId(), milo, "9556001000028", packQty = 24_000L, packPrice = 3_300L), System.currentTimeMillis())
            StockDao.insertMovement(tx, milo, MovementKind.OPENING, 48_000L, 100L, null, null, null, System.currentTimeMillis())
        }
        TestDb.product(db, "Roti", 350L, trackStock = false)

        val byCode = graph.priceCheck.lookup("9556001000011")
        assertEquals(listOf("Milo 3in1"), byCode.map { it.name })
        assertEquals(150L, byCode[0].price)
        assertEquals(48_000L, byCode[0].stock)
        assertEquals(listOf(PriceCheck.Pack(24_000L, 3_300L)), byCode[0].packs)
        // The pack barcode finds the same product.
        assertEquals(milo, graph.priceCheck.lookup("9556001000028").single().productId)

        val byName = graph.priceCheck.lookup("roti")
        assertEquals("Roti", byName.single().name)
        assertNull(byName.single().stock) // stock not tracked

        assertTrue(graph.priceCheck.lookup("0000000000000").isEmpty())
        assertTrue(graph.cart.state.value.cart.items.isEmpty()) // nothing was sold
    }
}
