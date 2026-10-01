package com.lekaspos.data.sale

import android.database.Cursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.DerivedRebuild
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.data.shift.ShiftTotals
import com.lekaspos.data.sync.Importer
import com.lekaspos.data.sync.SyncEvent
import com.lekaspos.testing.TestDb
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same credit sale voided on two tills while offline (2026-10 review): the customer's credit
 * comes back once and only the first void counts in shift reports, in any arrival order.
 */
@RunWith(AndroidJUnit4::class)
class VoidConflictTest {

    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
    private val customer = 4_242L
    private val dbs = ArrayList<Db>()
    private lateinit var home: Db
    private var beras = 0L
    private var saleId = 0L
    private var saleHlc = 0L
    private var total = 0L

    private fun fresh(): Db = TestDb.fresh().also { dbs.add(it) }

    /** A credit sale on [home], charged to [customer] as the checkout does. */
    @Before
    fun setUp() {
        home = fresh()
        beras = TestDb.product(home, "Beras 10kg", 3_290L)
        val card = TestDb.saleDraft(home, listOf(beras to 1_000L), payKind = PaymentKind.CARD)
        val draft = card.copy(
            customerId = customer,
            payments = listOf(PaymentDraft(Seed.Ids.PM_CREDIT, PaymentKind.CREDIT, card.total)),
        )
        total = draft.total
        val sale = home.writeBlocking { tx ->
            SaleDao.commit(tx, draft, tz).also {
                CustomerDao.insertCredit(
                    tx, customer, CreditKind.CHARGE, total, it.id, Seed.Ids.PM_CREDIT, null, null, null, draft.soldAt,
                )
            }
        }
        saleId = sale.id
        saleHlc = sale.hlc
    }

    @After
    fun tearDown() {
        for (d in dbs) TestDb.delete(d)
    }

    /** Rows of [sql] as column → value maps, as a sync payload carries them. */
    private fun rows(db: Db, sql: String, vararg bind: Any?): List<Map<String, Any?>> = db.readBlocking { r ->
        r.queryList(sql, args(*bind)) { c ->
            (0 until c.columnCount).associate { i ->
                c.getColumnName(i) to when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    else -> c.getString(i)
                }
            }
        }
    }

    private fun saleEvent(): SyncEvent {
        val (sale, lines, pays) = assertNotNull(home.readBlocking { SaleDao.exportRows(it, saleId) })
        val payload = mapOf("sale" to sale, "lines" to lines, "pays" to pays)
        return SyncEvent(Entity.SALE, EventOp.INSERT, saleId, saleHlc, payload)
    }

    private fun chargeEvent(): SyncEvent {
        val row = rows(home, "SELECT * FROM credit_entry WHERE sale_id = ?", saleId).single()
        return SyncEvent(Entity.CREDIT, EventOp.INSERT, row["id"] as Long, row["hlc"] as Long, row)
    }

    private class TillVoid(val id: Long, val void: SyncEvent, val reversal: SyncEvent)

    /** A void made on till [dev] in [shift], with the credit reversal SaleActions.void writes with it. */
    private fun voidOn(dev: Int, hlc: Long, shift: Long): TillVoid {
        val id = Ids.make(dev, 10L)
        val creditId = Ids.make(dev, 11L)
        val at = System.currentTimeMillis()
        val void = mapOf<String, Any?>(
            "id" to id, "sale_id" to saleId, "reason" to "wrong item", "staff_id" to null, "approved_by" to null,
            "shift_id" to shift, "at" to at, "hlc" to hlc,
        )
        val credit = mapOf<String, Any?>(
            "id" to creditId, "customer_id" to customer, "kind" to CreditKind.CHARGE.toLong(), "amount" to -total,
            "sale_id" to saleId, "method_id" to Seed.Ids.PM_CREDIT, "staff_id" to null, "note" to "void: wrong item",
            "at" to at, "hlc" to hlc + 1L, "shift_id" to shift,
        )
        return TillVoid(
            id,
            SyncEvent(Entity.SALE_VOID, EventOp.INSERT, id, hlc, void),
            SyncEvent(Entity.CREDIT, EventOp.INSERT, creditId, hlc + 1L, credit),
        )
    }

    private fun apply(db: Db, events: List<SyncEvent>) {
        for (e in events) db.writeBlocking { tx -> Importer(tx.db).apply(tx, e) }
    }

    private fun balance(db: Db): Long = db.readBlocking { CustomerDao.balance(it, customer) }

    private fun count(db: Db, sql: String, vararg bind: Any?): Long = db.readBlocking { it.long(sql, *bind) }

    private fun creditRows(db: Db): List<String> = db.readBlocking { r ->
        r.queryList(
            "SELECT id, customer_id, kind, amount, sale_id, method_id, note, at, hlc, shift_id " +
                "FROM credit_entry ORDER BY id",
        ) { c -> (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "null" else c.getString(it) } }
    }

    private fun charged(t: ShiftTotals): Long =
        t.credit.filter { it.kind == CreditKind.CHARGE }.sumOf { it.amount }

    @Test
    fun aSaleVoidedOnTwoTillsGivesItsCreditBackOnceInAnyOrder() {
        val shiftA = Ids.make(1_001, 1L)
        val shiftB = Ids.make(2_002, 1L)
        val a = voidOn(1_001, saleHlc + 1_000L, shiftA) // the first void
        val b = voidOn(2_002, saleHlc + 2_000L, shiftB) // a later one: its credit reversal is cancelled
        val sale = saleEvent()
        val charge = chargeEvent()
        val orders = listOf(
            listOf(sale, charge, a.void, a.reversal, b.void, b.reversal),
            listOf(b.reversal, b.void, a.reversal, charge, a.void, sale), // both voids before the sale
            listOf(b.void, sale, b.reversal, a.void, charge, a.reversal), // the first void arrives second
        )
        val tills = orders.map { events -> fresh().also { apply(it, events) } }
        val expectedRows = creditRows(tills[0])
        for (t in tills) {
            assertEquals(0L, balance(t))
            assertEquals(expectedRows, creditRows(t))
            assertEquals(1L, count(t, "SELECT COUNT(*) FROM credit_entry WHERE id = ?", -b.id))
            assertEquals(0L, count(t, "SELECT COUNT(*) FROM credit_entry WHERE id = ?", -a.id))
            assertEquals(SaleStatus.VOIDED.toLong(), count(t, "SELECT status FROM sale WHERE id = ?", saleId))

            // Only the first void counts in shift reports; the later shift's credit nets out.
            val first = t.readBlocking { ShiftDao.totals(it, shiftA) }
            val later = t.readBlocking { ShiftDao.totals(it, shiftB) }
            assertEquals(1, first.voidCount)
            assertEquals(total, first.voidTotal)
            assertEquals(0, later.voidCount)
            assertEquals(0L, later.voidTotal)
            assertEquals(-total, charged(first))
            assertEquals(0L, charged(later))

            t.writeBlocking(reserveIds = 0) { tx -> DerivedRebuild.customerBalances(tx) }
            assertEquals(0L, balance(t))
        }
    }

    @Test
    fun aVoidMadeHereLosesToAnEarlierVoidFromAnotherTill() {
        val now = System.currentTimeMillis()
        home.writeBlocking { tx ->
            SaleDao.void(tx, saleId, "wrong item", null, null, 77L, now)
            CustomerDao.insertCredit(
                tx, customer, CreditKind.CHARGE, -total, saleId, Seed.Ids.PM_CREDIT, null, 77L, "void: wrong item", now,
            )
        }
        assertEquals(0L, balance(home))
        val homeVoid = count(home, "SELECT id FROM sale_void WHERE sale_id = ?", saleId)
        val other = voidOn(2_002, saleHlc, 88L) // before this till's void by hlc

        apply(home, listOf(other.void, other.reversal, other.void)) // a repeated delivery changes nothing
        assertEquals(0L, balance(home))
        assertEquals(1L, count(home, "SELECT COUNT(*) FROM credit_entry WHERE id = ?", -homeVoid))
        assertEquals(1, home.readBlocking { ShiftDao.totals(it, 88L) }.voidCount)
        val mine = home.readBlocking { ShiftDao.totals(it, 77L) }
        assertEquals(0, mine.voidCount)
        assertEquals(0L, charged(mine))
    }

    @Test
    fun theRebuildTakesTheStatusFromTheVoids() {
        val now = System.currentTimeMillis()
        home.writeBlocking { tx -> SaleDao.void(tx, saleId, "wrong item", null, null, null, now) }
        val kept = home.writeBlocking { tx -> SaleDao.commit(tx, TestDb.saleDraft(home, listOf(beras to 1_000L)), tz) }
        home.writeBlocking { tx ->
            tx.update("UPDATE sale SET status = ? WHERE id = ?", SaleStatus.COMPLETED, saleId)
            tx.update("UPDATE sale SET status = ? WHERE id = ?", SaleStatus.VOIDED, kept.id)
        }
        home.writeBlocking(reserveIds = 0) { tx -> DerivedRebuild.all(tx) }
        assertEquals(SaleStatus.VOIDED.toLong(), count(home, "SELECT status FROM sale WHERE id = ?", saleId))
        assertEquals(SaleStatus.COMPLETED.toLong(), count(home, "SELECT status FROM sale WHERE id = ?", kept.id))
    }
}
