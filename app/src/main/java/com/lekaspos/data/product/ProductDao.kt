package com.lekaspos.data.product

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.report.Period
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.report.SummaryRange
import com.lekaspos.data.sync.LwwWriter

data class Product(
    val id: Long,
    val name: String,
    val sku: String? = null,
    val categoryId: Long? = null,
    val unit: String = "pcs",
    val sellMode: Int = 0,
    val price: Long,
    val cost: Long = 0L,
    val taxRateId: Long? = null,
    val trackStock: Boolean = true,
    val lowStock: Long = 0L,
    val active: Boolean = true,
)

data class Barcode(
    val id: Long,
    val productId: Long,
    val code: String,
    val kind: Int = BarcodeKind.BARCODE,
    val packQty: Long = 1000L,
    val packPrice: Long? = null,
)

/** Everything the selling screen needs to put a product on a bill. */
data class SellableProduct(
    val id: Long,
    val name: String,
    val unit: String,
    val sellMode: Int,
    val price: Long,
    val cost: Long,
    val categoryId: Long?,
    val taxRateId: Long?,
    val taxBp: Int,
    val trackStock: Boolean,
    val active: Boolean,
)

/** A barcode scan resolved to a product (pack barcodes carry their own qty/price). */
data class ScanHit(
    val product: SellableProduct,
    val code: String,
    val kind: Int,
    val packQty: Long,
    val packPrice: Long?,
)

data class ProductListItem(
    val id: Long,
    val name: String,
    val nameKey: String,
    val price: Long,
    val unit: String,
    val sellMode: Int,
    val stockQty: Long?,
    val active: Boolean = true,
    val categoryId: Long? = null,
    /** The tile's own colour ([com.lekaspos.core.model.TileColor], 0 = none) and picture (D-066). */
    val color: Int = 0,
    val imageId: Long? = null,
)

/** SQL for products, barcodes and product search. See references/database.md §8. */
object ProductDao {

    // ------------------------------------------------------------------ writes

    private const val INSERT_PRODUCT =
        "INSERT INTO product(id, name, name_key, sku, category_id, unit, sell_mode, price, cost, " +
            "tax_rate_id, track_stock, low_stock, active, deleted, created_at, updated_at, ver_hlc, " +
            "ver_dev, fver) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,NULL)"

    private const val INSERT_BARCODE =
        "INSERT INTO product_barcode(id, product_id, code, kind, pack_qty, pack_price, deleted, " +
            "created_at, updated_at, ver_hlc, ver_dev, fver) VALUES(?,?,?,?,?,?,0,?,?,?,?,NULL)"

    private const val DELETE_FTS = "DELETE FROM product_fts WHERE docid = ?"
    private const val INSERT_FTS = "INSERT INTO product_fts(docid, body) VALUES(?, ?)"

    /**
     * Bulk insert of a product with its barcodes at [hlc], **without** an outbox event: for
     * generated perf data and DAO tests only. The app creates products with [create].
     */
    fun insert(tx: Db.Tx, p: Product, barcodes: List<Barcode>, now: Long, hlc: Long) {
        tx.insert(
            INSERT_PRODUCT, p.id, p.name, SearchText.key(p.name), p.sku, p.categoryId, p.unit, p.sellMode,
            p.price, p.cost, p.taxRateId, p.trackStock, p.lowStock, p.active, now, now, hlc, tx.deviceNo,
        )
        for (b in barcodes) {
            require(b.productId == p.id) { "barcode for another product" }
            tx.insert(INSERT_BARCODE, b.id, b.productId, b.code, b.kind, b.packQty, b.packPrice, now, now, hlc, tx.deviceNo)
        }
        writeFts(tx, p.id, ftsBody(p.name, p.sku))
    }

    /** Rebuilds the search row of one product from its current name and SKU. */
    fun reindex(tx: Db.Tx, productId: Long) {
        val row = tx.db.queryOne(
            "SELECT name, sku, deleted FROM product WHERE id = ?", args(productId),
        ) { Triple(it.getString(0), it.stringOrNull(1), it.bool(2)) }
        if (row == null || row.third) {
            tx.exec(DELETE_FTS, productId)
            return
        }
        writeFts(tx, productId, ftsBody(row.first, row.second))
    }

    /**
     * FTS text: normalized name + SKU. Barcodes are not indexed here (unique digit tokens would
     * bloat the index); digit input is matched against the barcode index instead.
     */
    fun ftsBody(name: String, sku: String?): String {
        val sb = StringBuilder(SearchText.normalize(name))
        if (!sku.isNullOrBlank()) sb.append(' ').append(SearchText.normalize(sku))
        return sb.toString()
    }

    private fun writeFts(tx: Db.Tx, productId: Long, body: String) {
        tx.exec(DELETE_FTS, productId)
        tx.insert(INSERT_FTS, productId, body)
    }

    // ------------------------------------------------------------------ reads

    private const val SELLABLE_COLUMNS =
        "p.id, p.name, p.unit, p.sell_mode, p.price, p.cost, p.category_id, p.tax_rate_id, " +
            "COALESCE(t.rate_bp, 0), p.track_stock, p.active"

    /**
     * Which barcode row wins when several live products share a code: the one created last.
     * `created_at` travels with the row's creation, so every till picks the same product;
     * `updated_at` is the local time of the last change *or import* and differed per till
     * (2026-10 review). The id breaks ties the same way everywhere.
     */
    private const val CODE_WINNER = "b.created_at DESC, b.id DESC"

    private const val FIND_BY_CODE_1 =
        "SELECT $SELLABLE_COLUMNS, b.code, b.kind, b.pack_qty, b.pack_price " +
            "FROM product_barcode b JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 " +
            "WHERE b.code = ? AND b.deleted = 0 AND b.kind = ? AND p.deleted = 0 " +
            "ORDER BY $CODE_WINNER LIMIT 1"

    private const val FIND_BY_CODE_2 =
        "SELECT $SELLABLE_COLUMNS, b.code, b.kind, b.pack_qty, b.pack_price " +
            "FROM product_barcode b JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 " +
            "WHERE b.code IN (?, ?) AND b.deleted = 0 AND b.kind = ? AND p.deleted = 0 " +
            "ORDER BY b.code = ? DESC, $CODE_WINNER LIMIT 1"

    private const val FIND_BY_CODE_3 =
        "SELECT $SELLABLE_COLUMNS, b.code, b.kind, b.pack_qty, b.pack_price " +
            "FROM product_barcode b JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 " +
            "WHERE b.code IN (?, ?, ?) AND b.deleted = 0 AND b.kind = ? AND p.deleted = 0 " +
            "ORDER BY b.code = ? DESC, $CODE_WINNER LIMIT 1"

    /**
     * Resolves a scanned code. [codes] are the lookup variants (see Gtin.lookupVariants), at
     * most three (a UPC-E's 12- and 13-digit forms: a third was dropped, and those small packs were
     * "not found", 2026-10 review). The code exactly as scanned comes first (a newer product with only
     * its padded form took over the scans of "1234565", 2026-10 review); with duplicates, the barcode
     * created last wins, on every till ([CODE_WINNER]).
     */
    fun findByCode(db: SQLiteDatabase, codes: List<String>, kind: Int = BarcodeKind.BARCODE): ScanHit? =
        when (codes.size) {
            0 -> null
            1 -> db.queryOne(FIND_BY_CODE_1, args(codes[0], kind), ::scanHit)
            2 -> db.queryOne(FIND_BY_CODE_2, args(codes[0], codes[1], kind, codes[0]), ::scanHit)
            else -> db.queryOne(FIND_BY_CODE_3, args(codes[0], codes[1], codes[2], kind, codes[0]), ::scanHit)
        }

    private fun sellable(c: Cursor) = SellableProduct(
        id = c.getLong(0),
        name = c.getString(1),
        unit = c.getString(2),
        sellMode = c.getInt(3),
        price = c.getLong(4),
        cost = c.getLong(5),
        categoryId = c.longOrNull(6),
        taxRateId = c.longOrNull(7),
        taxBp = c.getInt(8),
        trackStock = c.bool(9),
        active = c.bool(10),
    )

    private fun scanHit(c: Cursor) = ScanHit(
        product = sellable(c),
        code = c.getString(11),
        kind = c.getInt(12),
        packQty = c.getLong(13),
        packPrice = c.longOrNull(14),
    )

    /** A product that may go on a bill; null once deleted (a tile shown before another till deleted it). */
    fun sellableById(db: SQLiteDatabase, id: Long): SellableProduct? = db.queryOne(
        "SELECT $SELLABLE_COLUMNS FROM product p " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 WHERE p.id = ? AND p.deleted = 0",
        args(id), ::sellable,
    )

    /** A tile's colour and picture (D-066): a primary-key lookup per product shown. */
    private const val LOOK_JOIN = " LEFT JOIN product_look k ON k.id = p.id"

    private const val LIST_COLUMNS =
        "p.id, p.name, p.name_key, p.price, p.unit, p.sell_mode, s.qty, p.active, p.category_id, k.color, k.image_id"

    /** Upper bound of FTS candidates joined and sorted per search: keeps common prefixes cheap. */
    const val FTS_CANDIDATES = 2000

    // The capped FTS subquery is the outer loop (CROSS JOIN pins the order), so the cost is
    // bounded by FTS_CANDIDATES even when a short prefix matches most of the catalogue. Only the
    // id and sort key of the candidates are sorted; the columns and the stock level are read for
    // the page alone (all of them for 2,000 candidates made common two-word searches slow on a
    // store's tablet, D-058).
    private const val FTS_PAGE =
        "SELECT p.id AS id, p.name_key AS k FROM (SELECT docid FROM product_fts WHERE product_fts MATCH ? LIMIT $FTS_CANDIDATES) f " +
            "CROSS JOIN product p ON p.id = f.docid WHERE p.deleted = 0"
    private const val FTS_ORDER = " ORDER BY p.name_key, p.id LIMIT ?"

    /** A one-letter word of the search, as a filter on the candidates: a word of the name starts with it. */
    private const val LETTER = " AND (' ' || p.name_key) LIKE ?"
    private const val FTS_COLUMNS =
        ") t CROSS JOIN product p ON p.id = t.id LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN ORDER BY t.k, t.id"
    private const val SEARCH_FTS =
        "SELECT $LIST_COLUMNS FROM ($FTS_PAGE AND p.active = 1 ORDER BY p.name_key, p.id LIMIT ?$FTS_COLUMNS"

    private const val SEARCH_PREFIX =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.deleted = 0 AND p.active = 1 AND p.name_key >= ? AND p.name_key < ? " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    private const val SEARCH_BARCODE_PREFIX =
        "SELECT $LIST_COLUMNS FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE b.code >= ? AND b.code < ? AND b.deleted = 0 AND p.deleted = 0 AND p.active = 1 " +
            "ORDER BY b.code LIMIT ?"

    // The same three searches with switched-off products included (product list and pickers,
    // where the owner looks for a product to switch it back on). Same plans as above.
    private const val SEARCH_FTS_ALL =
        "SELECT $LIST_COLUMNS FROM ($FTS_PAGE ORDER BY p.name_key, p.id LIMIT ?$FTS_COLUMNS"

    private const val SEARCH_PREFIX_ALL =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.deleted = 0 AND p.name_key >= ? AND p.name_key < ? " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    private const val SEARCH_BARCODE_PREFIX_ALL =
        "SELECT $LIST_COLUMNS FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE b.code >= ? AND b.code < ? AND b.deleted = 0 AND p.deleted = 0 " +
            "ORDER BY b.code LIMIT ?"

    /**
     * Product search for the selling screen:
     *  - digits only (2+) → barcode/PLU prefix range on the barcode index, then name/SKU matches;
     *  - one Latin letter or digit → name-prefix index range;
     *  - otherwise → FTS4 prefix match on name and SKU, all tokens required.
     * Every path is an index range or a capped FTS lookup, never a table scan. Switched-off
     * products are left out unless [includeInactive] (product management and pickers).
     */
    fun search(
        db: SQLiteDatabase,
        input: String,
        limit: Int = 50,
        includeInactive: Boolean = false,
    ): List<ProductListItem> {
        val tokens = SearchText.tokens(input)
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashMap<Long, ProductListItem>()
        val trimmed = input.trim()
        if (trimmed.length >= 2 && trimmed.all { it in '0'..'9' }) {
            val upper = prefixUpper(trimmed)
            val sql = if (includeInactive) SEARCH_BARCODE_PREFIX_ALL else SEARCH_BARCODE_PREFIX
            for (item in db.queryList(sql, args(trimmed, upper, limit), ::listItem)) {
                if (!out.containsKey(item.id)) out[item.id] = item // (Map.putIfAbsent is API 24+)
            }
            if (out.size >= limit) return out.values.toList()
        }
        val more = if (tokens.size == 1 && tokens[0].length == 1 && !SearchText.isCjkChar(tokens[0])) {
            val p = tokens[0]
            val sql = if (includeInactive) SEARCH_PREFIX_ALL else SEARCH_PREFIX
            db.queryList(sql, args(p, prefixUpper(p), limit), ::listItem)
        } else {
            // A one-letter word ("Julie's" → "julie s", "F&N" → "f n") merges the index entries of
            // every word with that letter (the index keeps 2- and 3-letter prefixes): with longer words
            // to search by, it filters their candidates instead — 2.5× faster, same results (D-058).
            val words = tokens.take(SearchText.MAX_QUERY_TOKENS)
            val longer = words.filter { it.length > 1 || SearchText.isCjkChar(it) }
            val letters = words.filter { it.length == 1 && !SearchText.isCjkChar(it) }
            if (longer.isNotEmpty() && letters.isNotEmpty()) {
                val match = SearchText.ftsQuery(longer.joinToString(" ")) ?: return out.values.toList()
                val sql = "SELECT $LIST_COLUMNS FROM ($FTS_PAGE" + (if (includeInactive) "" else " AND p.active = 1") +
                    LETTER.repeat(letters.size) + FTS_ORDER + FTS_COLUMNS
                db.queryList(sql, args(match, *letters.map { "% $it%" }.toTypedArray(), limit), ::listItem)
            } else {
                val match = SearchText.ftsQuery(input) ?: return out.values.toList()
                db.queryList(if (includeInactive) SEARCH_FTS_ALL else SEARCH_FTS, args(match, limit), ::listItem)
            }
        }
        for (item in more) {
            if (out.size >= limit) break
            if (!out.containsKey(item.id)) out[item.id] = item
        }
        return out.values.toList()
    }

    /** Smallest string greater than every string that starts with [prefix] (BMP text). */
    private fun prefixUpper(prefix: String): String = prefix.substring(0, prefix.length - 1) + (prefix.last() + 1)

    private const val BY_CATEGORY =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.category_id = ? AND p.deleted = 0 AND p.active = 1 " +
            "AND p.name_key >= ? AND (p.name_key > ? OR p.id > ?) " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    // The same page with switched-off products (a stock count counts what is on the shelf).
    private const val BY_CATEGORY_ALL =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.category_id = ? AND p.deleted = 0 " +
            "AND p.name_key >= ? AND (p.name_key > ? OR p.id > ?) " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    /** One page of a category, keyset-paginated by (name_key, id). First page: after = null. */
    fun byCategory(
        db: SQLiteDatabase,
        categoryId: Long,
        after: ProductListItem?,
        limit: Int = 60,
        includeInactive: Boolean = false,
    ): List<ProductListItem> {
        val key = after?.nameKey ?: ""
        val id = after?.id ?: -1L
        val sql = if (includeInactive) BY_CATEGORY_ALL else BY_CATEGORY
        return db.queryList(sql, args(categoryId, key, key, id, limit), ::listItem)
    }

    private fun listItem(c: Cursor) = ProductListItem(
        id = c.getLong(0),
        name = c.getString(1),
        nameKey = c.getString(2),
        price = c.getLong(3),
        unit = c.getString(4),
        sellMode = c.getInt(5),
        stockQty = c.longOrNull(6),
        active = c.bool(7),
        categoryId = c.longOrNull(8),
        color = if (c.isNull(9)) 0 else c.getInt(9),
        imageId = c.longOrNull(10),
    )

    /** Is [id] a product of this store that has not been deleted? */
    fun isLive(db: SQLiteDatabase, id: Long): Boolean =
        db.long("SELECT COUNT(*) FROM product WHERE id = ? AND deleted = 0", id) > 0L

    fun get(db: SQLiteDatabase, id: Long): Product? = db.queryOne(
        "SELECT id, name, sku, category_id, unit, sell_mode, price, cost, tax_rate_id, track_stock, " +
            "low_stock, active FROM product WHERE id = ?",
        args(id),
    ) { c ->
        Product(
            id = c.getLong(0),
            name = c.getString(1),
            sku = c.stringOrNull(2),
            categoryId = c.longOrNull(3),
            unit = c.getString(4),
            sellMode = c.getInt(5),
            price = c.getLong(6),
            cost = c.getLong(7),
            taxRateId = c.longOrNull(8),
            trackStock = c.bool(9),
            lowStock = c.getLong(10),
            active = c.bool(11),
        )
    }

    fun count(db: SQLiteDatabase): Long = db.long("SELECT COUNT(*) FROM product WHERE deleted = 0")

    /** Any product at all (O(1), unlike [count]). */
    fun any(db: SQLiteDatabase): Boolean = db.long("SELECT EXISTS(SELECT 1 FROM product WHERE deleted = 0)") == 1L

    // ------------------------------------------------------------------ editing (LWW, synced)

    /** Creates a product and its barcodes as LWW rows (with outbox events when sync is on). */
    fun create(tx: Db.Tx, p: Product, barcodes: List<Barcode>, now: Long) {
        LwwWriter.insert(tx, "product", Entity.PRODUCT, p.id, fields(p), now)
        for (b in barcodes) {
            require(b.productId == p.id) { "barcode for another product" }
            addBarcode(tx, b, now)
        }
        writeFts(tx, p.id, ftsBody(p.name, p.sku))
    }

    /** Column values of an editable product, as stored. */
    fun fields(p: Product): LinkedHashMap<String, Any?> = linkedMapOf(
        "name" to p.name,
        "name_key" to SearchText.key(p.name),
        "sku" to p.sku,
        "category_id" to p.categoryId,
        "unit" to p.unit,
        "sell_mode" to p.sellMode,
        "price" to p.price,
        "cost" to p.cost,
        "tax_rate_id" to p.taxRateId,
        "track_stock" to p.trackStock,
        "low_stock" to p.lowStock,
        "active" to p.active,
    )

    /** Writes the fields that differ between [before] and [after]; returns the changed columns. */
    fun update(tx: Db.Tx, before: Product, after: Product, now: Long): Set<String> =
        update(tx, before, after, before, now)

    /**
     * Writes an edit made on a screen that showed [shown]: the fields where [edited] differs from
     * [shown] (the user's changes) and from [current] (the row as stored now, read in this
     * transaction). A field another till changed while the screen was open keeps that change.
     * Returns the changed columns.
     */
    fun update(tx: Db.Tx, shown: Product, edited: Product, current: Product, now: Long): Set<String> {
        require(shown.id == edited.id && current.id == edited.id)
        val seen = fields(shown)
        val stored = fields(current)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in fields(edited)) if (seen[k] != v && stored[k] != v) changes[k] = v
        if (changes.isEmpty()) return emptySet()
        LwwWriter.update(tx, "product", Entity.PRODUCT, edited.id, changes, now)
        if ("name" in changes || "sku" in changes) reindex(tx, edited.id)
        // Its sales follow it to the new category in the reports' category totals (D-058).
        if ("category_id" in changes) com.lekaspos.data.sale.Summaries.recategorize(tx, edited.id, current.categoryId, edited.categoryId, hadRow = true)
        return changes.keys
    }

    /** Tombstones the product and its barcodes (history keeps pointing at the row). */
    fun delete(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "product", Entity.PRODUCT, id, now)
        for (b in barcodes(tx.db, id)) removeBarcode(tx, b.id, now)
        reindex(tx, id)
    }

    fun addBarcode(tx: Db.Tx, b: Barcode, now: Long) {
        LwwWriter.insert(
            tx, "product_barcode", Entity.BARCODE, b.id,
            linkedMapOf("product_id" to b.productId, "code" to b.code, "kind" to b.kind, "pack_qty" to b.packQty, "pack_price" to b.packPrice),
            now,
        )
    }

    private fun barcodeFields(b: Barcode): Map<String, Any?> =
        linkedMapOf("code" to b.code, "kind" to b.kind, "pack_qty" to b.packQty, "pack_price" to b.packPrice)

    fun barcode(db: SQLiteDatabase, id: Long): Barcode? = db.queryOne(
        "SELECT id, product_id, code, kind, pack_qty, pack_price FROM product_barcode WHERE id = ?", args(id),
    ) { c -> Barcode(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getLong(4), c.longOrNull(5)) }

    /** Writes the fields of [b] that differ from the barcode as stored now. Returns false if it does not exist. */
    fun updateBarcode(tx: Db.Tx, b: Barcode, now: Long): Boolean {
        val current = barcode(tx.db, b.id) ?: return false
        return updateBarcode(tx, current, b, now)
    }

    /**
     * An edit made on a screen that showed [before]: writes only the fields the user changed that
     * also differ from the barcode as stored now. Returns false if it does not exist.
     */
    fun updateBarcode(tx: Db.Tx, before: Barcode, after: Barcode, now: Long): Boolean {
        require(before.id == after.id)
        val current = barcode(tx.db, after.id) ?: return false
        val seen = barcodeFields(before)
        val stored = barcodeFields(current)
        val changes = LinkedHashMap<String, Any?>()
        for ((k, v) in barcodeFields(after)) if (seen[k] != v && stored[k] != v) changes[k] = v
        return LwwWriter.update(tx, "product_barcode", Entity.BARCODE, after.id, changes, now)
    }

    fun removeBarcode(tx: Db.Tx, id: Long, now: Long) {
        LwwWriter.delete(tx, "product_barcode", Entity.BARCODE, id, now)
    }

    fun barcodes(db: SQLiteDatabase, productId: Long): List<Barcode> = db.queryList(
        "SELECT id, product_id, code, kind, pack_qty, pack_price FROM product_barcode " +
            "WHERE product_id = ? AND deleted = 0 ORDER BY id",
        args(productId),
    ) { c -> Barcode(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getLong(4), c.longOrNull(5)) }

    // ------------------------------------------------------------------ CSV export / import (D-042)

    /** A product as exported to CSV; [tax] = the tax rate's name, [stock] = current level (milli). */
    data class ExportRow(
        val id: Long,
        val name: String,
        val sku: String?,
        val category: String?,
        val unit: String,
        val price: Long,
        val cost: Long,
        val tax: String?,
        val sellMode: Int,
        val trackStock: Boolean,
        val stock: Long?,
        val lowStock: Long,
        val active: Boolean,
        val barcodes: List<String>,
    )

    // A deleted category or tax rate is exported as blank (= keep on import), like the till treats it:
    // its name brought the category back as a new one, or the rate back onto the product (2026-10 review).
    private const val EXPORT_PAGE =
        "SELECT p.id, p.name, p.sku, c.name, p.unit, p.price, p.cost, t.name, p.sell_mode, p.track_stock, l.qty, " +
            "p.low_stock, p.active FROM product p " +
            "LEFT JOIN category c ON c.id = p.category_id AND c.deleted = 0 " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 " +
            "LEFT JOIN stock_level l ON l.product_id = p.id " +
            "WHERE p.deleted = 0 AND p.id > ? ORDER BY p.id LIMIT ?"

    // Plain unit barcodes only: pack barcodes and scale PLUs have extra settings a CSV cell cannot hold.
    private const val EXPORT_BARCODES =
        "SELECT product_id, code FROM product_barcode WHERE deleted = 0 AND product_id >= ? AND product_id <= ? " +
            "AND kind = ${BarcodeKind.BARCODE} AND pack_qty = 1000 ORDER BY product_id, id"

    /** Products in id order after [afterId], with their barcodes (two index reads, no per-row query). */
    fun exportPage(db: SQLiteDatabase, afterId: Long, limit: Int = 500): List<ExportRow> {
        val rows = db.queryList(EXPORT_PAGE, args(afterId, limit)) { c ->
            ExportRow(
                id = c.getLong(0), name = c.getString(1), sku = c.stringOrNull(2), category = c.stringOrNull(3),
                unit = c.getString(4), price = c.getLong(5), cost = c.getLong(6), tax = c.stringOrNull(7),
                sellMode = c.getInt(8), trackStock = c.bool(9), stock = c.longOrNull(10), lowStock = c.getLong(11),
                active = c.bool(12), barcodes = emptyList(),
            )
        }
        if (rows.isEmpty()) return rows
        val codes = HashMap<Long, MutableList<String>>()
        db.queryList(EXPORT_BARCODES, args(rows.first().id, rows.last().id)) { it.getLong(0) to it.getString(1) }
            .forEach { (pid, code) -> codes.getOrPut(pid) { ArrayList(2) }.add(code) }
        return rows.map { r -> codes[r.id]?.let { r.copy(barcodes = it) } ?: r }
    }

    private const val OWNERS_1 =
        "SELECT b.product_id FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
            "WHERE b.code = ? AND b.deleted = 0 AND p.deleted = 0 ORDER BY $CODE_WINNER LIMIT 5"
    private const val OWNERS_2 =
        "SELECT b.product_id FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
            "WHERE b.code IN (?, ?) AND b.deleted = 0 AND p.deleted = 0 ORDER BY $CODE_WINNER LIMIT 5"
    private const val OWNERS_3 =
        "SELECT b.product_id FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
            "WHERE b.code IN (?, ?, ?) AND b.deleted = 0 AND p.deleted = 0 ORDER BY $CODE_WINNER LIMIT 5"

    /**
     * The products (not deleted) that use one of [codes] (a code and its UPC/EAN forms, at most three,
     * see Gtin.lookupVariants), the one a scan picks first ([CODE_WINNER]); each once, at most five.
     */
    fun owners(db: SQLiteDatabase, codes: List<String>): List<Long> = when (codes.size) {
        0 -> emptyList()
        1 -> db.queryList(OWNERS_1, args(codes[0])) { it.getLong(0) }
        2 -> db.queryList(OWNERS_2, args(codes[0], codes[1])) { it.getLong(0) }
        else -> db.queryList(OWNERS_3, args(codes[0], codes[1], codes[2])) { it.getLong(0) }
    }.distinct()

    /** The product (not deleted) that uses [code] as a barcode, if any: the one a scan picks. */
    fun ownerOf(db: SQLiteDatabase, code: String): Long? = owners(db, listOf(code)).firstOrNull()

    /** The product (not deleted) with SKU [sku], if exactly one has it. */
    fun bySku(db: SQLiteDatabase, sku: String): Long? {
        val ids = db.queryList("SELECT id FROM product WHERE sku = ? AND deleted = 0 LIMIT 2", args(sku)) { it.getLong(0) }
        return ids.singleOrNull()
    }

    /**
     * Other products with [code] (duplicate-barcode warning) — also in its other UPC/EAN form, which answers the same scan
     * ("036000291452" beside "0036000291452" went unnoticed, 2026-10 review).
     */
    fun codeOwners(db: SQLiteDatabase, code: String, exceptProductId: Long): List<Pair<Long, String>> =
        Gtin.lookupVariants(code).flatMap { c ->
            db.queryList(
                "SELECT p.id, p.name FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
                    "WHERE b.code = ? AND b.deleted = 0 AND p.deleted = 0 AND p.id != ? LIMIT 5",
                args(c, exceptProductId),
            ) { it.getLong(0) to it.getString(1) }
        }.distinct().take(5)

    private const val MANAGE_PAGE =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.deleted = 0 AND p.name_key >= ? AND (p.name_key > ? OR p.id > ?) " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    /** All products (active and inactive) by name, keyset-paginated. First page: after = null. */
    fun managePage(db: SQLiteDatabase, after: ProductListItem?, limit: Int = 60): List<ProductListItem> {
        val key = after?.nameKey ?: ""
        val id = after?.id ?: -1L
        return db.queryList(MANAGE_PAGE, args(key, key, id, limit), ::listItem)
    }

    private const val SELL_PAGE =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.deleted = 0 AND p.active = 1 AND p.name_key >= ? AND (p.name_key > ? OR p.id > ?) " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    /** Sellable products by name for the catalogue's "All" tab, keyset-paginated. */
    fun sellPage(db: SQLiteDatabase, after: ProductListItem?, limit: Int = 60): List<ProductListItem> {
        val key = after?.nameKey ?: ""
        val id = after?.id ?: -1L
        return db.queryList(SELL_PAGE, args(key, key, id, limit), ::listItem)
    }

    // Best sellers by quantity over a range of days, from the daily summary (a range of its
    // primary key, never the sales themselves). Both bounds keep the range narrow on SQLite 3.8.
    // The period's product rows as whole months less a few days where it pays (SummaryRange,
    // D-058): 30 days of a big store were ~70,000 per-day rows, now ~40,000.
    private const val POPULAR_HEAD = "SELECT product_id FROM ("
    private const val POPULAR_TAIL = ") GROUP BY product_id HAVING SUM(qty) > 0 ORDER BY SUM(qty) DESC, product_id LIMIT ?"

    /** The products sold most (by quantity) from [fromDay] to [toDay] (inclusive), best first. */
    fun popularIds(db: SQLiteDatabase, fromDay: Long, toDay: Long, limit: Int): List<Long> {
        val rows = SummaryRange.qty(SummaryRange.plan(db, Period(fromDay, toDay + 1)))
        return db.queryList(POPULAR_HEAD + rows.sql + POPULAR_TAIL, rows.args + limit.toString()) { it.getLong(0) }
    }

    private const val BY_ID_PREFIX =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id$LOOK_JOIN " +
            "WHERE p.deleted = 0 AND p.active = 1 AND p.id IN "

    /** Sellable products among [ids], in the order of [ids] (deleted and inactive ones left out). */
    fun listByIds(db: SQLiteDatabase, ids: List<Long>): List<ProductListItem> {
        if (ids.isEmpty()) return emptyList()
        val out = HashMap<Long, ProductListItem>(ids.size * 2)
        for (chunk in ids.chunked(MAX_IN)) {
            val sql = BY_ID_PREFIX + chunk.joinToString(",", "(", ")") { "?" }
            for (item in db.queryList(sql, args(*chunk.toTypedArray()), ::listItem)) out[item.id] = item
        }
        return ids.mapNotNull { out[it] }
    }

    /** Bound parameters per IN list (SQLite allows 999). */
    private const val MAX_IN = 200

    /** Hot queries whose plans the perf suite verifies (name → SQL). */
    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "barcode_lookup" to FIND_BY_CODE_2,
        "barcode_lookup_3" to FIND_BY_CODE_3,
        "search_fts" to SEARCH_FTS,
        "search_prefix" to SEARCH_PREFIX,
        "search_barcode_prefix" to SEARCH_BARCODE_PREFIX,
        "search_fts_all" to SEARCH_FTS_ALL,
        "search_fts_letter" to "SELECT $LIST_COLUMNS FROM ($FTS_PAGE AND p.active = 1$LETTER$FTS_ORDER$FTS_COLUMNS",
        "search_prefix_all" to SEARCH_PREFIX_ALL,
        "search_barcode_prefix_all" to SEARCH_BARCODE_PREFIX_ALL,
        "category_page" to BY_CATEGORY,
        "category_page_all" to BY_CATEGORY_ALL,
        "product_manage_page" to MANAGE_PAGE,
        "product_sell_page" to SELL_PAGE,
        "product_export" to EXPORT_PAGE,
        "product_export_barcodes" to EXPORT_BARCODES,
        "barcode_owners" to OWNERS_2,
        "barcode_owners_3" to OWNERS_3,
        "popular_ids" to POPULAR_HEAD + SummaryRange.qty(SummaryRange.SAMPLE).sql + POPULAR_TAIL,
        "products_by_ids" to BY_ID_PREFIX + "(?,?,?)",
    )
}
