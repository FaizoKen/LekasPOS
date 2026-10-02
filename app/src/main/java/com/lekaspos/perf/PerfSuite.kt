package com.lekaspos.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.lekaspos.BuildConfig
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.csv.CsvWriter
import com.lekaspos.core.csv.ProductCsv
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.report.Period
import com.lekaspos.core.escpos.PrinterProfile
import com.lekaspos.core.id.Ids
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.escpos.ReceiptEncoder
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PriceLine
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.pricing.Settlement
import com.lekaspos.core.staff.PinHash
import com.lekaspos.core.text.SearchText
import com.lekaspos.core.time.Days
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.cart.CartDao
import com.lekaspos.data.cart.CartLine
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Schema
import com.lekaspos.data.db.Seed
import com.lekaspos.data.db.args
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.data.purchase.PurchaseDao
import com.lekaspos.data.purchase.PurchaseIn
import com.lekaspos.data.purchase.PurchaseLineIn
import com.lekaspos.data.report.ReceiptRow
import com.lekaspos.data.report.ReportDao
import com.lekaspos.data.sale.PaymentDraft
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.data.sale.SaleDraft
import com.lekaspos.data.sale.SaleLineDraft
import com.lekaspos.data.sale.SaleRow
import com.lekaspos.data.settings.StoreSettings
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.data.stock.CountSessionDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.stock.StockHistoryDao
import com.lekaspos.data.sync.Importer
import com.lekaspos.data.sync.SyncEvent
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.domain.report.ReportService
import com.lekaspos.domain.shift.ShiftService
import com.lekaspos.hw.printer.ReceiptRenderer
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
            Db.open(context, db.name, storeData = false).use { other -> other.sqlite.long("SELECT COUNT(*) FROM meta") }
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
        val (maxCart, maxLine) = CartDao.maxIds(r)
        val cartId = maxCart + 1L
        db.writeBlocking { tx -> CartDao.insertCart(tx, cartId, null, System.currentTimeMillis(), System.currentTimeMillis()) }
        add(measure("cart_persist", 100.0, warmup = 5, n = 60) { i ->
            val now = System.currentTimeMillis()
            db.writeBlocking(reserveIds = 0) { tx ->
                CartDao.putLine(
                    tx, cartId,
                    CartLine(
                        id = maxLine + 1L + i, lineNo = i + 1, productId = null, name = "Perf item $i", qty = 1000L,
                        unitPrice = 150L, addedAt = now,
                    ),
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
            // A 20-line delivery: purchase, lines, movements, 20 moving-average cost updates, outbox.
            add(measure("receive_commit", 300.0, warmup = 2, n = 30) { i ->
                val lines = List(20) { k ->
                    PurchaseLineIn(saleProducts[(i * 20 + k) % saleProducts.size].id, 12_000L, 100L, 1_200L)
                }
                db.writeBlocking(reserveIds = 60) { tx -> PurchaseDao.commit(tx, PurchaseIn(null, "PERF", null, lines), null, System.currentTimeMillis()) }
            })
        } finally {
            db.syncEnabled = wasSync
        }

        progress.update("receipt")
        val store = StoreSettings(name = "Perf Store", address = "1 Jalan Ujian\n43000 Kajang", sstNo = "W10-0000-00000000")
        val lastSale = SaleDao.history(r, null, 1).firstOrNull()?.id ?: error("perf database has no sales")
        add(measure("receipt_text", 50.0, warmup = 3, n = 40) {
            val doc = ReceiptBuilder.build(r, lastSale, copy = false, store, tz) ?: error("sale $lastSale missing")
            ReceiptEncoder.text(ReceiptBuilder.layout(doc, 32, store, logo = false, tz), PrinterProfile())
        })
        add(measure("receipt_image", 500.0, warmup = 1, n = 10) {
            val doc = ReceiptBuilder.build(r, lastSale, copy = false, store, tz) ?: error("sale $lastSale missing")
            val img = ReceiptRenderer(32, 384).mono(ReceiptBuilder.layout(doc, 32, store, logo = false, tz), null, null)
            ReceiptEncoder.image(img, PrinterProfile())
        })

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
        add(measure("low_stock_count", 300.0, warmup = 1, n = 5) { StockDao.lowStockCount(r) })
        add(measure("stock_history_page", 50.0, warmup = 5, n = 60) { i ->
            StockHistoryDao.page(r, samples.popular[i % samples.popular.size], null, 50)
        })
        add(measure("movement_page", 50.0, warmup = 5, n = 60) { StockDao.movementPage(r, null, 50) })
        add(measure("purchase_page", 50.0, warmup = 5, n = 60) { PurchaseDao.page(r, null, null, 50) })
        val sessions = CountSessionDao.list(r, 20)
        if (sessions.isNotEmpty()) {
            add(measure("count_page", 50.0, warmup = 5, n = 60) { i -> CountSessionDao.counts(r, sessions[i % sessions.size].id, null, 50) })
        }

        // Phase 4: shifts, customers and credit, the activity log, PIN checks.
        progress.update("shifts")
        val shiftPage = ShiftDao.page(r, null, 50)
        add(measure("shift_page", 50.0, warmup = 5, n = 60) { ShiftDao.page(r, null, 50) })
        add(measure("shift_current", 10.0, warmup = 10, n = 100) { ShiftDao.current(r, db.deviceNo) })
        if (shiftPage.size > 3) {
            val staffNames = StaffDao.names(r)
            val methodNames = PaymentMethodDao.names(r)
            // A full day's shift (hundreds of sales): everything the report and the close need.
            add(measure("shift_report", 300.0, warmup = 2, n = 20) { i ->
                val s = shiftPage[1 + i % (shiftPage.size - 1)]
                ShiftService.build(s, ShiftDao.totals(r, s.id), methodNames, staffNames, "")
            })
        }
        progress.update("customers")
        add(measure("customer_page", 50.0, warmup = 5, n = 60) { i -> CustomerDao.page(r, if (i % 2 == 0) "" else "ali", null, 50) })
        add(measure("customer_phone", 50.0, warmup = 5, n = 60) { CustomerDao.byPhone(r, "0110") })
        val regular = r.longOrNull("SELECT customer_id FROM credit_entry GROUP BY customer_id ORDER BY COUNT(*) DESC LIMIT 1")
        if (regular != null) {
            add(measure("statement_page", 50.0, warmup = 5, n = 60) { CustomerDao.statement(r, regular, null, 50) })
        }
        add(measure("audit_page", 50.0, warmup = 5, n = 60) { AuditDao.recent(r, null, 50) })
        progress.update("pin_check")
        val pinRecord = PinHash.create("2468")
        add(measure("pin_check", 300.0, warmup = 1, n = 10) { check(PinHash.verify("2468", pinRecord)) })

        val today = Days.epochDay(System.currentTimeMillis(), tz)
        for ((id, days, budget, n) in listOf(
            Quad("report_day", 1L, 300.0, 30),
            Quad("report_month", 30L, 1000.0, 10),
            Quad("report_year", 365L, 3000.0, 3),
        )) {
            progress.update(id)
            // The whole report screen: totals, the period before, buckets, payments, cashiers,
            // categories and the best sellers (products from whole months + loose days, D-043).
            add(measure(id, budget, warmup = 1, n = n) { i ->
                val to = today - i % 7 + 1
                ReportService.build(r, Period(to - days, to), 20)
            })
        }
        // Calendar year: twelve whole months, no loose days.
        val yearStart = Days.fromYmd(Days.toYmd(today) / 10_000 * 10_000 - 10_000 + 101)
        val lastYear = Period(yearStart, Days.fromYmd(Days.toYmd(today) / 10_000 * 10_000 + 101))
        add(measure("report_calendar_year", 3000.0, warmup = 1, n = 3) { ReportService.build(r, lastYear, 20) })

        progress.update("stock reports")
        val month = Period(today - 29, today + 1)
        // The list and the count and value of all of them, in one reading (D-058).
        add(measure("slow_movers", 1000.0, warmup = 1, n = 5) { ReportDao.slowMovers(r, month, 100) })
        add(measure("stock_value", 500.0, warmup = 1, n = 5) { ReportDao.stockValue(r) })

        // The catalogue's "Popular" tab (D-049): the tab shows the ranking kept from last time at once;
        // the ranking itself (a month of sales) is made in the background (D-058).
        progress.update("popular_items")
        var ranking = emptyList<Long>()
        add(measure("popular_ranking", 1000.0, warmup = 1, n = 10) { ranking = ProductDao.popularIds(r, today - 29, today, 50) })
        add(measure("popular_items", 50.0, warmup = 1, n = 10) { ProductDao.listByIds(r, ranking) })

        progress.update("exports")
        val sink = CountingSink()
        val currency = CurrencySpec.MYR
        // A month of receipts (~20,000) as CSV, page by page as the export does.
        add(measure("export_receipts_month", 10_000.0, warmup = 0, n = 1) {
            val w = CsvWriter(sink)
            val fromMs = Days.startOfDay(today - 29, tz)
            val toMs = Days.startOfDay(today + 1, tz)
            var after: ReceiptRow? = null
            while (true) {
                val page = ReportDao.receipts(r, fromMs, toMs, after, 500)
                for (s in page) w.row(s.receiptNo, s.staff, s.customer, s.payments, MoneyFormat.plain(s.total, currency.decimals))
                if (page.size < 500) break
                after = page.last()
            }
        })
        // Every product (50,000 at FULL) as CSV.
        add(measure("export_products", 20_000.0, warmup = 0, n = 1) {
            val w = CsvWriter(sink)
            var after = 0L
            while (true) {
                val page = ProductDao.exportPage(r, after, 500)
                for (p in page) {
                    w.row(ProductCsv.format(ProductCsv.Row(p.name, p.price, p.barcodes, p.sku, p.category, p.unit, p.cost, p.tax), currency))
                }
                if (page.size < 500) break
                after = page.last().id
            }
        })
        // One import transaction: 200 CSV rows parsed, checked and created as new products.
        val header = ProductCsv.header(ProductCsv.COLUMNS.map { it.header })
        add(measure("import_chunk_200", 3000.0, warmup = 0, n = 3) { i ->
            db.writeBlocking(reserveIds = 200 * 4L) { tx ->
                val now = System.currentTimeMillis()
                for (k in 0 until 200) {
                    val code = "2999${i}9${k.toString().padStart(6, '0')}"
                    val fields = listOf("Perf import $i-$k", code, "PERF-$i-$k", "", "pcs", "3.20", "2.10", "", "piece", "yes", "", "", "yes")
                    val row = (ProductCsv.parse(fields, header, currency) as ProductCsv.Parsed.Ok).row
                    check(ProductDao.ownerOf(tx.db, code) == null && ProductDao.bySku(tx.db, row.sku ?: "") == null)
                    val id = tx.nextId()
                    ProductDao.create(tx, Product(id = id, name = row.name, sku = row.sku, price = row.price, cost = row.cost ?: 0L), listOf(Barcode(tx.nextId(), id, code)), now)
                }
            }
        })

        progress.update("sync import")
        // Another till's events, applied by the importer as a sync does (D-045): 200 sales (an
        // hour of a busy till) and 200 price edits, each set in one transaction. A real import
        // splits a segment into ~100 ms transactions, so these are worst cases for a waiting sale.
        val remote = if (db.deviceNo >= Ids.MAX_DEVICE_NO) 1 else db.deviceNo + 1
        val runBase = (System.currentTimeMillis() / 1000L) % 1_000_000_000L * 1024L // fresh ids on a reused perf database
        val recent = r.queryList("SELECT id FROM sale WHERE kind = 0 ORDER BY id DESC LIMIT 200") { it.getLong(0) }
        val saleSets = (0 until 3).map { i ->
            recent.mapIndexedNotNull { k, id -> SaleDao.exportRows(r, id)?.let { remoteSale(it, remote, runBase + (i * 1_000L + k) * 64L) } }
        }
        add(measure("sync_import_200_sales", 3000.0, warmup = 0, n = 3) { i ->
            db.writeBlocking(reserveIds = 0L) { tx ->
                val importer = Importer(tx.db)
                for (e in saleSets[i]) check(importer.apply(tx, e))
            }
        })
        val editSets = (0 until 3).map { i ->
            (0 until 200).map { k ->
                val pid = samples.popular[rnd.nextInt(samples.popular.size)]
                val hlc = db.hlc.now()
                SyncEvent(Entity.PRODUCT, EventOp.LWW, pid, hlc, mapOf("id" to pid, "hlc" to hlc, "dev" to remote.toLong(), "f" to mapOf("price" to 100L + i * 200L + k)))
            }
        }
        add(measure("sync_import_200_edits", 1500.0, warmup = 0, n = 3) { i ->
            db.writeBlocking(reserveIds = 0L) { tx ->
                val importer = Importer(tx.db)
                for (e in editSets[i]) importer.apply(tx, e)
            }
        })

        progress.update("query plans")
        val plans = QueryPlans.check(r)

        val counts = linkedMapOf(
            "products" to ProductDao.count(r),
            "sales" to SaleDao.count(r),
            "sale_lines" to r.long("SELECT COUNT(*) FROM sale_line"),
            "payments" to r.long("SELECT COUNT(*) FROM payment"),
            "shifts" to r.long("SELECT COUNT(*) FROM shift"),
            "customers" to r.long("SELECT COUNT(*) FROM customer"),
            "credit_entries" to r.long("SELECT COUNT(*) FROM credit_entry"),
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

    /** An exported sale as if till [dev] had made it: its own ids (from [seq], up to 63 child rows) and receipt number. */
    private fun remoteSale(rows: Triple<Map<String, Any?>, List<Map<String, Any?>>, List<Map<String, Any?>>>, dev: Int, seq: Long): SyncEvent {
        val saleId = Ids.make(dev, seq)
        var child = 0L
        fun childId() = Ids.make(dev, seq + (++child))
        val sale = LinkedHashMap(rows.first)
        sale["id"] = saleId
        sale["device_no"] = dev.toLong()
        sale["receipt_no"] = "PF-$seq"
        val lines = rows.second.map { l -> LinkedHashMap(l).also { it["id"] = childId(); it["sale_id"] = saleId } }
        val pays = rows.third.map { p -> LinkedHashMap(p).also { it["id"] = childId(); it["sale_id"] = saleId } }
        return SyncEvent(Entity.SALE, EventOp.INSERT, saleId, sale["hlc"] as Long, mapOf("sale" to sale, "lines" to lines, "pays" to pays))
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
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) schema v${Schema.VERSION}, " +
                    "${BuildConfig.SIGNING_KEY} key",
                abi = abi,
            )
        }
    }
}

/** Counts what an export writes without keeping it (perf runs measure the work, not storage). */
private class CountingSink : Appendable {
    var chars = 0L
    override fun append(csq: CharSequence?): Appendable = apply { chars += csq?.length ?: 4 }
    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = apply { chars += end - start }
    override fun append(c: Char): Appendable = apply { chars++ }
}
