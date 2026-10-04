package com.lekaspos.domain.products

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.app.AppGraph
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.csv.CsvInput
import com.lekaspos.core.csv.CsvReader
import com.lekaspos.core.csv.CsvWriter
import com.lekaspos.core.csv.ImportProgress
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
import com.lekaspos.data.db.Meta
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
 * written; nothing about the file is kept in memory except the barcodes and SKUs seen (duplicates).
 *
 * Each chunk's transaction also records how far the import got ([ImportProgress] in `meta`): when
 * Android ends the app half-way, importing the same file again continues after the rows already
 * done instead of creating the products without barcode, SKU or number (and their opening stock)
 * twice; the stopped import is audited at the next preview or import.
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
        /** An Excel (or other spreadsheet) file: it must be saved as CSV first. */
        val spreadsheet: Boolean = false,
        /** The template's example rows ("EXAMPLE – …"): never imported. */
        val examples: Int = 0,
        /** Rows naming a tax the store does not have: imported without it (existing products keep theirs). */
        val taxUnmatched: Int = 0,
        /** The first few such tax cells, as written. */
        val taxUnmatchedNames: List<String> = emptyList(),
        /** Rows with characters that could not be read (U+FFFD: broken bytes in a UTF-8 file). */
        val unreadable: Int = 0,
        val firstUnreadableLine: Int = 0,
        /** The file has more than [ProductCsv.MAX_ROWS] rows: the rest is neither read nor imported. */
        val tooManyRows: Boolean = false,
        /**
         * Rows of this same file an import did before the app was closed: the counts above are for
         * the rows after them, where the import continues.
         */
        val resumeFrom: Int = 0,
        /** Where and why the file could not be read ([malformed]), for the screen to say in its language. */
        val malformedLine: Int = 0,
        val malformedReason: com.lekaspos.core.csv.CsvReader.Malformed.Reason? = null,
    ) {
        val importable: Boolean get() = malformed == null && !spreadsheet && missing.isEmpty() && newProducts + updates > 0
    }

    data class Result(
        val created: Int,
        val updated: Int,
        val skipped: Int,
        val categoriesCreated: Int,
        val stockSet: Int,
        /** Rows imported before the app was closed, which this import continued after. */
        val resumedFrom: Int = 0,
    )

    sealed class State {
        object Idle : State()
        data class Running(val rows: Int) : State()
        data class Done(val result: Result) : State()
        /** [storageFull]: the phone ran out of room (said in words, not SQLite's English). */
        data class Failed(val error: String, val storageFull: Boolean = false) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** The file the running or last import was started for: its result is shown only for that file. */
    @Volatile
    var source: String? = null
        private set

    /** An import runs in this process: its progress record is not one left by a stopped import. */
    @Volatile
    private var importing = false

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

    /**
     * An empty file with the headers and two example rows. Their names start with
     * [ProductCsv.EXAMPLE_PREFIX]: owners type their products below them, and the import skips them.
     */
    fun template(out: Appendable) {
        val currency = graph.settings.store.value.currency
        val ex = ProductCsv.EXAMPLE_PREFIX
        out.append(CsvWriter.BOM)
        val w = CsvWriter(out)
        w.row(ProductCsv.COLUMNS.map { it.header })
        w.row(
            ProductCsv.format(
                ProductCsv.Row("${ex}Milo 1kg", 1_890L, listOf("9556001234567"), "MILO1KG", "Minuman", "pcs", 1_520L, "", SellMode.UNIT, true, 24_000L, 6_000L, true),
                currency,
            ),
        )
        w.row(
            ProductCsv.format(
                ProductCsv.Row("${ex}Bawang merah", 800L, emptyList(), null, "Sayur", "kg", 550L, "", SellMode.WEIGHT, true, 10_000L, 2_000L, true),
                currency,
            ),
        )
    }

    /** Reads the whole file and reports what an import would do; writes nothing but the audit of a stopped import. */
    suspend fun preview(open: () -> Reader): Preview = withContext(Dispatchers.IO) {
        graph.permissions.actor(Perm.MANAGE_PRODUCTS)
        val currency = graph.settings.store.value.currency
        val db = graph.db()
        val earlier = if (importing) null else logStopped()
        val ctx = db.read { r -> Context(TaxRateDao.list(r), categoryKeys(r)) }
        var rowCount = 0
        var created = 0
        var updates = 0
        var bad = 0
        var examples = 0
        var taxUnmatched = 0
        val taxNames = LinkedHashSet<String>()
        var unreadable = 0
        var firstUnreadable = 0
        var tooMany = false
        var resumeFrom = 0
        val issues = ArrayList<Issue>()
        val newCategories = LinkedHashSet<String>()
        val sample = ArrayList<Pair<ProductCsv.Row, Boolean>>()
        var header: ProductCsv.Header? = null
        fun newCategory(row: ProductCsv.Row) {
            row.category?.let { if (SearchText.key(it) !in ctx.categories && newCategories.size < 20) newCategories.add(it) }
        }
        fun taxNotFound(row: ProductCsv.Row, unmatched: Boolean) {
            if (!unmatched) return
            taxUnmatched++
            row.tax?.let { if (taxNames.size < 5) taxNames.add(it) }
        }
        fun result(malformed: String?) = Preview(
            rowCount, created, updates, bad, issues, header?.missing.orEmpty(), header?.unknown.orEmpty(), newCategories.toList(),
            sample, header?.has(Column.STOCK) == true, malformed, false, examples, taxUnmatched, taxNames.toList(), unreadable,
            firstUnreadable, tooMany, resumeFrom,
        )
        try {
            if (earlier != null && earlier.hash == hashOf(open)) resumeFrom = earlier.rows
            open().use { reader ->
                val csv = CsvReader(reader)
                val h = ProductCsv.header(csv.next() ?: emptyList())
                header = h
                if (h.missing.isNotEmpty()) return@use
                val rows = Rows(csv)
                skip(rows, resumeFrom, h, currency, ctx)
                while (true) {
                    coroutineContext.ensureActive()
                    val chunk = rows.take(CHUNK)
                    if (chunk.isEmpty()) break
                    val plans = db.read { r -> chunk.map { (fields, line) -> plan(r, fields, line, h, currency, ctx) } }
                    for ((i, plan) in plans.withIndex()) {
                        val (fields, line) = chunk[i]
                        if (plan !is Plan.Example && fields.any { it.indexOf(REPLACEMENT) >= 0 }) {
                            if (unreadable++ == 0) firstUnreadable = line
                        }
                        when (plan) {
                            is Plan.Bad -> {
                                bad++
                                if (issues.size < MAX_ISSUES) issues.addAll(plan.issues.take(MAX_ISSUES - issues.size))
                            }
                            is Plan.New -> {
                                created++
                                newCategory(plan.row)
                                taxNotFound(plan.row, plan.taxUnmatched)
                                if (sample.size < SAMPLE) sample.add(plan.row to false)
                            }
                            is Plan.Update -> {
                                updates++
                                newCategory(plan.row)
                                taxNotFound(plan.row, plan.taxUnmatched)
                                if (sample.size < SAMPLE) sample.add(plan.row to true)
                            }
                            Plan.Example -> examples++
                        }
                    }
                }
                rowCount = rows.read
                tooMany = rows.capped
            }
        } catch (e: CsvReader.Malformed) {
            return@withContext result(e.message).copy(malformedLine = e.line, malformedReason = e.reason)
        } catch (e: CsvInput.SpreadsheetFile) {
            return@withContext Preview(0, 0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, spreadsheet = true)
        }
        if (header == null) header = ProductCsv.header(emptyList())
        result(null)
    }

    /** May the current user change stock through an import (the file's stock column)? */
    fun mayImportStock(): Boolean = graph.permissions.allowed(Perm.MANAGE_STOCK)

    /**
     * Starts the import in the app scope; progress and the result arrive in [state]. With
     * [setStock], the stock column also sets existing products' stock (as a count). The stock
     * column is used only for someone who may manage stock: "Manage products" alone never
     * changes stock levels. With [resume], an import of this file that the app's end stopped
     * part-way continues after the rows it did; without, the file is imported from its first row.
     */
    fun startImport(open: () -> Reader, setStock: Boolean, source: String? = null, resume: Boolean = true) {
        if (_state.value is State.Running) return
        val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS)
        val stockAllowed = mayImportStock()
        this.source = source
        _state.value = State.Running(0)
        graph.appScope.launch(Dispatchers.IO) {
            _state.value = try {
                State.Done(import(open, setStock, actor.staffId, actor.approvedBy, stockAllowed = stockAllowed, resume = resume))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Any failure, an Error too (out of memory): left "Running", the screen said "Importing…"
                // and refused every new import until the app restarted (2026-10 review).
                Log.e("Product import failed", e)
                State.Failed(e.message ?: e.javaClass.simpleName, storageFull = com.lekaspos.util.Storage.isFull(e))
            }
            graph.catalogChanged() // a failed import may have saved some rows too
        }
    }

    fun acknowledge() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }

    /**
     * The import itself (also used directly by tests). [stockAllowed] is the caller's check of
     * "Receive, adjust and count stock" ([mayImportStock]): without it the stock column is ignored.
     * It fails closed — a caller that does not pass it imports no stock. [resume]: see [startImport].
     */
    suspend fun import(
        open: () -> Reader,
        setStock: Boolean,
        staffId: Long?,
        approvedBy: Long?,
        stockAllowed: Boolean = false,
        resume: Boolean = true,
    ): Result {
        val earlier = logStopped()
        importing = true
        try {
            return importFile(open, setStock, staffId, approvedBy, stockAllowed, if (resume) earlier else null)
        } finally {
            importing = false
        }
    }

    private suspend fun importFile(
        open: () -> Reader,
        setStock: Boolean,
        staffId: Long?,
        approvedBy: Long?,
        stockAllowed: Boolean,
        earlier: ImportProgress?,
    ): Result {
        val currency = graph.settings.store.value.currency
        val db = graph.db()
        val hash = hashOf(open)
        val from = if (earlier != null && earlier.hash == hash) earlier.rows else 0
        val ctx = db.read { r -> Context(TaxRateDao.list(r), categoryKeys(r)) }
        // Counted only once a chunk has committed: a failed chunk is rolled back as a whole.
        var created = 0
        var updated = 0
        var skipped = 0
        var stockSet = 0
        var categories = 0
        var finished = false
        var logged = false // the last chunk's transaction wrote the audit entry
        var progress: ImportProgress? = null // what the last committed chunk recorded
        try {
            open().use { reader ->
                val csv = CsvReader(reader)
                val h = ProductCsv.header(csv.next() ?: emptyList())
                require(h.missing.isEmpty()) { "missing columns: ${h.missing}" }
                val rows = Rows(csv)
                skip(rows, from, h, currency, ctx)
                var done = from
                while (rows.peek() != null) {
                    coroutineContext.ensureActive()
                    val chunk = rows.take(CHUNK)
                    val last = rows.peek() == null
                    val categoriesBefore = ctx.categories.size
                    val (n, p) = db.write(reserveIds = chunk.size * 8L + 16L) { tx ->
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
                                Plan.Example -> Unit
                            }
                        }
                        val rec = ImportProgress(
                            hash, done + chunk.size, created + c[0], updated + c[1], skipped + c[2], stockSet + c[3],
                            staffId, approvedBy, from,
                        )
                        // The last chunk, its audit entry and the end of the progress record commit together.
                        if (last) {
                            val detail = ImportProgress.detail(rec.created, rec.updated, rec.skipped, rec.stockSet, from, finished = true)
                            AuditDao.log(tx, AuditAction.PRODUCT_IMPORT, staffId, now, detail = detail, approvedBy = approvedBy)
                            Meta.put(tx.db, META_PROGRESS, null)
                        } else {
                            Meta.put(tx.db, META_PROGRESS, rec.format())
                        }
                        c to rec
                    }
                    created += n[0]
                    updated += n[1]
                    skipped += n[2]
                    stockSet += n[3]
                    categories += ctx.categories.size - categoriesBefore
                    done += chunk.size
                    progress = p
                    if (last) logged = true
                    _state.value = State.Running(done)
                }
            }
            finished = true
        } finally {
            // An import that stops part-way keeps the chunks it committed: those are logged too
            // (2026-10 review), without hiding the error that stopped it. Its progress record stays
            // (marked as logged), so the same file can continue later.
            if (!logged) {
                val log = finished || created + updated > 0
                val record = progress
                if (log || record != null) {
                    try {
                        withContext(NonCancellable) {
                            db.write(reserveIds = 1L) { tx ->
                                if (log) {
                                    val detail = ImportProgress.detail(created, updated, skipped, stockSet, from, finished)
                                    AuditDao.log(tx, AuditAction.PRODUCT_IMPORT, staffId, System.currentTimeMillis(), detail = detail, approvedBy = approvedBy)
                                }
                                Meta.put(tx.db, META_PROGRESS, if (finished) null else record?.copy(logged = true)?.format())
                            }
                        }
                    } catch (e: Exception) {
                        if (finished) throw e
                        Log.w("The stopped product import could not be logged", e)
                    }
                }
            }
        }
        return Result(created, updated, skipped, categories, stockSet, from)
    }

    /**
     * The progress record an import left when it stopped part-way (Android ended the app):
     * audited here, once, as "stopped early" — the import itself never got to its audit entry.
     * The record stays, so the same file can continue. Null when there is none.
     */
    private suspend fun logStopped(): ImportProgress? {
        val db = graph.db()
        val found = db.read { Meta.get(it, META_PROGRESS) }?.let(ImportProgress::parse) ?: return null
        if (found.logged) return found
        return db.write(reserveIds = 1L) { tx ->
            val p = Meta.get(tx.db, META_PROGRESS)?.let(ImportProgress::parse)
            if (p == null || p.logged || importing) return@write p
            if (p.created + p.updated > 0) {
                AuditDao.log(tx, AuditAction.PRODUCT_IMPORT, p.staffId, System.currentTimeMillis(), detail = p.detail(), approvedBy = p.approvedBy)
            }
            p.copy(logged = true).also { Meta.put(tx.db, META_PROGRESS, it.format()) }
        }
    }

    /** SHA-256 of the file's text: one more streaming read (the file is identified before its first chunk). */
    private fun hashOf(open: () -> Reader): String = open().use { ImportProgress.sha256(it) }

    // ------------------------------------------------------------------ rows of the file

    /**
     * The file's records after the header, with their line, at most [ProductCsv.MAX_ROWS] of
     * them ([capped]: there were more); [peek] looks one ahead (is this chunk the last?).
     */
    private class Rows(private val csv: CsvReader) {
        var read = 0
            private set
        var capped = false
            private set
        private var held: Pair<List<String>, Int>? = null
        private var holding = false
        private var ended = false

        fun peek(): Pair<List<String>, Int>? {
            if (!holding) {
                held = fetch()
                holding = true
            }
            return held
        }

        fun next(): Pair<List<String>, Int>? {
            val r = peek()
            if (r != null) {
                held = null
                holding = false
            }
            return r
        }

        fun take(n: Int): List<Pair<List<String>, Int>> {
            val out = ArrayList<Pair<List<String>, Int>>(n)
            while (out.size < n) out.add(next() ?: break)
            return out
        }

        private fun fetch(): Pair<List<String>, Int>? {
            if (ended) return null
            if (read >= ProductCsv.MAX_ROWS) {
                ended = true
                capped = csv.next() != null
                return null
            }
            val f = csv.next()
            if (f == null) {
                ended = true
                return null
            }
            read++
            return f to csv.recordLine
        }
    }

    /**
     * Passes over the [n] rows an earlier import of this file did, remembering their barcodes and
     * SKUs: a later row repeating one is refused as it would have been in one go.
     */
    private fun skip(rows: Rows, n: Int, h: ProductCsv.Header, currency: CurrencySpec, ctx: Context) {
        for (i in 0 until n) {
            val (fields, line) = rows.next() ?: return
            val parsed = ProductCsv.parse(fields, h, currency)
            if (parsed is ProductCsv.Parsed.Ok) {
                for (b in parsed.row.barcodes) ctx.seen.getOrPut(b) { line }
                parsed.row.sku?.let { ctx.skus.getOrPut(it.lowercase()) { line } }
            }
        }
    }

    // ------------------------------------------------------------------ one row

    /** Taxes and categories known so far (categories grow as the import creates them). */
    private class Context(val taxes: List<TaxRate>, val categories: MutableMap<String, Long>) {
        val rates: List<Pair<String, Int>> = taxes.map { it.name to it.rateBp }

        /** Barcode → first line using it in this file. */
        val seen = HashMap<String, Int>()

        /** SKU (any case) → first line using it in this file. */
        val skus = HashMap<String, Int>()
    }

    private sealed class Plan {
        /** [taxUnmatched]: the file names a tax the store does not have (imported without it). */
        class New(val row: ProductCsv.Row, val taxId: Long?, val taxUnmatched: Boolean) : Plan()
        class Update(val productId: Long, val row: ProductCsv.Row, val taxId: Long?, val taxUnmatched: Boolean) : Plan()
        class Bad(val issues: List<Issue>) : Plan()
        object Example : Plan()
    }

    private fun plan(db: SQLiteDatabase, fields: List<String>, line: Int, h: ProductCsv.Header, currency: CurrencySpec, ctx: Context): Plan {
        val row = when (val parsed = ProductCsv.parse(fields, h, currency)) {
            is ProductCsv.Parsed.Bad -> return Plan.Bad(parsed.problems.map { Issue(line, it.first, it.second) })
            ProductCsv.Parsed.Example -> return Plan.Example
            is ProductCsv.Parsed.Ok -> parsed.row
        }
        val issues = ArrayList<Issue>()
        var taxId: Long? = null
        var taxUnmatched = false
        row.tax?.let { tax ->
            when (val choice = ProductCsv.taxChoice(tax, ctx.rates)) {
                ProductCsv.TaxChoice.None -> Unit
                is ProductCsv.TaxChoice.Rate -> taxId = ctx.taxes[choice.index].id
                ProductCsv.TaxChoice.Unmatched -> taxUnmatched = true
            }
        }
        for (b in row.barcodes) {
            val first = ctx.seen.getOrPut(b) { line }
            if (first != line) issues.add(Issue(line, Problem.BARCODE_TWICE, Column.BARCODES))
        }
        // A second row with the same SKU updated the product the first row had just created: a file
        // whose SKU column held one shared code made ONE product with every row's barcodes, while
        // the preview promised them all as new (2026-10 review).
        row.sku?.let { sku ->
            if (ctx.skus.getOrPut(sku.lowercase()) { line } != line) issues.add(Issue(line, Problem.SKU_TWICE, Column.SKU))
        }
        // Every product using each barcode (also in its UPC/EAN form), the one a scan picks first: a
        // code on two products (two tills) updates the product the till sells (2026-10 review).
        // A product an older version stored without the leading 0, or as the file wrote the code
        // ("955-6001-234568", before 1.4.0 kept such codes as they were), is found too (else
        // imported twice).
        val owners = row.barcodes.map { code ->
            ProductDao.owners(db, Gtin.lookupVariants(code)).ifEmpty {
                ProductDao.owners(db, listOfNotNull(Gtin.withoutLeadingZero(code), row.asWritten[code]).distinct())
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
        return if (target == null) Plan.New(row, taxId, taxUnmatched) else Plan.Update(target, row, taxId, taxUnmatched)
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
            // A tax the store does not have leaves the product's own unchanged.
            taxRateId = if (r.tax == null || plan.taxUnmatched) before.taxRateId else plan.taxId,
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
        private const val REPLACEMENT = '�'

        /** `meta` key (LOCAL) of the running or stopped product import's [ImportProgress]. */
        const val META_PROGRESS = "import.products.progress"
    }
}
