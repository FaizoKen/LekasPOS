package com.lekaspos.domain.products

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.app.AppGraph
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.csv.CsvReader
import com.lekaspos.core.csv.CsvWriter
import com.lekaspos.core.csv.ProductCsv
import com.lekaspos.core.csv.ProductCsv.Column
import com.lekaspos.core.csv.ProductCsv.Problem
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.TaxRate
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.util.Log
import java.io.Reader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Products to and from CSV (D-042). Export streams every product page by page. Import reads
 * the file twice: a preview that validates every row and says what would happen, then the
 * import itself in transactions of [CHUNK] rows. Rows with problems are skipped, never half
 * written; nothing about the file is kept in memory except the barcodes seen (duplicates).
 */
class ProductCsvService(private val graph: AppGraph) {

    data class Issue(val line: Int, val problem: Problem, val column: Column?)

    data class Preview(
        val rows: Int,
        val newProducts: Int,
        val updates: Int,
        val badRows: Int,
        /** The first [MAX_ISSUES] problems (a row can have several). */
        val issues: List<Issue>,
        val missing: List<Column>,
        val unknown: List<String>,
        val newCategories: List<String>,
        /** A few valid rows and whether each updates an existing product. */
        val sample: List<Pair<ProductCsv.Row, Boolean>>,
        val hasStock: Boolean,
        /** The file itself could not be read (bad quotes …): line and message. */
        val malformed: String? = null,
    ) {
        val importable: Boolean get() = malformed == null && missing.isEmpty() && newProducts + updates > 0
    }

    data class Result(val created: Int, val updated: Int, val skipped: Int, val categoriesCreated: Int, val stockSet: Int)

    sealed class State {
        object Idle : State()
        data class Running(val rows: Int) : State()
        data class Done(val result: Result) : State()
        data class Failed(val error: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** The file the running or last import was started for: its result is shown only for that file. */
    @Volatile
    var source: String? = null
        private set

    /** The import running in the app scope (survives rotation and leaving the screen). */
    val state: StateFlow<State> = _state

    /** Writes every product (UTF-8 with BOM) to [out]; returns how many. */
    suspend fun export(out: Appendable): Int {
        graph.permissions.actor(Perm.MANAGE_PRODUCTS)
        val currency = graph.settings.store.value.currency
        out.append(CsvWriter.BOM)
        val w = CsvWriter(out)
        w.row(ProductCsv.COLUMNS.map { it.header })
        var after = 0L
        var n = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = graph.db().read { ProductDao.exportPage(it, after, PAGE) }
            for (p in page) {
                val row = ProductCsv.Row(
                    name = p.name, price = p.price, barcodes = p.barcodes, sku = p.sku, category = p.category, unit = p.unit,
                    cost = p.cost, tax = p.tax ?: "", sellMode = p.sellMode, trackStock = p.trackStock,
                    stock = if (p.trackStock) (p.stock ?: 0L) else null, lowStock = p.lowStock, active = p.active, id = p.id,
                )
                w.row(ProductCsv.format(row, currency))
                n++
            }
            if (page.size < PAGE) break
            after = page.last().id
        }
        return n
    }

    /** An empty file with the headers and two example rows. */
    fun template(out: Appendable) {
        val currency = graph.settings.store.value.currency
        out.append(CsvWriter.BOM)
        val w = CsvWriter(out)
        w.row(ProductCsv.COLUMNS.map { it.header })
        w.row(
            ProductCsv.format(
                ProductCsv.Row("Milo 1kg", 1_890L, listOf("9556001234567"), "MILO1KG", "Minuman", "pcs", 1_520L, "", SellMode.UNIT, true, 24_000L, 6_000L, true),
                currency,
            ),
        )
        w.row(
            ProductCsv.format(
                ProductCsv.Row("Bawang merah", 800L, emptyList(), null, "Sayur", "kg", 550L, "", SellMode.WEIGHT, true, 10_000L, 2_000L, true),
                currency,
            ),
        )
    }

    /** Reads the whole file and reports what an import would do; writes nothing. */
    suspend fun preview(open: () -> Reader): Preview = withContext(Dispatchers.IO) {
        graph.permissions.actor(Perm.MANAGE_PRODUCTS)
        val currency = graph.settings.store.value.currency
        val db = graph.db()
        val ctx = db.read { r -> Context(TaxRateDao.list(r), categoryKeys(r)) }
        var rows = 0
        var created = 0
        var updates = 0
        var bad = 0
        val issues = ArrayList<Issue>()
        val newCategories = LinkedHashSet<String>()
        val sample = ArrayList<Pair<ProductCsv.Row, Boolean>>()
        var header: ProductCsv.Header? = null
        try {
            open().use { reader ->
                val csv = CsvReader(reader)
                val h = ProductCsv.header(csv.next() ?: emptyList())
                header = h
                if (h.missing.isNotEmpty()) return@use
                var done = false
                while (!done) {
                    coroutineContext.ensureActive()
                    val chunk = ArrayList<Pair<List<String>, Int>>(CHUNK)
                    while (chunk.size < CHUNK) {
                        val f = csv.next()
                        if (f == null) {
                            done = true
                            break
                        }
                        chunk.add(f to csv.recordLine)
                    }
                    rows += chunk.size
                    val plans = db.read { r -> chunk.map { (fields, line) -> plan(r, fields, line, h, currency, ctx) } }
                    for (plan in plans) when (plan) {
                        is Plan.Bad -> {
                            bad++
                            if (issues.size < MAX_ISSUES) issues.addAll(plan.issues.take(MAX_ISSUES - issues.size))
                        }
                        is Plan.New -> {
                            created++
                            plan.row.category?.let { if (SearchText.key(it) !in ctx.categories && newCategories.size < 20) newCategories.add(it) }
                            if (sample.size < SAMPLE) sample.add(plan.row to false)
                        }
                        is Plan.Update -> {
                            updates++
                            plan.row.category?.let { if (SearchText.key(it) !in ctx.categories && newCategories.size < 20) newCategories.add(it) }
                            if (sample.size < SAMPLE) sample.add(plan.row to true)
                        }
                    }
                }
            }
        } catch (e: CsvReader.Malformed) {
            return@withContext Preview(rows, created, updates, bad, issues, emptyList(), emptyList(), emptyList(), sample, false, e.message)
        }
        val h = header ?: ProductCsv.header(emptyList())
        Preview(rows, created, updates, bad, issues, h.missing, h.unknown, newCategories.toList(), sample, h.has(Column.STOCK))
    }

    /** May the current user change stock through an import (the file's stock column)? */
    fun mayImportStock(): Boolean = graph.permissions.allowed(Perm.MANAGE_STOCK)

    /**
     * Starts the import in the app scope; progress and the result arrive in [state]. With
     * [setStock], the stock column also sets existing products' stock (as a count). The stock
     * column is used only for someone who may manage stock: "Manage products" alone never
     * changes stock levels.
     */
    fun startImport(open: () -> Reader, setStock: Boolean, source: String? = null) {
        if (_state.value is State.Running) return
        val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS)
        val stockAllowed = mayImportStock()
        this.source = source
        _state.value = State.Running(0)
        graph.appScope.launch(Dispatchers.IO) {
            _state.value = try {
                State.Done(import(open, setStock, actor.staffId, actor.approvedBy, stockAllowed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Product import failed", e)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun acknowledge() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }

    /** The import itself (also used directly by tests). Without [stockAllowed] the stock column is ignored. */
    suspend fun import(open: () -> Reader, setStock: Boolean, staffId: Long?, approvedBy: Long?, stockAllowed: Boolean = true): Result {
        val currency = graph.settings.store.value.currency
        val db = graph.db()
        val ctx = db.read { r -> Context(TaxRateDao.list(r), categoryKeys(r)) }
        // Counted only once a chunk has committed: a failed chunk is rolled back as a whole.
        var created = 0
        var updated = 0
        var skipped = 0
        var stockSet = 0
        var categories = 0
        var finished = false
        try {
            open().use { reader ->
                val csv = CsvReader(reader)
                val h = ProductCsv.header(csv.next() ?: emptyList())
                require(h.missing.isEmpty()) { "missing columns: ${h.missing}" }
                var done = false
                var rows = 0
                while (!done) {
                    coroutineContext.ensureActive()
                    val chunk = ArrayList<Pair<List<String>, Int>>(CHUNK)
                    while (chunk.size < CHUNK) {
                        val f = csv.next()
                        if (f == null) {
                            done = true
                            break
                        }
                        chunk.add(f to csv.recordLine)
                    }
                    if (chunk.isEmpty()) break
                    val categoriesBefore = ctx.categories.size
                    val n = db.write(reserveIds = chunk.size * 8L + 16L) { tx ->
                        val now = System.currentTimeMillis()
                        val c = IntArray(4) // created, updated, skipped, stock set
                        for ((fields, line) in chunk) {
                            when (val plan = plan(tx.db, fields, line, h, currency, ctx)) {
                                is Plan.Bad -> c[2]++
                                is Plan.New -> {
                                    if (create(tx, plan, ctx, staffId, now, stockAllowed)) c[3]++
                                    c[0]++
                                }
                                is Plan.Update -> {
                                    if (update(tx, plan, ctx, setStock && stockAllowed, staffId, now)) c[3]++
                                    c[1]++
                                }
                            }
                        }
                        c
                    }
                    created += n[0]
                    updated += n[1]
                    skipped += n[2]
                    stockSet += n[3]
                    categories += ctx.categories.size - categoriesBefore
                    rows += chunk.size
                    _state.value = State.Running(rows)
                }
            }
            finished = true
        } finally {
            // An import that stops part-way keeps the chunks it committed: those are logged too
            // (2026-10 review), without hiding the error that stopped it.
            if (finished || created + updated > 0) {
                val detail = "created $created, updated $updated, skipped $skipped, stock set $stockSet" +
                    if (finished) "" else ", stopped early"
                try {
                    withContext(NonCancellable) {
                        db.write(reserveIds = 1L) { tx ->
                            AuditDao.log(
                                tx, AuditAction.PRODUCT_IMPORT, staffId, System.currentTimeMillis(),
                                detail = detail, approvedBy = approvedBy,
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (finished) throw e
                    Log.w("The stopped product import could not be logged", e)
                }
            }
        }
        return Result(created, updated, skipped, categories, stockSet)
    }

    // ------------------------------------------------------------------ one row

    /** Taxes and categories known so far (categories grow as the import creates them). */
    private class Context(val taxes: List<TaxRate>, val categories: MutableMap<String, Long>) {
        /** Barcode → first line using it in this file. */
        val seen = HashMap<String, Int>()
    }

    private sealed class Plan {
        class New(val row: ProductCsv.Row, val taxId: Long?) : Plan()
        class Update(val productId: Long, val row: ProductCsv.Row, val taxId: Long?) : Plan()
        class Bad(val issues: List<Issue>) : Plan()
    }

    private fun plan(db: SQLiteDatabase, fields: List<String>, line: Int, h: ProductCsv.Header, currency: CurrencySpec, ctx: Context): Plan {
        val parsed = ProductCsv.parse(fields, h, currency)
        if (parsed is ProductCsv.Parsed.Bad) return Plan.Bad(parsed.problems.map { Issue(line, it.first, it.second) })
        val row = (parsed as ProductCsv.Parsed.Ok).row
        val issues = ArrayList<Issue>()
        var taxId: Long? = null
        val tax = row.tax
        if (!tax.isNullOrEmpty() && !isNoTax(tax)) {
            taxId = findTax(ctx.taxes, tax)
            if (taxId == null) issues.add(Issue(line, Problem.TAX_UNKNOWN, Column.TAX))
        }
        for (b in row.barcodes) {
            val first = ctx.seen.getOrPut(b) { line }
            if (first != line) issues.add(Issue(line, Problem.BARCODE_TWICE, Column.BARCODES))
        }
        // Every product using each barcode (also in its UPC/EAN form), the one a scan picks first: a
        // code on two products (two tills) updates the product the till sells (2026-10 review).
        // A product an older version stored without the leading 0 is found too (else imported twice).
        val owners = row.barcodes.map { code ->
            ProductDao.owners(db, Gtin.lookupVariants(code)).ifEmpty {
                Gtin.withoutLeadingZero(code)?.let { ProductDao.owners(db, listOf(it)) }.orEmpty()
            }
        }
        val picks = owners.mapNotNull { it.firstOrNull() }.toSet()
        val bySku = row.sku?.let { ProductDao.bySku(db, it) }
        // The file's own product number first (a file exported from this store); another store's
        // numbers match nothing here, and the row is matched by barcode or SKU as before.
        val byId = row.id?.takeIf { ProductDao.isLive(db, it) }
        if (byId == null && picks.size > 1) issues.add(Issue(line, Problem.BARCODES_SPLIT, Column.BARCODES))
        val target = byId ?: picks.singleOrNull() ?: bySku
        // With its number, a code is taken only when the product does not have it itself.
        val taken = if (byId != null) {
            owners.any { it.isNotEmpty() && byId !in it }
        } else {
            picks.size == 1 && bySku != null && bySku != picks.first()
        }
        if (taken) issues.add(Issue(line, Problem.BARCODE_TAKEN, Column.BARCODES))
        if (issues.isNotEmpty()) return Plan.Bad(issues.distinct())
        return if (target == null) Plan.New(row, taxId) else Plan.Update(target, row, taxId)
    }

    /** Returns true when it posted opening stock. */
    private fun create(tx: Db.Tx, plan: Plan.New, ctx: Context, staffId: Long?, now: Long, stockAllowed: Boolean): Boolean {
        val r = plan.row
        val id = tx.nextId()
        val p = Product(
            id = id, name = r.name, sku = r.sku, categoryId = category(tx, r.category, ctx, now), unit = r.unit ?: "pcs",
            sellMode = r.sellMode ?: SellMode.UNIT, price = r.price, cost = r.cost ?: 0L, taxRateId = plan.taxId,
            trackStock = r.trackStock ?: true, lowStock = r.lowStock ?: 0L, active = r.active ?: true,
        )
        ProductDao.create(tx, p, r.barcodes.map { Barcode(tx.nextId(), id, it) }, now)
        val stock = r.stock
        if (stockAllowed && stock != null && stock != 0L && p.trackStock) {
            StockDao.insertMovement(tx, id, MovementKind.OPENING, stock, p.cost, null, REASON, staffId, now)
            return true
        }
        return false
    }

    /** Changes only what the file says; returns true when it set the stock. */
    private fun update(tx: Db.Tx, plan: Plan.Update, ctx: Context, setStock: Boolean, staffId: Long?, now: Long): Boolean {
        val r = plan.row
        val before = ProductDao.get(tx.db, plan.productId) ?: return false
        val after = before.copy(
            name = r.name,
            price = r.price,
            sku = r.sku ?: before.sku,
            categoryId = if (r.category != null) category(tx, r.category, ctx, now) else before.categoryId,
            unit = r.unit ?: before.unit,
            sellMode = r.sellMode ?: before.sellMode,
            cost = r.cost ?: before.cost,
            taxRateId = if (r.tax == null) before.taxRateId else plan.taxId,
            trackStock = r.trackStock ?: before.trackStock,
            lowStock = r.lowStock ?: before.lowStock,
            active = r.active ?: before.active,
        )
        ProductDao.update(tx, before, after, now)
        val have = ProductDao.barcodes(tx.db, before.id).map { it.code }.toSet()
        for (code in r.barcodes) {
            // "036000291452" is the code the product has as "0036000291452": not a second barcode.
            if (Gtin.lookupVariants(code).any { it in have }) continue
            ProductDao.addBarcode(tx, Barcode(tx.nextId(), before.id, code), now)
        }
        val stock = r.stock
        if (setStock && stock != null && after.trackStock && stock != StockDao.level(tx.db, before.id)) {
            StockDao.insertCount(tx, before.id, stock, null, staffId, REASON, now)
            return true
        }
        return false
    }

    private fun category(tx: Db.Tx, name: String?, ctx: Context, now: Long): Long? {
        if (name.isNullOrBlank()) return null
        val key = SearchText.key(name)
        return ctx.categories[key] ?: CategoryDao.insert(tx, name.trim(), 0, 0, now).also { ctx.categories[key] = it }
    }

    private fun categoryKeys(r: SQLiteDatabase): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        for (c in CategoryDao.list(r)) {
            val key = SearchText.key(c.name)
            if (key !in out) out[key] = c.id
        }
        return out
    }

    companion object {
        const val CHUNK = 200
        const val MAX_ISSUES = 200
        private const val PAGE = 500
        private const val SAMPLE = 10
        private const val REASON = "CSV import"

        fun isNoTax(text: String): Boolean = SearchText.key(text) in setOf("none", "no", "tiada", "tidak", "0", "0 %", "-") ||
            ProductCsv.percentBp(text) == 0

        /** A tax rate by name, else by percentage. */
        fun findTax(taxes: List<TaxRate>, text: String): Long? {
            val key = SearchText.key(text)
            taxes.firstOrNull { SearchText.key(it.name) == key }?.let { return it.id }
            val bp = ProductCsv.percentBp(text) ?: return null
            return taxes.firstOrNull { it.rateBp == bp }?.id
        }
    }
}
