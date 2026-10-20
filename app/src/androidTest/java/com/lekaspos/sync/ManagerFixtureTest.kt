package com.lekaspos.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.app.AppGraph
import com.lekaspos.core.inventory.AdjustReason
import com.lekaspos.core.inventory.ReceiveDraft
import com.lekaspos.core.inventory.ReceiveLine
import com.lekaspos.core.model.CashMoveKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.Restore
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.db.Seed
import com.lekaspos.data.product.ProductDao
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.sell.Tender
import com.lekaspos.testing.TestDb
import com.lekaspos.testing.TestGraph
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes the shop the manager web page is checked against (D-082): two tills sync through a folder after
 * a day of what tills record — sales through the cart and checkout (cash with rounding, card, tax, item
 * and bill discounts, credit), a refund, the same sale voided on both tills, price changes on each till,
 * a delivery, stock written off, set and counted, a shift with money out of the drawer, a repayment.
 * The folder and a backup file of each till are left in `files/manager-fixture` (external app files when
 * there are any): scripts/ci/instrumented.sh pulls them and scripts/manager/fixture.test.mjs reads the
 * folder as the web page reads Google Drive and compares its data and reports with both tills'.
 */
@RunWith(AndroidJUnit4::class)
class ManagerFixtureTest {

    private lateinit var folder: File
    private lateinit var provider: FolderProvider
    private val tills = ArrayList<AppGraph>()
    private val cash = PaymentMethod(Seed.Ids.PM_CASH, "Cash", PaymentKind.CASH, true, 1)

    @Before
    fun setUp() {
        folder = File(TestDb.context.cacheDir, "manager-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        provider = FolderProvider(folder)
        Restore.cancelStaged(TestDb.context)
        Restore.finished(TestDb.context)
    }

    @After
    fun tearDown() {
        for (t in tills) TestGraph.destroy(t)
        folder.deleteRecursively()
    }

    private fun till(): AppGraph = TestGraph.create().also { tills.add(it); runBlocking { it.cart.load() } }

    private suspend fun syncAll(vararg ts: AppGraph) = repeat(2) { for (t in ts) t.sync.sync(provider) }

    private fun bill(t: AppGraph, items: List<Pair<Long, Long>>): List<Long> = runBlocking {
        val db = t.db()
        items.map { (pid, qty) -> t.cart.addProduct(TestDb.sellable(db, pid), qty) }
    }

    private suspend fun payCash(t: AppGraph): CheckoutService.Done {
        val total = t.cart.state.value.priced.total
        val given = total + 1_000L
        val s = Settlement.cash(total, given, 5L) as Settlement.Result.Settled
        return t.checkout.complete(listOf(Tender(Seed.Ids.PM_CASH, PaymentKind.CASH, "Cash", true, s.applied, given, s.change)), s.rounding)
    }

    private suspend fun payWith(t: AppGraph, methodId: Long, kind: Int, name: String): CheckoutService.Done {
        val total = t.cart.state.value.priced.total
        return t.checkout.complete(listOf(Tender(methodId, kind, name, false, total, total, 0L)), 0L)
    }

    private suspend fun editPrice(t: AppGraph, productId: Long, price: Long) {
        t.db().write { tx ->
            val p = ProductDao.get(tx.db, productId) ?: error("no product")
            ProductDao.update(tx, p, p.copy(price = price), System.currentTimeMillis())
        }
    }

    @Test
    fun twoTillsMakeAShopForTheManagerPage() = runBlocking {
        val a = till()
        val b = till()
        val adb = a.db()
        val now = System.currentTimeMillis()

        // The catalogue before sync is on: it travels in the first round's backfill.
        val drinks = adb.writeBlocking { tx -> CategoryDao.insert(tx, "Minuman", 0, 0, now) }
        val sst = adb.writeBlocking { tx -> TaxRateDao.insert(tx, "SST 6%", "S", 600, now) }
        val milo = TestDb.product(adb, "Milo 1kg", 2_890L, listOf("9556001000011"), categoryId = drinks, cost = 2_150L)
        val roti = TestDb.product(adb, "Roti Gardenia", 395L, listOf("9556001000028"), cost = 300L)
        val sabun = TestDb.product(adb, "Sabun Breeze", 1_690L, taxRateId = sst, cost = 1_200L)
        a.settings.saveStore(a.settings.store.value.copy(name = "Kedai Ujian", creditEnabled = true))
        a.inventory.setStock(milo, 50_000L)
        a.inventory.setStock(roti, 30_000L)
        a.inventory.setStock(sabun, 20_000L)
        val shiftA = a.shifts.open(20_000L)
        bill(a, listOf(milo to 2_000L, roti to 1_000L))
        val s1 = payCash(a)
        a.sync.enable(provider, "Counter A", join = true)

        // Sync on: every change is an event from here.
        bill(a, listOf(sabun to 1_000L))
        val s2 = payWith(a, Seed.Ids.PM_CARD, PaymentKind.CARD, "Card")
        val keys = bill(a, listOf(milo to 1_000L, roti to 3_000L))
        a.cart.setLineDiscount(keys[1], Discount.Percent(1_000))
        a.cart.setBillDiscount(Discount.Amount(100L))
        payCash(a)
        val line = assertNotNull(a.sales.refundInfo(s1.saleId)).lines.first().id
        a.sales.refund(s1.saleId, mapOf(line to 1_000L), true, "spilt", cash)
        a.shifts.moveCash(CashMoveKind.CASH_OUT, 500L, "ice")
        // As the Receive screen does: the draft is kept while it is entered, then received (InventoryTest).
        val delivery = ReceiveDraft(refNo = "DO-7", lines = listOf(ReceiveLine(1L, roti, "Roti Gardenia", "pcs", 24_000L, 280L, 6_720L)), token = 1L)
        a.inventory.saveDraft(delivery)
        a.inventory.receive(delivery)
        a.inventory.adjust(milo, AdjustReason.DAMAGED, 1_000L, true, "box crushed")
        val session = a.inventory.startCount("Shelf", null)
        a.inventory.count(session, roti, 40_000L)
        a.inventory.finishCount(session)
        val siti = a.customers.save(null, Customer(0L, "Siti", creditLimit = 50_000L))
        bill(a, listOf(milo to 1_000L))
        a.cart.setCustomer(siti.id, siti.name)
        val onCredit = payWith(a, Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, "Customer credit")
        a.customers.receivePayment(siti.id, 1_000L, cash, null)

        b.sync.enable(provider, "Counter B", join = true)
        syncAll(a, b)

        // Both tills offline: each voids the credit sale; each changes a price; B sells and voids A's card sale.
        a.sales.void(onCredit.saleId, "wrong customer")
        b.sales.void(onCredit.saleId, "mistake")
        editPrice(b, milo, 2_990L)
        editPrice(a, roti, 420L)
        b.shifts.open(10_000L)
        bill(b, listOf(milo to 1_000L, sabun to 2_000L))
        payCash(b)
        b.sales.void(s2.saleId, "test")
        val expected = assertNotNull(a.shifts.report(shiftA.id)).cash.expected
        a.shifts.close(expected - 100L, "short 1.00")
        syncAll(a, b)

        val out = File(TestDb.context.getExternalFilesDir(null) ?: TestDb.context.filesDir, "manager-fixture")
        out.deleteRecursively()
        out.mkdirs()
        for (f in folder.listFiles().orEmpty()) {
            // `.name.props` (FolderProvider's side file) is kept as `name.props`: a plain name pulls everywhere.
            val name = if (f.name.startsWith(".") && f.name.endsWith(".props")) f.name.substring(1) else f.name
            f.copyTo(File(out, name), overwrite = true)
        }
        val temp = File(TestDb.context.cacheDir, "manager-fixture-tmp")
        for ((t, name) in listOf(a to "till-a.lekasbak", b to "till-b.lekasbak")) {
            FileOutputStream(File(out, name)).use { o -> BackupFiles.write(t.db(), o, temp, "fixture", "manual") }
        }
        temp.deleteRecursively()
        assertTrue(File(out, "till-a.lekasbak").length() > 0L)
        assertTrue(out.listFiles().orEmpty().count { it.name.startsWith("seg-") && it.name.endsWith(".ndjson.gz") } >= 2)
        assertEquals(1, out.listFiles().orEmpty().count { it.name.startsWith("store-") })
    }
}
