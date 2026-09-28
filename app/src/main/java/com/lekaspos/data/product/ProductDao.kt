package com.lekaspos.data.product

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.text.SearchText
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.args
import com.lekaspos.data.db.bool
import com.lekaspos.data.db.long
import com.lekaspos.data.db.longOrNull
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.queryOne
import com.lekaspos.data.db.stringOrNull

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

    /** Inserts a new product with its barcodes as one LWW creation at [hlc]. */
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

    private const val FIND_BY_CODE_1 =
        "SELECT $SELLABLE_COLUMNS, b.code, b.kind, b.pack_qty, b.pack_price " +
            "FROM product_barcode b JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 " +
            "WHERE b.code = ? AND b.deleted = 0 AND b.kind = ? AND p.deleted = 0 " +
            "ORDER BY b.updated_at DESC, b.id DESC LIMIT 1"

    private const val FIND_BY_CODE_2 =
        "SELECT $SELLABLE_COLUMNS, b.code, b.kind, b.pack_qty, b.pack_price " +
            "FROM product_barcode b JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 " +
            "WHERE b.code IN (?, ?) AND b.deleted = 0 AND b.kind = ? AND p.deleted = 0 " +
            "ORDER BY b.updated_at DESC, b.id DESC LIMIT 1"

    /**
     * Resolves a scanned code. [codes] are the lookup variants (see Gtin.lookupVariants), at
     * most two. With duplicates, the most recently changed barcode wins (references/sync.md §4).
     */
    fun findByCode(db: SQLiteDatabase, codes: List<String>, kind: Int = BarcodeKind.BARCODE): ScanHit? =
        when (codes.size) {
            0 -> null
            1 -> db.queryOne(FIND_BY_CODE_1, args(codes[0], kind), ::scanHit)
            else -> db.queryOne(FIND_BY_CODE_2, args(codes[0], codes[1], kind), ::scanHit)
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

    fun sellableById(db: SQLiteDatabase, id: Long): SellableProduct? = db.queryOne(
        "SELECT $SELLABLE_COLUMNS FROM product p " +
            "LEFT JOIN tax_rate t ON t.id = p.tax_rate_id AND t.deleted = 0 WHERE p.id = ?",
        args(id), ::sellable,
    )

    private const val LIST_COLUMNS = "p.id, p.name, p.name_key, p.price, p.unit, p.sell_mode, s.qty"

    /** Upper bound of FTS candidates joined and sorted per search: keeps common prefixes cheap. */
    const val FTS_CANDIDATES = 2000

    // The capped FTS subquery is the outer loop (CROSS JOIN pins the order), so the cost is
    // bounded by FTS_CANDIDATES even when a short prefix matches most of the catalogue.
    private const val SEARCH_FTS =
        "SELECT $LIST_COLUMNS FROM (SELECT docid FROM product_fts WHERE product_fts MATCH ? LIMIT $FTS_CANDIDATES) f " +
            "CROSS JOIN product p ON p.id = f.docid " +
            "LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE p.deleted = 0 AND p.active = 1 " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    private const val SEARCH_PREFIX =
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE p.deleted = 0 AND p.active = 1 AND p.name_key >= ? AND p.name_key < ? " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    private const val SEARCH_BARCODE_PREFIX =
        "SELECT $LIST_COLUMNS FROM product_barcode b CROSS JOIN product p ON p.id = b.product_id " +
            "LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE b.code >= ? AND b.code < ? AND b.deleted = 0 AND p.deleted = 0 AND p.active = 1 " +
            "ORDER BY b.code LIMIT ?"

    /**
     * Product search for the selling screen:
     *  - digits only (2+) → barcode/PLU prefix range on the barcode index, then name/SKU matches;
     *  - one Latin letter or digit → name-prefix index range;
     *  - otherwise → FTS4 prefix match on name and SKU, all tokens required.
     * Every path is an index range or a capped FTS lookup, never a table scan.
     */
    fun search(db: SQLiteDatabase, input: String, limit: Int = 50): List<ProductListItem> {
        val tokens = SearchText.tokens(input)
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashMap<Long, ProductListItem>()
        val trimmed = input.trim()
        if (trimmed.length >= 2 && trimmed.all { it in '0'..'9' }) {
            val upper = prefixUpper(trimmed)
            for (item in db.queryList(SEARCH_BARCODE_PREFIX, args(trimmed, upper, limit), ::listItem)) {
                if (!out.containsKey(item.id)) out[item.id] = item // (Map.putIfAbsent is API 24+)
            }
            if (out.size >= limit) return out.values.toList()
        }
        val more = if (tokens.size == 1 && tokens[0].length == 1 && !SearchText.isCjkChar(tokens[0])) {
            val p = tokens[0]
            db.queryList(SEARCH_PREFIX, args(p, prefixUpper(p), limit), ::listItem)
        } else {
            val match = SearchText.ftsQuery(input) ?: return out.values.toList()
            db.queryList(SEARCH_FTS, args(match, limit), ::listItem)
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
        "SELECT $LIST_COLUMNS FROM product p LEFT JOIN stock_level s ON s.product_id = p.id " +
            "WHERE p.category_id = ? AND p.deleted = 0 AND p.active = 1 " +
            "AND p.name_key >= ? AND (p.name_key > ? OR p.id > ?) " +
            "ORDER BY p.name_key, p.id LIMIT ?"

    /** One page of a category, keyset-paginated by (name_key, id). First page: after = null. */
    fun byCategory(db: SQLiteDatabase, categoryId: Long, after: ProductListItem?, limit: Int = 60): List<ProductListItem> {
        val key = after?.nameKey ?: ""
        val id = after?.id ?: -1L
        return db.queryList(BY_CATEGORY, args(categoryId, key, key, id, limit), ::listItem)
    }

    private fun listItem(c: Cursor) = ProductListItem(
        id = c.getLong(0),
        name = c.getString(1),
        nameKey = c.getString(2),
        price = c.getLong(3),
        unit = c.getString(4),
        sellMode = c.getInt(5),
        stockQty = c.longOrNull(6),
    )

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

    /** Hot queries whose plans the perf suite verifies (name → SQL). */
    val HOT_QUERIES: List<Pair<String, String>> = listOf(
        "barcode_lookup" to FIND_BY_CODE_2,
        "search_fts" to SEARCH_FTS,
        "search_prefix" to SEARCH_PREFIX,
        "search_barcode_prefix" to SEARCH_BARCODE_PREFIX,
        "category_page" to BY_CATEGORY,
    )
}
