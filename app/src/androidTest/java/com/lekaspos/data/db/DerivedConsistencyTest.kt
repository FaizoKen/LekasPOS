package com.lekaspos.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.pricing.Discount
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleLineDraft
import com.lekaspos.core.model.SaleKind
import com.lekaspos.testing.TestDb
import java.util.Random
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The incremental maintenance done while selling (SaleDao/Summaries/StockDao) and the set-based
 * DerivedRebuild must agree exactly — the perf data generator and future restores rely on it.
 */
@RunWith(AndroidJUnit4::class)
class DerivedConsistencyTest {

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

    /** Rows of the derived tables, without all-zero rows (a fully voided day leaves zeros behind). */
    private fun snapshot(): Map<String, List<String>> = db.readBlocking { r ->
        fun rows(sql: String) = r.queryList(sql) { c ->
            (0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "null" else c.getString(i) }
        }.filterNot { row -> row.split("|").drop(2).all { it == "0" || it == "null" } }
        mapOf(
            "sum_day" to rows("SELECT day, sale_count + refund_count + void_count, sale_count, refund_count, void_count, gross, discount, net_ex, tax, rounding, total, cost, refund_total, items FROM sum_day ORDER BY day"),
            "sum_day_product" to rows("SELECT day, product_id, qty, net_ex, tax, cost FROM sum_day_product ORDER BY day, product_id"),
            "sum_month_product" to rows("SELECT month, product_id, qty, net_ex, tax, cost FROM sum_month_product ORDER BY month, product_id"),
            "sum_year_product" to rows("SELECT year, product_id, qty, net_ex, tax, cost FROM sum_year_product ORDER BY year, product_id"),
            // v8 (D-058): the category totals.
            "sum_day_category" to rows("SELECT day, category_id, qty, net_ex, cost FROM sum_day_category ORDER BY day, category_id"),
            "sum_month_category" to rows("SELECT month, category_id, qty, net_ex, cost FROM sum_month_category ORDER BY month, category_id"),
            "sum_year_category" to rows("SELECT year, category_id, qty, net_ex, cost FROM sum_year_category ORDER BY year, category_id"),
            "sum_day_payment" to rows("SELECT day, method_id, amount, count FROM sum_day_payment ORDER BY day, method_id"),
            "sum_day_staff" to rows("SELECT day, staff_id, sale_count, total, net_ex FROM sum_day_staff ORDER BY day, staff_id"),
            "stock_level" to r.queryList("SELECT product_id, qty FROM stock_level WHERE qty != 0 ORDER BY product_id") { "${it.getLong(0)}|${it.getLong(1)}" },
            "refunded" to r.queryList("SELECT id, refunded FROM sale WHERE refunded != 0 ORDER BY id") { "${it.getLong(0)}|${it.getLong(1)}" },
        )
    }

    @Test
    fun incrementalMaintenanceEqualsFullRebuild() {
        val rnd = Random(99)
        val now = System.currentTimeMillis()
        val cats = listOf("A", "B", "C").map { n -> db.writeBlocking { tx -> CategoryDao.insert(tx, n, 0, 0, now) } } + listOf<Long?>(null)
        val products = (1..12).map { i -> TestDb.product(db, "Product $i", 100L + 37L * i, trackStock = i % 4 != 0, categoryId = cats[i % 4]) }
        // 150 sales over 45 days: the per-month table crosses at least one month boundary.
        val start = System.currentTimeMillis() - 45 * 86_400_000L
        val saleIds = ArrayList<Long>()
        repeat(150) { n ->
            val items = List(1 + rnd.nextInt(4)) { products[rnd.nextInt(products.size)] to 1_000L * (1 + rnd.nextInt(3)) }
            val draft = TestDb.saleDraft(
                db, items, soldAt = start + n * 25_920_000L,
                payKind = if (rnd.nextBoolean()) PaymentKind.CASH else PaymentKind.CARD,
                staffId = if (rnd.nextBoolean()) 1L else null,
                billDiscount = if (rnd.nextInt(5) == 0) Discount.Amount(50) else Discount.None,
            )
            saleIds.add(db.writeBlocking { tx -> SaleDao.commit(tx, draft, tz) }.id)
            when (rnd.nextInt(12)) {
                0 -> db.writeBlocking { tx -> SaleDao.void(tx, saleIds[rnd.nextInt(saleIds.size)], "test", null, null, null, 0L) }
                1 -> refund(saleIds[rnd.nextInt(saleIds.size)], start + n * 25_920_000L + 60_000L)
                // A product moves to another category (or none): its history follows it (D-058).
                2 -> db.writeBlocking { tx ->
                    val p = ProductDao.get(tx.db, products[rnd.nextInt(products.size)]) ?: error("no product")
                    ProductDao.update(tx, p, p.copy(categoryId = cats[rnd.nextInt(cats.size)]), now)
                }
                else -> Unit
            }
        }
        val incremental = snapshot()
        db.writeBlocking(reserveIds = 0) { tx -> DerivedRebuild.all(tx) }
        val rebuilt = snapshot()
        for (key in incremental.keys) assertEquals(incremental[key], rebuilt[key], "derived table $key differs")
    }

    private fun refund(saleId: Long, at: Long) {
        if (db.readBlocking { SaleDao.isVoided(it, saleId) }) return
        val line = db.readBlocking { SaleDao.lines(it, saleId) }.first()
        val tracked = line.productId?.let { TestDb.sellable(db, it).trackStock } ?: false
        db.writeBlocking { tx ->
            SaleDao.commit(
                tx,
                SaleDraft(
                    kind = SaleKind.REFUND, refSaleId = saleId, openedAt = at, soldAt = at, pricesInclTax = true,
                    subtotal = -line.gross, discount = -(line.gross - line.net), tax = -line.tax, rounding = 0,
                    total = -line.net, paid = -line.net, change = 0,
                    lines = listOf(
                        SaleLineDraft(
                            productId = line.productId, refLineId = line.id, name = line.name, qty = -line.qty,
                            baseQty = -line.qty, unitPrice = line.unitPrice, gross = -line.gross, net = -line.net, tax = -line.tax,
                            trackStock = tracked,
                        ),
                    ),
                    payments = listOf(PaymentDraft(Seed.Ids.PM_CARD, PaymentKind.CARD, -line.net)),
                ),
                tz,
            )
        }
    }
}
