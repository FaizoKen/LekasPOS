package com.lekaspos.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.lekaspos.BuildConfig
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PriceLine
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.text.SearchText
import com.lekaspos.core.time.Days
import com.lekaspos.data.cart.CartDao
import com.lekaspos.data.cart.CartLine
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Schema
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleLineDraft
import com.lekaspos.data.sale.SaleRow
import com.lekaspos.data.stock.StockDao
import java.util.Random
import java.util.TimeZone

/**
 * Runs every scenario of references/performance.md against a generated perf database,
 * through the same DAO code the app uses. Blocking: call from a background thread.
 */
class PerfSuite(
    private val context: Context,
    private val db: Db,
    private val scale: PerfScale,
    private val tz: TimeZone = TimeZone.getDefault(),
) {
    fun interface Progress {
        fun update(step: String)
    }

    private val rnd = Random(7L)
    private val r get() = db.sqlite

    fun run(generationMs: Long, progress: Progress = Progress { }, cancelled: () -> Boolean = { false }): PerfReport {
        val startedAt = System.currentTimeMillis()
        val results = ArrayList<PerfResult>()
        fun add(result: PerfResult) {
            results.add(result)
            if (cancelled()) throw PerfDataGenerator.Cancelled()
        }

        progress.update("preparing samples")
        val samples = Samples.load(r, rnd)

        progress.update("db_open")
        add(measure("db_open", 300.0, warmup = 1, n = 5) {
            Db.open(context, db.name).use { other -> other.sqlite.long("SELECT COUNT(*) FROM meta") }
        })

        progress.update("barcode_lookup")
        add(measure("barcode_lookup", 10.0, warmup = 30, n = 300) { i ->
            val code = if (i % 10 == 9) "9999999999994" else samples.barcodes[i % samples.barcodes.size]
            ProductDao.findByCode(r, Gtin.lookupVariants(code))
        })

        progress.update("scan_to_cart")
        val cart = ArrayList<PriceLine>(32)
        add(measure("scan_to_cart", 20.0, warmup = 30, n = 300) { i ->
            val hit = ProductDao.findByCode(r, Gtin.lookupVariants(samples.barcodes[(i * 7) % samples.barcodes.size]))
            if (hit != null) {
                if (cart.size >= 30) cart.clear()
                val p = hit.product
                cart.add(PriceLine(qty = hit.packQty, unitPrice = p.price, taxRateId = p.taxRateId.takeIf { p.taxBp > 0 }, taxBp = p.taxBp))
                PricingEngine.price(cart, Discount.None, pricesIncludeTax = true)
            }
        })

        val searches = listOf(
            "search_1char" to samples.oneChar,
            "search_2char" to samples.twoChar,
            "search_word" to samples.word,
            "search_multiword" to samples.multiWord,
            "search_digits" to samples.digits,
            "search_nomatch" to listOf("zzqxv", "qqzz wxy", "xyzzy"),
        )
        for ((id, terms) in searches) {
            progress.update(id)
            add(measure(id, 50.0, warmup = 10, n = 100) { i -> ProductDao.search(r, terms[i % terms.size], 50) })
        }

        progress.update("category_page")
        add(measure("category_page", 30.0, warmup = 10, n = 100) { i ->
            ProductDao.byCategory(r, samples.categories[i % samples.categories.size], null, 60)
        })

        progress.update("cart_persist")
        val cartId = db.writeBlocking { tx -> CartDao.openCart(tx, null, System.currentTimeMillis()) }
        add(measure("cart_persist", 100.0, warmup = 5, n = 60) { i ->
            val now = System.currentTimeMillis()
            db.writeBlocking(reserveIds = 0) { tx ->
                CartDao.insertLine(
                    tx, cartId,
                    CartLine(lineNo = i + 1, productId = null, name = "Perf item $i", qty = 1000L, unitPrice = 150L, addedAt = now),
                    now,
                )
            }
        })
        db.writeBlocking(reserveIds = 0) { tx -> CartDao.deleteCart(tx, cartId) }

        progress.update("sale_commit")
        val saleProducts = samples.popular.mapNotNull { ProductDao.sellableById(r, it) }
        check(saleProducts.isNotEmpty()) { "perf database has no sold products" }
        val wasSync = db.syncEnabled
        db.syncEnabled = true // worst case: include the outbox write
        try {
            add(measure("sale_commit", 150.0, warmup = 3, n = 60) { i ->
                val draft = saleDraft(saleProducts, i)
                db.writeBlocking(reserveIds = 20) { tx -> SaleDao.commit(tx, draft, tz) }
            })
        } finally {
            db.syncEnabled = wasSync
        }

        progress.update("history")
        add(measure("history_page_first", 50.0, warmup = 5, n = 60) { SaleDao.history(r, null, 50) })
        add(measure("history_page_deep", 50.0, warmup = 5, n = 60) { i ->
            SaleDao.history(r, samples.deepHistory[i % samples.deepHistory.size], 50)
        })

        progress.update("receipt_lookup")
        add(measure("receipt_lookup", 10.0, warmup = 10, n = 200) { i ->
            SaleDao.byReceipt(r, samples.receipts[i % samples.receipts.size])
        })

        progress.update("product_history_page")
        add(measure("product_history_page", 50.0, warmup = 5, n = 60) { i ->
            SaleDao.productHistory(r, samples.popular[i % samples.popular.size], null, 50)
        })

        progress.update("stock")
        add(measure("stock_level", 5.0, warmup = 20, n = 300) { i ->
            StockDao.level(r, samples.popular[i % samples.popular.size])
        })
        add(measure("low_stock_page", 300.0, warmup = 2, n = 20) { StockDao.lowStock(r, 50) })

        val today = Days.epochDay(System.currentTimeMillis(), tz)
        for ((id, days, budget, n) in listOf(
            Quad("report_day", 1L, 300.0, 30),
            Quad("report_month", 30L, 1000.0, 10),
            Quad("report_year", 365L, 3000.0, 3),
        )) {
            progress.update(id)
            add(measure(id, budget, warmup = 1, n = n) { i ->
                val to = today - i % 7 + 1
                val from = to - days
                ReportDao.totals(r, from, to)
                ReportDao.byPayment(r, from, to)
                ReportDao.topProducts(r, from, to, 10)
                ReportDao.byStaff(r, from, to)
            })
        }

        progress.update("query plans")
        val plans = QueryPlans.check(r)

        val counts = linkedMapOf(
            "products" to ProductDao.count(r),
            "sales" to SaleDao.count(r),
            "sale_lines" to r.long("SELECT COUNT(*) FROM sale_line"),
            "payments" to r.long("SELECT COUNT(*) FROM payment"),
        )
        return PerfReport(
            device = deviceInfo(context, r.stringOrNull("SELECT sqlite_version()") ?: "?"),
            scale = scale,
            startedAt = startedAt,
            generationMs = generationMs,
            dbSizeBytes = db.file.length(),
            counts = counts,
            results = results,
            plans = plans,
        )
    }

    private data class Quad(val id: String, val days: Long, val budget: Double, val n: Int)

    private inline fun measure(id: String, budgetMs: Double, warmup: Int, n: Int, op: (Int) -> Unit): PerfResult {
        for (i in 0 until warmup) op(i)
        val samples = LongArray(n)
        for (i in 0 until n) {
            val t = System.nanoTime()
            op(i)
            samples[i] = System.nanoTime() - t
        }
        return PerfResult.from(id, budgetMs, samples)
    }

    /** A realistic 5-line cash sale priced by the real engine. */
    private fun saleDraft(products: List<SellableProduct>, i: Int): SaleDraft {
        val picked = List(5) { k -> products[(i * 5 + k) % products.size] }
        val lines = picked.map { p ->
            PriceLine(qty = 1000L, unitPrice = p.price, taxRateId = p.taxRateId.takeIf { p.taxBp > 0 }, taxBp = p.taxBp)
        }
        val priced = PricingEngine.price(lines, Discount.None, pricesIncludeTax = true)
        val settled = Settlement.cash(priced.total, priced.total + 1000L, 5L) as Settlement.Result.Settled
        val now = System.currentTimeMillis()
        val total = priced.total + settled.rounding
        return SaleDraft(
            openedAt = now - 60_000L,
            soldAt = now,
            pricesInclTax = true,
            subtotal = priced.subtotal,
            discount = priced.discount,
            tax = priced.tax,
            rounding = settled.rounding,
            total = total,
            paid = total,
            change = settled.change,
            lines = picked.mapIndexed { k, p ->
                val pl = priced.lines[k]
                SaleLineDraft(
                    productId = p.id, name = p.name, qty = 1000L, baseQty = 1000L, unitPrice = p.price,
                    gross = pl.gross, discount = pl.lineDiscount, billDiscount = pl.billDiscount, net = pl.net,
                    tax = pl.tax, taxRateId = p.taxRateId.takeIf { p.taxBp > 0 }, taxBp = p.taxBp, cost = p.cost,
                    unit = p.unit, categoryId = p.categoryId, trackStock = p.trackStock,
                )
            },
            payments = listOf(
                PaymentDraft(Seed.Ids.PM_CASH, PaymentKind.CASH, settled.applied, tendered = priced.total + 1000L, change = settled.change),
            ),
        )
    }

    /** Inputs sampled from the generated data (outside the timed loops). */
    private class Samples(
        val barcodes: List<String>,
        val popular: List<Long>,
        val categories: List<Long>,
        val receipts: List<String>,
        val deepHistory: List<SaleRow>,
        val oneChar: List<String>,
        val twoChar: List<String>,
        val word: List<String>,
        val multiWord: List<String>,
        val digits: List<String>,
    ) {
        companion object {
            fun load(r: android.database.sqlite.SQLiteDatabase, rnd: Random): Samples {
                val barcodeCount = r.long("SELECT COUNT(*) FROM product_barcode")
                val step = (barcodeCount / 400L).coerceAtLeast(1L)
                val barcodes = r.queryList(
                    "SELECT code FROM product_barcode WHERE kind = 0 AND deleted = 0 AND rowid % ? = 0 LIMIT 400", args(step),
                ) { it.getString(0) }
                val popular = r.queryList(
                    "SELECT product_id FROM sum_day_product WHERE product_id != 0 GROUP BY product_id ORDER BY SUM(qty) DESC LIMIT 50",
                ) { it.getLong(0) }
                val categories = r.queryList("SELECT id FROM category WHERE deleted = 0") { it.getLong(0) }
                val saleCount = r.long("SELECT COUNT(*) FROM sale")
                val receipts = r.queryList(
                    "SELECT receipt_no FROM sale WHERE rowid % ? = 0 LIMIT 200", args((saleCount / 200L).coerceAtLeast(1L)),
                ) { it.getString(0) }
                val deep = r.queryList(
                    "SELECT id, kind, receipt_no, sold_at, total, status, line_count FROM sale ORDER BY sold_at LIMIT 20",
                ) { c -> SaleRow(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5), c.getInt(6)) }
                val names = r.queryList(
                    "SELECT name FROM product WHERE rowid % ? = 0 LIMIT 300",
                    args((r.long("SELECT COUNT(*) FROM product") / 300L).coerceAtLeast(1L)),
                ) { it.getString(0) }
                val tokenLists = names.map { SearchText.tokens(it).filter { t -> t.any(Char::isLetter) } }.filter { it.size >= 2 }
                fun token() = tokenLists[rnd.nextInt(tokenLists.size)]
                val oneChar = List(50) { token()[0].take(1) }
                val twoChar = List(50) { token()[0].take(2) }
                val word = List(50) { val t = token()[0]; t.take(3 + rnd.nextInt(3)) }
                val multi = List(50) { val t = token(); t[0] + " " + t[1].take(3) }
                val digits = List(50) { barcodes[rnd.nextInt(barcodes.size)].take(6 + rnd.nextInt(5)) }
                return Samples(barcodes, popular, categories, receipts, deep, oneChar, twoChar, word, multi, digits)
            }
        }
    }

    companion object {
        fun deviceInfo(context: Context, sqliteVersion: String): DeviceInfo {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE,
                totalRamMb = mi.totalMem / (1024L * 1024L),
                lowRamDevice = am.isLowRamDevice,
                memoryClassMb = am.memoryClass,
                sqliteVersion = sqliteVersion,
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) schema v${Schema.VERSION}",
                abi = abi,
            )
        }
    }
}
