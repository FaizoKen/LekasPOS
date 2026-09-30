package com.lekaspos.data.shift

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.pricing.Discount
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.sale.CommittedSale
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.testing.TestDb
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Shift totals (D-038): a void belongs to the shift it happens in, tax and discounts included. */
@RunWith(AndroidJUnit4::class)
class ShiftDaoTest {

    private lateinit var db: Db
    private val tz = TimeZone.getTimeZone("Asia/Kuala_Lumpur")

    @Before
    fun setUp() {
        db = TestDb.fresh()
    }

    @After
    fun tearDown() {
        TestDb.delete(db)
    }

    /** (tax, discount) of a stored sale. */
    private fun taxAndDiscount(s: CommittedSale): Pair<Long, Long> = assertNotNull(
        db.readBlocking { r ->
            r.queryOne("SELECT tax, discount FROM sale WHERE id = ?", args(s.id)) { it.getLong(0) to it.getLong(1) }
        },
    )

    private fun totals(shiftId: Long) = db.readBlocking { ShiftDao.totals(it, shiftId) }

    @Test
    fun voidsTakeTheirTaxAndDiscountOffTheShiftTheyHappenIn() {
        val now = System.currentTimeMillis()
        val sst = db.writeBlocking { tx -> TaxRateDao.insert(tx, "SST", "S", 600, now) }
        val p = TestDb.product(db, "Minyak 5kg", 10_600L, taxRateId = sst)
        val first = db.writeBlocking { tx -> ShiftDao.open(tx, 1L, 0L, now) }
        fun sell(discount: Discount) = db.writeBlocking { tx ->
            SaleDao.commit(tx, TestDb.saleDraft(db, listOf(p to 1_000L), billDiscount = discount).copy(shiftId = first.id), tz)
        }
        val kept = sell(Discount.None)
        val voided = sell(Discount.Amount(1_000L))
        val (keptTax, keptDiscount) = taxAndDiscount(kept)
        val (voidedTax, voidedDiscount) = taxAndDiscount(voided)
        assertTrue(keptTax > 0L && voidedTax > 0L && voidedDiscount > 0L)

        // Voided in the same shift: the shift's tax and discounts are those of the kept sale only.
        db.writeBlocking { tx -> SaleDao.void(tx, voided.id, "wrong item", null, null, first.id, now) }
        val t = totals(first.id)
        assertEquals(2, t.docs.single { it.kind == SaleKind.SALE }.count) // sales made in the shift, voided one included
        assertEquals(1, t.voidCount)
        assertEquals(voidedTax, t.voidTax)
        assertEquals(voidedDiscount, t.voidDiscount)
        assertEquals(keptTax, t.tax)
        assertEquals(keptDiscount, t.discount)

        // Voided in a later shift: the earlier shift's report does not change; the later one takes it off.
        val second = db.writeBlocking { tx -> ShiftDao.open(tx, 1L, 0L, now + 1L) }
        db.writeBlocking { tx -> SaleDao.void(tx, kept.id, "customer changed mind", null, null, second.id, now + 2L) }
        assertEquals(t, totals(first.id))
        val later = totals(second.id)
        assertEquals(-keptTax, later.tax)
        assertEquals(-keptDiscount, later.discount)
    }
}
