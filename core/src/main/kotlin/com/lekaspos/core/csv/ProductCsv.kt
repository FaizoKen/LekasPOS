package com.lekaspos.core.csv

import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.text.SearchText

/**
 * The product CSV format (D-042): the same columns for export and import, so an exported file
 * can be edited in a spreadsheet and imported back. Headers are matched loosely (English or
 * Malay, any case, spaces or underscores); only `name` and `price` are required. Pure: the app
 * resolves categories, tax rates and barcode owners.
 */
object ProductCsv {

    enum class Column(val header: String, vararg val aliases: String) {
        NAME("name", "nama", "product", "produk", "item", "product name", "nama produk"),
        BARCODES("barcodes", "barcode", "kod bar", "kodbar", "ean", "upc", "barcode no"),
        SKU("sku", "code", "kod", "item code", "kod item"),
        CATEGORY("category", "kategori", "department", "jabatan"),
        UNIT("unit", "uom"),
        PRICE("price", "harga", "price rm", "harga rm", "selling price", "harga jual", "retail price"),
        COST("cost", "kos", "cost rm", "kos rm", "cost price", "harga kos", "harga beli"),
        TAX("tax", "cukai", "tax rate", "kadar cukai", "sst"),
        SOLD_BY("sold by", "sold_by", "jual ikut", "sell mode"),
        TRACK_STOCK("track stock", "jejak stok", "stock tracking"),
        STOCK("stock", "stok", "quantity", "qty", "kuantiti", "on hand"),
        LOW_STOCK("low stock", "stok rendah", "reorder level", "alert"),
        ACTIVE("active", "aktif", "for sale", "dijual"),
    }

    /** Export order (also the template). */
    val COLUMNS: List<Column> = Column.values().toList()

    enum class Problem {
        NAME_MISSING, NAME_TOO_LONG, PRICE_MISSING, PRICE_BAD, COST_BAD, STOCK_BAD, LOW_STOCK_BAD, SOLD_BY_BAD,
        YES_NO_BAD, BARCODE_BAD, TAX_BAD,

        /** Checked by the app: the same barcode twice in the file, or owned by another product. */
        BARCODE_TWICE, BARCODE_TAKEN, BARCODES_SPLIT, TAX_UNKNOWN,
    }

    /** Where each column is in the file; [missing] required columns make the file unusable. */
    class Header(val index: Map<Column, Int>, val unknown: List<String>) {
        val missing: List<Column> get() = listOf(Column.NAME, Column.PRICE).filter { it !in index }
        fun has(c: Column): Boolean = c in index
    }

    /**
     * One valid row. Nullable fields were empty or absent: an import leaves those unchanged on
     * existing products and uses defaults for new ones.
     */
    data class Row(
        val name: String,
        val price: Long,
        val barcodes: List<String> = emptyList(),
        val sku: String? = null,
        val category: String? = null,
        val unit: String? = null,
        val cost: Long? = null,
        /** Tax as written: a rate's name or a percentage, or "none" / "0%" to remove it. */
        val tax: String? = null,
        val sellMode: Int? = null,
        val trackStock: Boolean? = null,
        val stock: Long? = null,
        val lowStock: Long? = null,
        val active: Boolean? = null,
    )

    sealed class Parsed {
        data class Ok(val row: Row) : Parsed()
        data class Bad(val problems: List<Pair<Problem, Column?>>) : Parsed()
    }

    private fun norm(s: String): String = SearchText.normalize(s.replace('_', ' ')).trim()

    fun header(fields: List<String>): Header {
        val lookup = HashMap<String, Column>()
        for (c in Column.values()) {
            lookup[norm(c.header)] = c
            for (a in c.aliases) lookup[norm(a)] = c
        }
        val index = LinkedHashMap<Column, Int>()
        val unknown = ArrayList<String>()
        for ((i, f) in fields.withIndex()) {
            val c = lookup[norm(f)]
            if (c == null) {
                if (f.isNotBlank()) unknown.add(f.trim())
            } else if (c !in index) {
                index[c] = i
            }
        }
        return Header(index, unknown)
    }

    fun parse(fields: List<String>, h: Header, currency: CurrencySpec): Parsed {
        val problems = ArrayList<Pair<Problem, Column?>>()
        fun cell(c: Column): String? = h.index[c]?.let { fields.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        val name = cell(Column.NAME)
        if (name == null) problems.add(Problem.NAME_MISSING to Column.NAME)
        else if (name.length > MAX_NAME) problems.add(Problem.NAME_TOO_LONG to Column.NAME)

        val priceText = cell(Column.PRICE)
        val price = priceText?.let { money(it, currency) }
        if (priceText == null) problems.add(Problem.PRICE_MISSING to Column.PRICE)
        else if (price == null || price < 0L) problems.add(Problem.PRICE_BAD to Column.PRICE)

        val cost = cell(Column.COST)?.let { t -> money(t, currency).also { if (it == null || it < 0L) problems.add(Problem.COST_BAD to Column.COST) } }

        val barcodes = cell(Column.BARCODES)?.split(BARCODE_SPLIT)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (barcodes.any { it.length > MAX_BARCODE || it.any { c -> c.isWhitespace() || c.code < 32 } }) {
            problems.add(Problem.BARCODE_BAD to Column.BARCODES)
        }

        val sellMode = cell(Column.SOLD_BY)?.let { t -> sellMode(t).also { if (it == null) problems.add(Problem.SOLD_BY_BAD to Column.SOLD_BY) } }
        val track = cell(Column.TRACK_STOCK)?.let { t -> yesNo(t).also { if (it == null) problems.add(Problem.YES_NO_BAD to Column.TRACK_STOCK) } }
        val active = cell(Column.ACTIVE)?.let { t -> yesNo(t).also { if (it == null) problems.add(Problem.YES_NO_BAD to Column.ACTIVE) } }
        val stock = cell(Column.STOCK)?.let { t -> MoneyFormat.parseQty(t).also { if (it == null) problems.add(Problem.STOCK_BAD to Column.STOCK) } }
        val low = cell(Column.LOW_STOCK)?.let { t ->
            MoneyFormat.parseQty(t).also { if (it == null || it < 0L) problems.add(Problem.LOW_STOCK_BAD to Column.LOW_STOCK) }
        }
        val tax = cell(Column.TAX)
        if (!tax.isNullOrEmpty() && percentBp(tax) == null && tax.length > MAX_NAME) problems.add(Problem.TAX_BAD to Column.TAX)

        if (problems.isNotEmpty() || name == null || price == null) return Parsed.Bad(problems)
        return Parsed.Ok(
            Row(
                name = name, price = price, barcodes = barcodes.distinct(), sku = cell(Column.SKU),
                category = cell(Column.CATEGORY), unit = cell(Column.UNIT), cost = cost, tax = tax,
                sellMode = sellMode, trackStock = track, stock = stock, lowStock = low, active = active,
            ),
        )
    }

    /** A product as a CSV row in [COLUMNS] order. */
    fun format(r: Row, currency: CurrencySpec): List<String> = listOf(
        r.name,
        r.barcodes.joinToString(" | "),
        r.sku ?: "",
        r.category ?: "",
        r.unit ?: "",
        MoneyFormat.plain(r.price, currency.decimals),
        r.cost?.let { MoneyFormat.plain(it, currency.decimals) } ?: "",
        r.tax ?: "",
        when (r.sellMode) {
            SellMode.WEIGHT -> "weight"
            SellMode.OPEN_PRICE -> "open price"
            else -> "piece"
        },
        if (r.trackStock != false) "yes" else "no",
        r.stock?.let { MoneyFormat.formatQty(it) } ?: "",
        r.lowStock?.let { MoneyFormat.formatQty(it) } ?: "",
        if (r.active != false) "yes" else "no",
    )

    fun money(text: String, currency: CurrencySpec): Long? =
        MoneyFormat.parse(text, currency) ?: MoneyFormat.parsePlain(text, currency.decimals)

    fun sellMode(text: String): Int? = when (norm(text)) {
        "piece", "pieces", "pcs", "pc", "unit", "units", "each", "biji", "unit biji", "kotak", "box" -> SellMode.UNIT
        "weight", "weighed", "kg", "g", "gram", "berat", "timbang", "ditimbang" -> SellMode.WEIGHT
        "open", "open price", "harga terbuka", "harga bebas" -> SellMode.OPEN_PRICE
        else -> null
    }

    fun yesNo(text: String): Boolean? = when (norm(text)) {
        "yes", "y", "true", "1", "ya", "on" -> true
        "no", "n", "false", "0", "tidak", "tak", "off" -> false
        else -> null
    }

    /** "6%", "6", "6.5 %", "SST 6%" → basis points (600, 600, 650, 600); null if no number. */
    fun percentBp(text: String): Int? {
        val m = PERCENT.find(text) ?: return null
        val v = MoneyFormat.parsePlain(m.groupValues[1], 2) ?: return null // hundredths of a percent
        if (v < 0L || v > 10_000L) return null
        return v.toInt()
    }

    const val MAX_NAME = 120
    const val MAX_BARCODE = 48
    private val BARCODE_SPLIT = Regex("[|;,/]+")
    private val PERCENT = Regex("(\\d+(?:\\.\\d{1,2})?)\\s*%?\\s*$")
}
