package com.lekaspos.perf

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.CashMoveKind
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.model.SaleStatus
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.Rounding
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PriceLine
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.text.SearchText
import com.lekaspos.core.time.Days
import com.lekaspos.core.time.Hlc
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.DerivedRebuild
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.pragma
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.ReceiptNumbers
import com.lekaspos.data.sale.SaleDao
import java.util.Random
import java.util.TimeZone

/** Data volumes for the perf suite (references/performance.md §2). */
enum class PerfScale(val products: Int, val sales: Int, val days: Int) {
    /** Correctness tests only. */
    TINY(300, 600, 20),
    QUICK(5_000, 20_000, 120),
    FULL(50_000, 250_000, 365),
}

/**
 * Builds a separate, deterministic database (`perf.db`) with a realistic Malaysian grocery
 * catalogue and a history of sales, refunds and voids. Products go through the real
 * ProductDao; sales are bulk-inserted with the same SQL as SaleDao and the derived tables are
 * then rebuilt with DerivedRebuild (proven equivalent to the incremental path by tests).
 */
class PerfDataGenerator(
    private val context: Context,
    private val scale: PerfScale,
    private val seed: Long = 20260928L,
    private val tz: TimeZone = TimeZone.getDefault(),
) {
    fun interface Progress {
        fun update(phase: String, done: Int, total: Int)
    }

    class Cancelled : RuntimeException("cancelled")

    private val rnd = Random(seed)

    fun generate(progress: Progress = Progress { _, _, _ -> }, cancelled: () -> Boolean = { false }): Db {
        delete(context)
        val db = Db.open(context, DB_NAME, storeData = false)
        try {
            val now = System.currentTimeMillis()
            val catalog = createCatalog(db, now, progress, cancelled)
            val shifts = createShifts(db, catalog, now)
            createSales(db, catalog, shifts, now, progress, cancelled)
            createStockWork(db, catalog, now, progress, cancelled)
            createCustomers(db, catalog, shifts, now, progress, cancelled)
            val steps = listOf<(Db.Tx) -> Unit>(
                DerivedRebuild::refundedAmounts, DerivedRebuild::stockLevels, DerivedRebuild::summaries, DerivedRebuild::customerBalances,
            )
            for ((i, step) in steps.withIndex()) {
                if (cancelled()) throw Cancelled()
                progress.update("derived", i, steps.size)
                db.writeBlocking(reserveIds = 0) { tx -> step(tx) }
            }
            db.readBlocking { it.pragma("PRAGMA wal_checkpoint(FULL)") }
            progress.update("derived", steps.size, steps.size)
            return db
        } catch (t: Throwable) {
            db.close()
            throw t
        }
    }

    // ------------------------------------------------------------------ catalogue

    private class Catalog(n: Int) {
        val id = LongArray(n)
        val name = arrayOfNulls<String>(n)
        val code = arrayOfNulls<String>(n)
        val unit = arrayOfNulls<String>(n)
        val price = LongArray(n)
        val cost = LongArray(n)
        val category = LongArray(n)
        val taxRate = LongArray(n) // 0 = none
        val taxBp = IntArray(n)
        val weighed = BooleanArray(n)
        val tracked = BooleanArray(n)

        /** Popularity rank → product index (so popular items are not just the first IDs). */
        val byRank = IntArray(n)
        val staff = LongArray(3)
    }

    private fun createCatalog(db: Db, now: Long, progress: Progress, cancelled: () -> Boolean): Catalog {
        val n = scale.products
        val cat = Catalog(n)
        val taxIds = LongArray(2)
        val categoryIds = LongArray(CATEGORIES.size)
        db.writeBlocking { tx ->
            val hlc = tx.hlcNow()
            val rates = intArrayOf(1000, 800)
            val names = arrayOf("SST 10%", "Service 8%")
            for (i in 0..1) {
                taxIds[i] = tx.nextId()
                tx.insert(
                    "INSERT INTO tax_rate(id, name, rate_bp, created_at, updated_at, ver_hlc, ver_dev) VALUES(?,?,?,?,?,?,?)",
                    taxIds[i], names[i], rates[i], now, now, hlc, tx.deviceNo,
                )
            }
            for ((i, c) in CATEGORIES.withIndex()) {
                categoryIds[i] = tx.nextId()
                tx.insert(
                    "INSERT INTO category(id, name, name_key, sort, created_at, updated_at, ver_hlc, ver_dev) VALUES(?,?,?,?,?,?,?,?)",
                    categoryIds[i], c, SearchText.key(c), i, now, now, hlc, tx.deviceNo,
                )
            }
            for (i in 0 until 3) {
                cat.staff[i] = tx.nextId()
                tx.insert(
                    "INSERT INTO staff(id, name, role_id, created_at, updated_at, ver_hlc, ver_dev) VALUES(?,?,?,?,?,?,?)",
                    cat.staff[i], STAFF[i], Seed.Ids.ROLE_CASHIER, now, now, hlc, tx.deviceNo,
                )
            }
        }

        var gtinBody = 955_000_000_000L // 955 = GS1 Malaysia; body has 12 digits
        var plu = 10_000
        val chunk = 2_000
        var i = 0
        while (i < n) {
            if (cancelled()) throw Cancelled()
            val end = minOf(n, i + chunk)
            db.writeBlocking(reserveIds = (end - i) * 4L) { tx ->
                for (k in i until end) {
                    val weighed = rnd.nextInt(100) < 2
                    val chinese = !weighed && rnd.nextInt(100) < 2
                    val name = when {
                        weighed -> FRESH[rnd.nextInt(FRESH.size)] + " " + VARIANTS[rnd.nextInt(VARIANTS.size)]
                        chinese -> CHINESE[rnd.nextInt(CHINESE.size)] + " " + SIZES[rnd.nextInt(SIZES.size)]
                        else -> BRANDS[rnd.nextInt(BRANDS.size)] + " " + ITEMS[rnd.nextInt(ITEMS.size)] +
                            VARIANTS[rnd.nextInt(VARIANTS.size)].let { if (it.isEmpty()) "" else " $it" } +
                            " " + SIZES[rnd.nextInt(SIZES.size)]
                    }.trim()
                    val price = if (weighed) 500L + rnd.nextInt(3_500) else priceOf(rnd)
                    val cost = Rounding.mulDivHalfUp(price, 60L + rnd.nextInt(26), 100L)
                    val taxIdx = when (rnd.nextInt(100)) {
                        in 0..6 -> 0
                        7 -> 1
                        else -> -1
                    }
                    val productId = tx.nextId()
                    val code = Gtin.withCheckDigit((gtinBody++).toString())
                    val barcodes = ArrayList<Barcode>(2)
                    barcodes.add(Barcode(tx.nextId(), productId, code))
                    if (weighed) {
                        barcodes.add(Barcode(tx.nextId(), productId, (plu++).toString(), BarcodeKind.SCALE_PLU))
                    } else if (rnd.nextInt(100) < 5) {
                        val pack = if (rnd.nextBoolean()) 12L else 24L
                        barcodes.add(
                            Barcode(
                                tx.nextId(), productId, Gtin.withCheckDigit((gtinBody++).toString()),
                                packQty = pack * 1000L, packPrice = price * pack * 95L / 100L,
                            ),
                        )
                    }
                    val p = Product(
                        id = productId,
                        name = name,
                        sku = "SKU-" + (100_000 + k),
                        categoryId = categoryIds[rnd.nextInt(categoryIds.size)],
                        unit = if (weighed) "kg" else "pcs",
                        sellMode = if (weighed) SellMode.WEIGHT else SellMode.UNIT,
                        price = price,
                        cost = cost,
                        taxRateId = if (taxIdx >= 0) taxIds[taxIdx] else null,
                        trackStock = rnd.nextInt(100) < 95,
                        lowStock = if (rnd.nextInt(100) < 30) (5L + rnd.nextInt(16)) * 1000L else 0L,
                    )
                    ProductDao.insert(tx, p, barcodes, now, tx.hlcNow())
                    // Every fifth product has a tile colour (D-066): the lists' join finds rows. No draw of `rnd`,
                    // so the rest of the generated store stays as it was.
                    if (k % 5 == 0) {
                        tx.insert(
                            "INSERT INTO product_look(id, color, created_at, updated_at, ver_hlc, ver_dev) VALUES(?,?,?,?,?,?)",
                            productId, 1 + k % 12, now, now, tx.hlcNow(), tx.deviceNo,
                        )
                    }
                    if (p.trackStock) {
                        tx.insert(
                            "INSERT INTO stock_movement(id, product_id, kind, qty, unit_cost, at, hlc) VALUES(?,?,?,?,?,?,?)",
                            tx.nextId(), productId, MovementKind.OPENING, (20L + rnd.nextInt(300)) * 1000L, cost,
                            now - scale.days * Days.DAY_MS, Hlc.pack(now - scale.days * Days.DAY_MS, k and 0xFFFF),
                        )
                    }
                    cat.id[k] = productId
                    cat.name[k] = name
                    cat.code[k] = code
                    cat.unit[k] = p.unit
                    cat.price[k] = price
                    cat.cost[k] = cost
                    cat.category[k] = p.categoryId ?: 0L
                    cat.taxRate[k] = p.taxRateId ?: 0L
                    cat.taxBp[k] = if (taxIdx == 0) 1000 else if (taxIdx == 1) 800 else 0
                    cat.weighed[k] = weighed
                    cat.tracked[k] = p.trackStock
                }
            }
            i = end
            progress.update("products", i, n)
        }
        // Random popularity order.
        for (k in 0 until n) cat.byRank[k] = k
        for (k in n - 1 downTo 1) {
            val j = rnd.nextInt(k + 1)
            val t = cat.byRank[k]
            cat.byRank[k] = cat.byRank[j]
            cat.byRank[j] = t
        }
        return cat
    }

    // ------------------------------------------------------------------ sales

    private fun createSales(db: Db, cat: Catalog, shifts: LongArray, now: Long, progress: Progress, cancelled: () -> Boolean) {
        val total = scale.sales
        val start = now - scale.days * Days.DAY_MS
        val step = scale.days * Days.DAY_MS / total
        val prefix = ReceiptNumbers.defaultPrefix(db.deviceNo)
        var saleSeq = 0L
        var refundSeq = 0L
        var hlcCounter = 0
        val chunk = 1_000
        var i = 0
        while (i < total) {
            if (cancelled()) throw Cancelled()
            val end = minOf(total, i + chunk)
            db.writeBlocking(reserveIds = (end - i) * 20L) { tx ->
                for (s in i until end) {
                    val soldAt = start + s * step + rnd.nextInt(step.toInt().coerceAtLeast(1))
                    val hlc = Hlc.pack(soldAt, (hlcCounter++) and 0xFFFF)
                    val staff = cat.staff[rnd.nextInt(cat.staff.size)]
                    val shift = shifts[dayIndex(soldAt, start)]
                    val sale = insertSale(tx, cat, soldAt, hlc, staff, prefix, ++saleSeq, shift)
                    val roll = rnd.nextInt(1000)
                    if (roll < 10) {
                        tx.insert(
                            SaleDao.INSERT_VOID, tx.nextId(), sale.saleId, "Wrong item scanned", staff, cat.staff[0], shift,
                            soldAt + 60_000L, Hlc.pack(soldAt + 60_000L, (hlcCounter++) and 0xFFFF),
                        )
                        tx.update("UPDATE sale SET status = ? WHERE id = ?", SaleStatus.VOIDED, sale.saleId)
                    } else if (roll < 15) {
                        insertRefund(
                            tx, cat, sale, soldAt + 3_600_000L, (hlcCounter++) and 0xFFFF, staff, prefix, ++refundSeq,
                            shifts[dayIndex(soldAt + 3_600_000L, start)],
                        )
                    }
                }
            }
            i = end
            progress.update("sales", i, total)
        }
        db.writeBlocking(reserveIds = 0) { tx ->
            Meta.put(tx.db, Meta.RECEIPT_PREFIX, prefix)
            Meta.put(tx.db, Meta.docSeqKey(SaleKind.SALE), saleSeq.toString())
            Meta.put(tx.db, Meta.docSeqKey(SaleKind.REFUND), refundSeq.toString())
        }
    }

    // ------------------------------------------------------------------ shifts, customers, credit (Phase 4)

    private fun dayIndex(at: Long, start: Long): Int = ((at - start) / Days.DAY_MS).toInt().coerceIn(0, scale.days - 1)

    /** One closed shift a day on this till, with a morning float and two cash movements. */
    private fun createShifts(db: Db, cat: Catalog, now: Long): LongArray {
        val start = now - scale.days * Days.DAY_MS
        val ids = LongArray(scale.days)
        db.writeBlocking(reserveIds = scale.days * 4L) { tx ->
            for (d in 0 until scale.days) {
                val opened = start + d * Days.DAY_MS + 7L * 3_600_000L
                val closed = opened + 14L * 3_600_000L
                val id = tx.nextId()
                ids[d] = id
                tx.insert(
                    "INSERT INTO shift(id, device_no, opened_by, opened_at, opening_float, closed_by, closed_at, counted_cash, " +
                        "expected_cash, deleted, created_at, updated_at, ver_hlc, ver_dev) VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                    id, tx.deviceNo, cat.staff[0], opened, 20_000L, cat.staff[0], closed, 0L, 0L, opened, closed,
                    Hlc.pack(opened, d and 0xFFFF), tx.deviceNo,
                )
                for ((k, kind) in intArrayOf(CashMoveKind.DROP, CashMoveKind.CASH_OUT).withIndex()) {
                    val at = opened + (4L + k * 5L) * 3_600_000L
                    tx.insert(
                        "INSERT INTO cash_movement(id, shift_id, kind, amount, reason, staff_id, at, hlc) VALUES(?,?,?,?,?,?,?,?)",
                        tx.nextId(), id, kind, if (kind == CashMoveKind.DROP) 50_000L else 1_500L, "perf", cat.staff[0], at,
                        Hlc.pack(at, 1 + k),
                    )
                }
            }
        }
        return ids
    }

    /**
     * Customers (products / 25) with credit: charges and repayments spread over the period,
     * a few regulars owning most entries (like a real shop's notebook).
     */
    private fun createCustomers(db: Db, cat: Catalog, shifts: LongArray, now: Long, progress: Progress, cancelled: () -> Boolean) {
        val start = now - scale.days * Days.DAY_MS
        val n = (scale.products / 25).coerceAtLeast(20)
        val ids = LongArray(n)
        db.writeBlocking(reserveIds = n.toLong()) { tx ->
            for (i in 0 until n) {
                val id = tx.nextId()
                ids[i] = id
                val name = FIRST[i % FIRST.size] + " " + LAST[(i / FIRST.size) % LAST.size] + " " + (i + 1)
                tx.insert(
                    "INSERT INTO customer(id, name, name_key, phone, credit_limit, deleted, created_at, updated_at, ver_hlc, ver_dev) " +
                        "VALUES(?,?,?,?,?,0,?,?,?,?)",
                    id, name, SearchText.key(name), "01" + (10_000_000 + i * 37).toString(), if (i % 3 == 0) 0L else 20_000L,
                    start, start, Hlc.pack(start, i and 0xFFFF), tx.deviceNo,
                )
            }
        }
        val entries = scale.sales / 8
        val chunk = 2_000
        var e = 0
        var counter = 0
        while (e < entries) {
            if (cancelled()) throw Cancelled()
            val end = minOf(entries, e + chunk)
            db.writeBlocking(reserveIds = (end - e).toLong()) { tx ->
                for (k in e until end) {
                    val at = start + (k.toLong() * scale.days * Days.DAY_MS) / entries
                    // Squared pick: low indexes (regulars) get most entries.
                    val c = ids[((rnd.nextDouble() * rnd.nextDouble()) * n).toInt().coerceIn(0, n - 1)]
                    val payment = k % 3 == 2
                    tx.insert(
                        "INSERT INTO credit_entry(id, customer_id, kind, amount, sale_id, method_id, staff_id, note, at, hlc, shift_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        tx.nextId(), c, if (payment) CreditKind.PAYMENT else CreditKind.CHARGE, 500L + rnd.nextInt(4_000),
                        null, if (payment) Seed.Ids.PM_CASH else Seed.Ids.PM_CREDIT, cat.staff[0], null, at,
                        Hlc.pack(at, (counter++) and 0xFFFF), shifts[dayIndex(at, start)],
                    )
                }
            }
            e = end
            progress.update("customers", e, entries)
        }
    }

    // ------------------------------------------------------------------ stock work (Phase 3)

    /**
     * Suppliers, one delivery a day (15 lines, movements sharing the line ids like PurchaseDao),
     * manual adjustments and a stock count a month — inserted in bulk; levels are rebuilt after.
     */
    private fun createStockWork(db: Db, cat: Catalog, now: Long, progress: Progress, cancelled: () -> Boolean) {
        val start = now - scale.days * Days.DAY_MS
        var counter = 0
        val suppliers = LongArray(40)
        db.writeBlocking(reserveIds = 100L) { tx ->
            for (i in suppliers.indices) {
                val id = tx.nextId()
                suppliers[i] = id
                val name = "Pembekal ${i + 1} Sdn Bhd"
                tx.insert(
                    "INSERT INTO supplier(id, name, name_key, deleted, created_at, updated_at, ver_hlc, ver_dev) VALUES(?,?,?,0,?,?,?,?)",
                    id, name, SearchText.key(name), start, start, Hlc.pack(start, i), tx.deviceNo,
                )
            }
        }
        val days = scale.days
        var d = 0
        while (d < days) {
            if (cancelled()) throw Cancelled()
            val end = minOf(days, d + 30)
            db.writeBlocking(reserveIds = (end - d) * 40L) { tx ->
                for (day in d until end) {
                    val at = start + day * Days.DAY_MS + 8L * 3_600_000L
                    val hlc = Hlc.pack(at, (counter++) and 0xFFFF)
                    val purchaseId = tx.nextId()
                    val lines = ArrayList<LongArray>(15)
                    var total = 0L
                    for (l in 0 until 15) {
                        var k = pick(cat)
                        if (!cat.tracked[k]) k = cat.byRank[l]
                        val qty = (6L + rnd.nextInt(60)) * 1000L
                        val unitCost = cat.cost[k]
                        val lineTotal = Rounding.mulDivHalfUp(unitCost, qty, 1000L)
                        total += lineTotal
                        lines.add(longArrayOf(tx.nextId(), cat.id[k], qty, unitCost, lineTotal))
                    }
                    tx.insert(
                        "INSERT INTO purchase(id, supplier_id, ref_no, total, note, staff_id, at, hlc) VALUES(?,?,?,?,NULL,?,?,?)",
                        purchaseId, suppliers[rnd.nextInt(suppliers.size)], "DO-$day", total, cat.staff[0], at, hlc,
                    )
                    for (v in lines) {
                        tx.insert("INSERT INTO purchase_line(id, purchase_id, product_id, qty, unit_cost, total) VALUES(?,?,?,?,?,?)", v[0], purchaseId, v[1], v[2], v[3], v[4])
                        tx.insert(
                            "INSERT INTO stock_movement(id, product_id, kind, qty, unit_cost, ref_id, staff_id, at, hlc) VALUES(?,?,?,?,?,?,?,?,?)",
                            v[0], v[1], MovementKind.RECEIVE, v[2], v[3], purchaseId, cat.staff[0], at, hlc,
                        )
                    }
                }
            }
            d = end
            progress.update("deliveries", d, days)
        }
        val adjustments = scale.sales / 50
        val reasons = arrayOf("damaged", "expired", "lost", "correction")
        db.writeBlocking(reserveIds = adjustments + 10L) { tx ->
            for (a in 0 until adjustments) {
                val k = pick(cat)
                if (!cat.tracked[k]) continue
                val at = start + (a.toLong() * scale.days * Days.DAY_MS) / adjustments.coerceAtLeast(1)
                val r = reasons[rnd.nextInt(reasons.size)]
                tx.insert(
                    "INSERT INTO stock_movement(id, product_id, kind, qty, reason, staff_id, at, hlc) VALUES(?,?,?,?,?,?,?,?)",
                    tx.nextId(), cat.id[k], if (r == "damaged" || r == "expired") MovementKind.WASTE else MovementKind.ADJUST,
                    -(1L + rnd.nextInt(3)) * 1000L, r, cat.staff[0], at, Hlc.pack(at, (counter++) and 0xFFFF),
                )
            }
        }
        val sessions = (scale.days / 30).coerceAtLeast(1)
        val perSession = (scale.products / 100).coerceAtLeast(5)
        for (s in 0 until sessions) {
            if (cancelled()) throw Cancelled()
            db.writeBlocking(reserveIds = perSession + 10L) { tx ->
                val at = start + (s + 1L) * 30L * Days.DAY_MS
                val sessionId = tx.nextId()
                val name = "Kiraan ${s + 1}"
                tx.insert(
                    "INSERT INTO count_session(id, name, status, started_at, finished_at, deleted, created_at, updated_at, ver_hlc, ver_dev) " +
                        "VALUES(?,?,1,?,?,0,?,?,?,?)",
                    sessionId, name, at, at + 3_600_000L, at, at, Hlc.pack(at, (counter++) and 0xFFFF), tx.deviceNo,
                )
                for (c in 0 until perSession) {
                    val k = pick(cat)
                    if (!cat.tracked[k]) continue
                    val qty = (rnd.nextInt(200)).toLong() * 1000L
                    val t = at + c * 1_000L
                    tx.insert(
                        "INSERT INTO stock_count(id, product_id, qty, session_id, staff_id, at, hlc, expected, unit_cost) VALUES(?,?,?,?,?,?,?,?,?)",
                        tx.nextId(), cat.id[k], qty, sessionId, cat.staff[0], t, Hlc.pack(t, (counter++) and 0xFFFF),
                        qty + (rnd.nextInt(5) - 2) * 1000L, cat.cost[k],
                    )
                }
            }
            progress.update("counts", s + 1, sessions)
        }
    }

    private fun pick(cat: Catalog): Int {
        // Skewed popularity: rank ≈ n·u³ puts most sales on a few hundred products.
        val u = rnd.nextDouble()
        val rank = (cat.id.size * u * u * u).toInt().coerceIn(0, cat.id.size - 1)
        return cat.byRank[rank]
    }

    /** The first line of a generated sale, kept in memory so refunds need no query. */
    private class GeneratedSale(
        val saleId: Long,
        val lineId: Long,
        val productIndex: Int,
        val qty: Long,
        val gross: Long,
        val lineDiscount: Long,
        val billDiscount: Long,
        val net: Long,
        val tax: Long,
        val cost: Long,
    )

    private fun insertSale(tx: Db.Tx, cat: Catalog, soldAt: Long, hlc: Long, staff: Long, prefix: String, seq: Long, shift: Long): GeneratedSale {
        val count = 1 + rnd.nextInt(7)
        val idx = IntArray(count) { pick(cat) }
        val qty = LongArray(count) { k ->
            if (cat.weighed[idx[k]]) 100L + rnd.nextInt(1_900) else 1000L * (if (rnd.nextInt(10) < 8) 1 else 2 + rnd.nextInt(2))
        }
        val lines = List(count) { k ->
            val p = idx[k]
            PriceLine(
                qty = qty[k],
                unitPrice = cat.price[p],
                discount = if (rnd.nextInt(100) < 5) Discount.Percent(1000) else Discount.None,
                taxRateId = if (cat.taxBp[p] > 0) cat.taxRate[p] else null,
                taxBp = cat.taxBp[p],
            )
        }
        val bill = if (rnd.nextInt(100) < 3) Discount.Amount(100L + rnd.nextInt(400)) else Discount.None
        val priced = PricingEngine.price(lines, bill, pricesIncludeTax = true)

        val payRoll = rnd.nextInt(100)
        val payKind: Int
        val methodId: Long
        var rounding = 0L
        var tendered = 0L
        var change = 0L
        var applied = priced.total
        if (payRoll < 70 && priced.total > 0) {
            payKind = PaymentKind.CASH
            methodId = Seed.Ids.PM_CASH
            val due = Settlement.cashDue(priced.total, 5L)
            tendered = if (rnd.nextBoolean()) due else ((due + 999L) / 1000L) * 1000L
            val r = Settlement.cash(priced.total, tendered, 5L) as Settlement.Result.Settled
            rounding = r.rounding
            applied = r.applied
            change = r.change
        } else {
            payKind = if (payRoll < 90) PaymentKind.CARD else PaymentKind.EWALLET
            methodId = if (payKind == PaymentKind.CARD) Seed.Ids.PM_CARD else Seed.Ids.PM_EWALLET
        }
        val totalDue = priced.total + rounding
        val day = Days.epochDay(soldAt, tz)
        var cost = 0L
        val lineCost = LongArray(count) { k ->
            Rounding.mulDivHalfUp(cat.cost[idx[k]], qty[k], 1000L).also { cost += it }
        }

        val saleId = tx.nextId()
        tx.insert(
            SaleDao.INSERT_SALE, saleId, SaleKind.SALE, ReceiptNumbers.format(prefix, SaleKind.SALE, seq), tx.deviceNo,
            seq, null, shift, staff, null, soldAt - 90_000L, soldAt, day, count, priced.subtotal, priced.discount,
            priced.tax, rounding, totalDue, totalDue, change, cost, true, SaleStatus.COMPLETED, 0L, null, hlc,
        )
        var firstLineId = 0L
        for (k in 0 until count) {
            val p = idx[k]
            val pl = priced.lines[k]
            val lineId = tx.nextId()
            if (k == 0) firstLineId = lineId
            tx.insert(
                SaleDao.INSERT_LINE, lineId, saleId, k + 1, cat.id[p], null, cat.name[p], cat.code[p], cat.unit[p],
                cat.category[p], qty[k], qty[k], cat.price[p], pl.gross, pl.lineDiscount, pl.billDiscount, pl.net,
                if (cat.taxBp[p] > 0) cat.taxRate[p] else null, cat.taxBp[p], pl.tax, lineCost[k], false,
                if (cat.tracked[p]) -qty[k] else 0L, hlc, null, null,
            )
        }
        tx.insert(SaleDao.INSERT_PAY, tx.nextId(), saleId, methodId, payKind, applied, tendered, change, null, shift, soldAt)
        val first = priced.lines[0]
        return GeneratedSale(
            saleId, firstLineId, idx[0], qty[0], first.gross, first.lineDiscount, first.billDiscount, first.net, first.tax, lineCost[0],
        )
    }

    /** Refunds the first line of [sale] in full, by card (exact amount). No query: values come from memory. */
    private fun insertRefund(
        tx: Db.Tx,
        cat: Catalog,
        sale: GeneratedSale,
        at: Long,
        counter: Int,
        staff: Long,
        prefix: String,
        seq: Long,
        shift: Long,
    ) {
        val p = sale.productIndex
        val hlc = Hlc.pack(at, counter)
        val refundId = tx.nextId()
        val net = sale.net
        val day = Days.epochDay(at, tz)
        tx.insert(
            SaleDao.INSERT_SALE, refundId, SaleKind.REFUND, ReceiptNumbers.format(prefix, SaleKind.REFUND, seq),
            tx.deviceNo, seq, sale.saleId, shift, staff, null, at, at, day, 1, -sale.gross,
            -(sale.lineDiscount + sale.billDiscount), -sale.tax, 0L, -net, -net, 0L, -sale.cost, true,
            SaleStatus.COMPLETED, 0L, "Customer return", hlc,
        )
        tx.insert(
            SaleDao.INSERT_LINE, tx.nextId(), refundId, 1, cat.id[p], sale.lineId, cat.name[p], cat.code[p], cat.unit[p],
            cat.category[p], -sale.qty, -sale.qty, cat.price[p], -sale.gross, -sale.lineDiscount, -sale.billDiscount, -net,
            if (cat.taxBp[p] > 0) cat.taxRate[p] else null, cat.taxBp[p], -sale.tax, -sale.cost, false,
            if (cat.tracked[p]) sale.qty else 0L, hlc, null, null,
        )
        tx.insert(SaleDao.INSERT_PAY, tx.nextId(), refundId, Seed.Ids.PM_CARD, PaymentKind.CARD, -net, 0L, 0L, null, shift, at)
    }

    companion object {
        const val DB_NAME = "perf.db"
        private val FIRST = listOf("Ali", "Siti", "Ah Kow", "Mei Ling", "Ravi", "Priya", "Aminah", "Wong", "Kumar", "Farah")
        private val LAST = listOf("Bakar", "Tan", "Lim", "Raj", "Ismail", "Lee", "Hassan", "Chong", "Nair", "Yusof")

        fun delete(context: Context): Boolean = SQLiteDatabase.deleteDatabase(context.getDatabasePath(DB_NAME))

        fun exists(context: Context): Boolean = context.getDatabasePath(DB_NAME).exists()

        private fun priceOf(r: Random): Long {
            // Mostly cheap items (RM0.50–RM20), some mid, a few expensive (up to RM150).
            val u = r.nextInt(100)
            val ringgit100 = when {
                u < 70 -> 50 + r.nextInt(1_950)
                u < 95 -> 2_000 + r.nextInt(4_000)
                else -> 6_000 + r.nextInt(9_000)
            }
            return (ringgit100 / 10 * 10).toLong() // whole 10 sen
        }

        val CATEGORIES = arrayOf(
            "Minuman", "Makanan Ringan", "Roti & Kek", "Susu & Tenusu", "Beras & Bijirin", "Minyak & Lemak",
            "Rempah & Perasa", "Mi & Pasta", "Makanan Dalam Tin", "Sayur-sayuran", "Buah-buahan", "Daging & Ayam",
            "Ikan & Makanan Laut", "Makanan Beku", "Kopi & Teh", "Gula & Tepung", "Sos & Kicap", "Coklat & Gula-gula",
            "Biskut", "Sarapan", "Bayi", "Kesihatan", "Penjagaan Diri", "Penjagaan Mulut", "Rambut", "Kebersihan Rumah",
            "Dobi", "Tisu & Kertas", "Barangan Plastik", "Alat Tulis", "Rokok", "Aiskrim", "Kuih Tradisional",
            "Import", "Organik", "Haiwan Peliharaan", "Bateri & Elektrik", "Dapur", "Musim Perayaan", "Lain-lain",
        )
        private val STAFF = arrayOf("Aisyah", "Kumar", "Mei Ling")
        private val BRANDS = arrayOf(
            "Milo", "Nestle", "Gardenia", "Massimo", "Dutch Lady", "F&N", "Ayam Brand", "Maggi", "Mamee", "Julie's",
            "Hup Seng", "Munchy's", "Adabi", "Brahim's", "Kimball", "Life", "Jalen", "Faiza", "Cap Rambutan", "Saji",
            "Knife", "Buruh", "Seri Murni", "Vitagen", "Yeo's", "Pokka", "100 Plus", "Coca-Cola", "Pepsi", "Spritzer",
            "Cactus", "Colgate", "Darlie", "Dettol", "Lifebuoy", "Dynamo", "Breeze", "Top", "Softlan", "Kleenex",
            "Premier", "Cutie", "Lee Kum Kee", "Kewpie", "Nescafe", "Boh", "Lipton", "Old Town", "Ah Huat", "Alicafe",
            "Indomie", "Cintan", "Kraft", "Oreo", "Twisties", "Mister Potato", "Tiger", "Marigold", "Farm Fresh", "Anlene",
        )
        private val ITEMS = arrayOf(
            "Susu Tepung", "Roti Putih", "Roti Gandum", "Mee Segera", "Biskut Krim", "Kopi 3 in 1", "Teh Uncang",
            "Minyak Masak", "Beras Wangi", "Gula Pasir", "Tepung Gandum", "Sos Cili", "Kicap Manis", "Sardin",
            "Susu Pekat", "Jus Oren", "Air Mineral", "Minuman Isotonik", "Ubat Gigi", "Syampu", "Sabun Mandi",
            "Pencuci Pinggan", "Serbuk Pencuci", "Pelembut Fabrik", "Tisu Muka", "Lampin Bayi", "Coklat Susu",
            "Kacang Panggang", "Keropok Ikan", "Mayonis", "Sos Tiram", "Mentega Kacang", "Jem Strawberi",
            "Bijirin Sarapan", "Oat Segera", "Santan Kotak", "Yogurt", "Aiskrim Vanila", "Chocolate Milk",
            "Instant Noodles", "Cream Crackers", "Peanut Butter", "Corn Flakes", "Curry Powder", "Fish Balls",
            "Frozen Nuggets", "Canned Tuna", "Baked Beans", "Green Tea", "Orange Juice", "Soya Bean Drink",
            "Chilli Sauce", "Tomato Ketchup", "Hand Wash", "Floor Cleaner", "Toilet Roll", "Garbage Bags",
            "Wafer", "Kuih Bangkit", "Dodol",
        )
        private val VARIANTS = arrayOf("", "", "", "Original", "Pedas", "Kurang Manis", "Lite", "Extra", "Premium", "Asli")
        private val SIZES = arrayOf(
            "100g", "200g", "250g", "400g", "500g", "1kg", "2kg", "5kg", "250ml", "500ml", "1L", "1.5L", "2L", "12s",
            "24s", "30s", "5x79g", "155g", "750ml", "Family Pack",
        )
        private val FRESH = arrayOf(
            "Ayam Segar", "Ikan Kembung", "Udang Harimau", "Daging Lembu", "Pisang Berangan", "Tembikai", "Bawang Merah",
            "Bawang Putih", "Kentang", "Tomato", "Kobis Bulat", "Cili Merah", "Halia", "Limau Nipis", "Betik",
        )
        private val CHINESE = arrayOf("李锦记 蚝油", "老干妈 辣椒酱", "海天 生抽", "康师傅 方便面", "维他 豆奶", "旺旺 雪饼", "统一 冰红茶")
    }
}
