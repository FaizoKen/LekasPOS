package com.lekaspos.testing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PriceLine
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Seed
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleLineDraft
import java.util.UUID

/** Fresh throw-away databases and small fixtures for instrumented tests. */
object TestDb {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Assets of the test APK (schema snapshots). */
    val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    fun fresh(name: String = "test-${UUID.randomUUID()}.db"): Db {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(name))
        return Db.open(context, name)
    }

    fun delete(db: Db) {
        val file = db.file
        db.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    /** Inserts a product with one barcode per code; returns the product ID. */
    fun product(
        db: Db,
        name: String,
        price: Long,
        codes: List<String> = emptyList(),
        categoryId: Long? = null,
        sku: String? = null,
        trackStock: Boolean = true,
        active: Boolean = true,
        cost: Long = price / 2,
        taxRateId: Long? = null,
    ): Long = db.writeBlocking { tx ->
        val id = tx.nextId()
        val barcodes = codes.map { Barcode(tx.nextId(), id, it) }
        ProductDao.insert(
            tx,
            Product(id = id, name = name, sku = sku, categoryId = categoryId, price = price, cost = cost, trackStock = trackStock, active = active, taxRateId = taxRateId),
            barcodes, System.currentTimeMillis(), tx.hlcNow(),
        )
        id
    }

    fun sellable(db: Db, id: Long): SellableProduct = db.readBlocking { ProductDao.sellableById(it, id) } ?: error("no product $id")

    /** A priced sale of [items] (product, qty milli) paid in full with [payKind]. */
    fun saleDraft(
        db: Db,
        items: List<Pair<Long, Long>>,
        soldAt: Long = System.currentTimeMillis(),
        payKind: Int = PaymentKind.CASH,
        staffId: Long? = null,
        billDiscount: Discount = Discount.None,
    ): SaleDraft {
        val products = items.map { sellable(db, it.first) }
        val priced = PricingEngine.price(
            items.mapIndexed { i, (_, qty) ->
                val p = products[i]
                PriceLine(qty = qty, unitPrice = p.price, taxRateId = p.taxRateId.takeIf { p.taxBp > 0 }, taxBp = p.taxBp)
            },
            billDiscount,
            pricesIncludeTax = true,
        )
        var rounding = 0L
        var tendered = 0L
        var change = 0L
        var applied = priced.total
        val methodId: Long
        if (payKind == PaymentKind.CASH) {
            methodId = Seed.Ids.PM_CASH
            tendered = Settlement.cashDue(priced.total, 5L) + 1000L
            val s = Settlement.cash(priced.total, tendered, 5L) as Settlement.Result.Settled
            rounding = s.rounding
            applied = s.applied
            change = s.change
        } else {
            methodId = Seed.Ids.PM_CARD
        }
        val total = priced.total + rounding
        return SaleDraft(
            staffId = staffId,
            openedAt = soldAt - 30_000L,
            soldAt = soldAt,
            pricesInclTax = true,
            subtotal = priced.subtotal,
            discount = priced.discount,
            tax = priced.tax,
            rounding = rounding,
            total = total,
            paid = total,
            change = change,
            lines = items.mapIndexed { i, (pid, qty) ->
                val p = products[i]
                val pl = priced.lines[i]
                SaleLineDraft(
                    productId = pid, name = p.name, qty = qty, baseQty = qty, unitPrice = p.price, gross = pl.gross,
                    discount = pl.lineDiscount, billDiscount = pl.billDiscount, net = pl.net, tax = pl.tax,
                    taxRateId = p.taxRateId.takeIf { p.taxBp > 0 }, taxBp = p.taxBp,
                    cost = p.cost * qty / 1000L, unit = p.unit, categoryId = p.categoryId, trackStock = p.trackStock,
                )
            },
            payments = listOf(PaymentDraft(methodId, payKind, applied, tendered, change)),
        )
    }
}
