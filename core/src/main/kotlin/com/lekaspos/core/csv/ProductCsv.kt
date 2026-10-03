package com.lekaspos.core.csv

import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.text.SearchText

/**
 * The product CSV format (D-042): the same columns for export and import, so an exported file
 * can be edited in a spreadsheet and imported back. Headers are matched loosely (English or
 * Malay, any case, spaces, underscores, punctuation, "RM"); only `name` and `price` are required.
 * Pure: the app resolves categories, tax rates and barcode owners.
 */
object ProductCsv {

    /**
     * A column, its export [header] and the headers it is also known by. Headers are compared
     * by [headerKey]: any case, accents, punctuation, `_`, and a currency word ("Selling Price (RM)"
     * is "selling price"). A [weak] header ("Description", "Item") is used only when the file has
     * no stronger one for that column: "Name, Description, Price" takes its names from "Name".
     */
    enum class Column(val header: String, val aliases: List<String>, val weak: List<String> = emptyList()) {
        NAME(
            "name",
            listOf("nama", "product name", "nama produk", "item name", "nama item", "nama barang", "nama barangan"),
            weak = listOf(
                "product", "produk", "item", "barang", "description", "item description", "product description",
                "keterangan", "perihal",
            ),
        ),
        BARCODES(
            "barcodes",
            listOf(
                "barcode", "bar code", "bar codes", "barcode no", "barcode number", "no barcode", "nombor barcode",
                "kod bar", "kodbar", "kod barcode", "ean", "ean13", "ean 13", "upc", "gtin",
            ),
        ),
        SKU(
            "sku",
            listOf(
                "code", "kod", "item code", "kod item", "product code", "kod produk", "kod barang", "item no",
                "item number", "stock code", "kod stok", "plu", "plu code",
            ),
        ),
        CATEGORY(
            "category",
            listOf("kategori", "department", "jabatan", "product category", "kategori produk", "item category"),
            weak = listOf("group", "item group", "kumpulan"),
        ),
        UNIT("unit", listOf("uom", "unit of measure", "unit of measurement", "unit ukuran", "unit sukatan", "sukatan")),
        PRICE(
            "price",
            listOf(
                "harga", "price rm", "harga rm", "selling price", "harga jual", "harga jualan", "retail price",
                "harga runcit", "unit price", "harga unit", "harga seunit", "price per unit", "harga per unit",
                "sale price", "sales price",
            ),
        ),
        COST(
            "cost",
            listOf(
                "kos", "cost rm", "kos rm", "cost price", "harga kos", "harga beli", "harga belian", "purchase price",
                "buying price", "unit cost", "cost per unit", "kos seunit", "kos unit", "harga modal",
            ),
        ),
        TAX(
            "tax",
            listOf(
                "cukai", "tax rate", "kadar cukai", "sst", "sst rate", "kadar sst", "tax code", "kod cukai", "gst",
                "sales tax", "cukai jualan", "service tax", "cukai perkhidmatan",
            ),
        ),
        SOLD_BY("sold by", listOf("sold_by", "jual ikut", "dijual ikut", "sell mode")),
        TRACK_STOCK("track stock", listOf("jejak stok", "stock tracking")),
        STOCK(
            "stock",
            listOf(
                "stok", "quantity", "qty", "kuantiti", "on hand", "stock qty", "stock quantity", "stock on hand",
                "qty on hand", "quantity on hand", "kuantiti stok", "stok semasa", "current stock", "opening stock",
                "stok awal", "stok permulaan",
            ),
        ),
        LOW_STOCK(
            "low stock",
            listOf(
                "stok rendah", "reorder level", "reorder point", "alert", "min stock", "minimum stock", "stok minimum",
                "min qty", "minimum qty",
            ),
        ),
        ACTIVE("active", listOf("aktif", "for sale", "dijual")),

        /**
         * The product's own number in this store, last: an exported file edited and imported
         * again updates the same products, also those with no barcode or SKU. Written as `#…` so
         * a spreadsheet keeps it as text (a 19-digit number would come back rounded).
         */
        ID("id", listOf("product id", "id produk")),
    }

    /** Export order (also the template). */
    val COLUMNS: List<Column> = Column.values().toList()

    enum class Problem {
        NAME_MISSING, NAME_TOO_LONG, PRICE_MISSING, PRICE_BAD, COST_BAD, STOCK_BAD, LOW_STOCK_BAD, SOLD_BY_BAD,
        YES_NO_BAD, BARCODE_BAD, TAX_BAD,

        /** Text longer than [maxLength] of its column (SKU, category, unit). */
        TOO_LONG,

        /** An amount above [MAX_AMOUNT] or a quantity above [MAX_QTY] (crafted or mistyped files). */
        TOO_LARGE,

        /** Checked by the app: the same barcode twice in the file, or owned by another product. */
        BARCODE_TWICE, BARCODE_TAKEN, BARCODES_SPLIT,

        /** Checked by the app: the same SKU twice in the file (the rows would merge into one product). */
        SKU_TWICE,
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
        /** Tax as written: a rate's name or a percentage, or "none" / "0%" to remove it (see [taxChoice]). */
        val tax: String? = null,
        val sellMode: Int? = null,
        val trackStock: Boolean? = null,
        val stock: Long? = null,
        val lowStock: Long? = null,
        val active: Boolean? = null,
        /** [Column.ID]: the product this row was exported from (matched first on import). */
        val id: Long? = null,
        /** Barcodes as the file wrote them, where that differs from [barcodes] (canonical form → as written). */
        val asWritten: Map<String, String> = emptyMap(),
    )

    sealed class Parsed {
        data class Ok(val row: Row) : Parsed()
        data class Bad(val problems: List<Pair<Problem, Column?>>) : Parsed()

        /** One of the template's example rows ([isExample]): never imported, whatever else it holds. */
        object Example : Parsed()
    }

    private fun norm(s: String): String = SearchText.normalize(s.replace('_', ' ')).trim()

    /** A header as it is compared: normalized words without a currency word ("Harga (RM)" → "harga"). */
    fun headerKey(s: String): String = SearchText.tokens(s.replace('_', ' ')).filter { it !in CURRENCY_WORDS }.joinToString(" ")

    private val STRONG: Map<String, Column> by lazy {
        val m = HashMap<String, Column>()
        for (c in Column.values()) {
            m[headerKey(c.header)] = c
            for (a in c.aliases) m[headerKey(a)] = c
        }
        m
    }

    private val WEAK: Map<String, Column> by lazy {
        val m = HashMap<String, Column>()
        for (c in Column.values()) for (a in c.weak) m[headerKey(a)] = c
        m
    }

    fun header(fields: List<String>): Header {
        val index = LinkedHashMap<Column, Int>()
        val weak = LinkedHashMap<Column, Int>()
        val unused = ArrayList<Pair<Int, String>>()
        for ((i, f) in fields.withIndex()) {
            val key = headerKey(f)
            val strong = STRONG[key]
            val w = WEAK[key]
            when {
                strong != null -> if (strong !in index) index[strong] = i else unused.add(i to f)
                w != null -> if (w !in weak) weak[w] = i else unused.add(i to f)
                f.isNotBlank() -> unused.add(i to f)
            }
        }
        for ((c, i) in weak) {
            if (c !in index) index[c] = i else unused.add(i to fields[i])
        }
        // Every column the import does not use is named ("Ignored columns"), in file order.
        val unknown = unused.sortedBy { it.first }.map { it.second.trim() }.filter { it.isNotEmpty() }
        return Header(index, unknown)
    }

    fun parse(fields: List<String>, h: Header, currency: CurrencySpec): Parsed {
        val problems = ArrayList<Pair<Problem, Column?>>()
        fun cell(c: Column): String? = h.index[c]?.let { fields.getOrNull(it) }?.trim()?.let(CsvWriter::undefuse)?.takeIf { it.isNotEmpty() }

        val name = cell(Column.NAME)
        if (name != null && isExample(name)) return Parsed.Example
        if (name == null) problems.add(Problem.NAME_MISSING to Column.NAME)
        else if (name.length > MAX_NAME) problems.add(Problem.NAME_TOO_LONG to Column.NAME)

        val priceText = cell(Column.PRICE)
        val price = priceText?.let { money(it, currency) }
        if (priceText == null) problems.add(Problem.PRICE_MISSING to Column.PRICE)
        else if (price == null || price < 0L) problems.add(Problem.PRICE_BAD to Column.PRICE)
        else if (price > MAX_AMOUNT) problems.add(Problem.TOO_LARGE to Column.PRICE)

        val cost = cell(Column.COST)?.let { t ->
            money(t, currency).also {
                if (it == null || it < 0L) problems.add(Problem.COST_BAD to Column.COST)
                else if (it > MAX_AMOUNT) problems.add(Problem.TOO_LARGE to Column.COST)
            }
        }

        val written = cell(Column.BARCODES)?.let(::unformatNumber)?.split(BARCODE_SPLIT)
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val barcodes = written.map(Gtin::canonical) // "1234565" (a number in a spreadsheet) → "01234565"; a GTIN-14 → its EAN-13
        // "9.55600E+12": the spreadsheet turned the barcode into a number and lost its digits; and a
        // decimal number is never a barcode ("9556001234567.5").
        if (barcodes.any { it.length > MAX_BARCODE || it.any { c -> c.isWhitespace() || c.code < 32 } || SCIENTIFIC.matches(it) || DECIMAL.matches(it) }) {
            problems.add(Problem.BARCODE_BAD to Column.BARCODES)
        }

        val sku = cell(Column.SKU)?.takeUnless { NO_SKU.matches(it) }
        val category = cell(Column.CATEGORY)
        val unit = cell(Column.UNIT)
        for ((c, v) in listOf(Column.SKU to sku, Column.CATEGORY to category, Column.UNIT to unit)) {
            if (v != null && v.length > maxLength(c)) problems.add(Problem.TOO_LONG to c)
        }

        val sellMode = cell(Column.SOLD_BY)?.let { t -> sellMode(t).also { if (it == null) problems.add(Problem.SOLD_BY_BAD to Column.SOLD_BY) } }
        val track = cell(Column.TRACK_STOCK)?.let { t -> yesNo(t).also { if (it == null) problems.add(Problem.YES_NO_BAD to Column.TRACK_STOCK) } }
        val active = cell(Column.ACTIVE)?.let { t -> yesNo(t).also { if (it == null) problems.add(Problem.YES_NO_BAD to Column.ACTIVE) } }
        val stock = cell(Column.STOCK)?.let { t ->
            MoneyFormat.parseQty(t).also {
                if (it == null) problems.add(Problem.STOCK_BAD to Column.STOCK)
                else if (it > MAX_QTY || it < -MAX_QTY) problems.add(Problem.TOO_LARGE to Column.STOCK)
            }
        }
        val low = cell(Column.LOW_STOCK)?.let { t ->
            MoneyFormat.parseQty(t).also {
                if (it == null || it < 0L) problems.add(Problem.LOW_STOCK_BAD to Column.LOW_STOCK)
                else if (it > MAX_QTY) problems.add(Problem.TOO_LARGE to Column.LOW_STOCK)
            }
        }
        val tax = cell(Column.TAX)
        if (!tax.isNullOrEmpty() && percentBp(tax) == null && tax.length > MAX_NAME) problems.add(Problem.TAX_BAD to Column.TAX)

        if (problems.isNotEmpty() || name == null || price == null) return Parsed.Bad(problems)
        return Parsed.Ok(
            Row(
                name = name, price = price, barcodes = barcodes.distinct(), sku = sku,
                category = category, unit = unit, cost = cost, tax = tax,
                sellMode = sellMode, trackStock = track, stock = stock, lowStock = low, active = active,
                id = cell(Column.ID)?.removePrefix("#")?.trim()?.toLongOrNull()?.takeIf { it > 0L },
                asWritten = written.zip(barcodes).filter { (w, c) -> w != c }.associate { (w, c) -> c to w },
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
        r.id?.let { "#$it" } ?: "",
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

    // ------------------------------------------------------------------ tax

    /** What a tax cell asks for, given the store's rates (see [taxChoice]). */
    sealed class TaxChoice {
        /** No tax: a product's rate is removed. */
        object None : TaxChoice()

        /** The store's rate at this index of the list given. */
        data class Rate(val index: Int) : TaxChoice()

        /**
         * A tax the store does not have ("SST 10%" with no 10 % rate, "Y" with several rates).
         * Imported without it (existing products keep theirs) and named in the preview: whether a
         * shop charges SST is a legal fact, and tax rates are set up under Settings, with their own
         * permission and audit (D-056) — an import does not create them.
         */
        object Unmatched : TaxChoice()
    }

    /**
     * A tax cell against the store's rates (name, basis points): a rate's name first, then
     * "no tax" words and placeholders ("-", "N/A", "Exempt", "0%", "N" …), then a rate with the
     * cell's percentage, then a yes ("Y", "Taxable") meaning the store's only rate.
     */
    fun taxChoice(text: String, rates: List<Pair<String, Int>>): TaxChoice {
        val key = SearchText.key(text)
        if (key.isNotEmpty()) {
            val byName = rates.indexOfFirst { SearchText.key(it.first) == key }
            if (byName >= 0) return TaxChoice.Rate(byName)
        }
        if (isNoTax(text)) return TaxChoice.None
        val bp = percentBp(text)
        if (bp != null) {
            val i = rates.indexOfFirst { it.second == bp }
            return if (i >= 0) TaxChoice.Rate(i) else TaxChoice.Unmatched
        }
        if (key in TAX_YES && rates.size == 1) return TaxChoice.Rate(0)
        return TaxChoice.Unmatched
    }

    /**
     * Empty or a placeholder ("-", "–", ".", "N/A", "nil"), a "no tax" word in English or Malay
     * ("none", "exempt", "tiada", "dikecualikan", "N" of a Y/N column), or 0 %. The key of "-" is
     * empty: compared on keys alone, "-" never matched and the row was skipped (2026-10 review).
     */
    fun isNoTax(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val key = SearchText.key(t)
        if (key.isEmpty() || key in NO_TAX) return true // punctuation only: "-", "—", ".", "/"
        return percentBp(t) == 0
    }

    // ------------------------------------------------------------------ template examples

    /** What the template's example names start with ("EXAMPLE – Milo 1kg"). */
    const val EXAMPLE_PREFIX = "EXAMPLE - " // ASCII: a dash survives any code page a spreadsheet saves in

    /**
     * One of the template's example rows: a name starting with EXAMPLE or CONTOH and a dash or
     * colon. Owners type their products below the examples and leave them there: they were
     * imported as real products (2026-10 review).
     */
    fun isExample(name: String): Boolean = EXAMPLE.containsMatchIn(name)

    /**
     * A barcode cell a spreadsheet wrote as a formatted number (2026-10 review): "9556001234567.00"
     * (a "Number" column) loses its zero decimals, and "9,556,001,234,567" (thousands separators)
     * its commas when the digits make a valid EAN/UPC — commas otherwise separate several codes.
     */
    internal fun unformatNumber(cell: String): String {
        val t = cell.trim()
        ZERO_DECIMALS.matchEntire(t)?.let { return it.groupValues[1] }
        val grouped = GROUPED.matchEntire(t) ?: return cell
        val digits = grouped.groupValues[1].replace(",", "")
        return if (digits.length in GTIN_LENGTHS && Gtin.isValid(digits)) digits else cell
    }

    /** Longest text a column takes (name, SKU, category: [MAX_NAME]; unit: [MAX_UNIT]). */
    fun maxLength(c: Column): Int = if (c == Column.UNIT) MAX_UNIT else MAX_NAME

    const val MAX_NAME = 120
    const val MAX_UNIT = 32
    const val MAX_BARCODE = 48

    /** Largest price or cost: 99,999,999.99 in minor units (RM 99,999,999.99). */
    const val MAX_AMOUNT = 9_999_999_999L

    /**
     * Largest stock or low-stock quantity (milli-units): 99,999 — as on a bill line
     * (CartSession.MAX_QTY), and a quantity times [MAX_AMOUNT] still fits in a Long.
     */
    const val MAX_QTY = 99_999_000L

    /** Rows of one file the import reads; the rest is not imported (the preview says so). */
    const val MAX_ROWS = 100_000

    private val BARCODE_SPLIT = Regex("[|;,/]+")
    private val SCIENTIFIC = Regex("[0-9]+(?:[.,][0-9]+)?[eE][+-]?[0-9]+")
    private val DECIMAL = Regex("[0-9]+[.,][0-9]+")
    private val ZERO_DECIMALS = Regex("([0-9]+)\\.0+")
    private val GROUPED = Regex("([0-9]{1,3}(?:,[0-9]{3})+)(?:\\.0+)?")
    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)

    /** Currency words dropped from headers: "Price (RM)", "Harga MYR". */
    private val CURRENCY_WORDS = setOf("rm", "myr")

    /** What spreadsheets hold where there is no SKU: rows with it must not all be one product (2026-10 review). */
    private val NO_SKU = Regex("[-–—.0 ]+|n/?a|nil|none|null|tiada|tidak ada", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("(\\d+(?:\\.\\d{1,2})?)\\s*%?\\s*$")

    /** Keys ([SearchText.key]) of tax cells meaning no tax. */
    private val NO_TAX = setOf(
        "none", "no", "n", "x", "nil", "null", "na", "n a", "not applicable", "false", "0", "no tax", "non taxable",
        "not taxable", "exempt", "exempted", "tax exempt", "zero rated", "zero", "tiada", "tidak", "tak", "tiada cukai",
        "tanpa cukai", "bebas cukai", "tidak dikenakan", "dikecualikan", "kecuali",
    )

    /** Keys of tax cells meaning "taxed" without saying which rate (a Y/N column). */
    private val TAX_YES = setOf("y", "yes", "ya", "true", "taxable", "kena cukai", "dikenakan", "sst", "tax", "cukai")

    /** EXAMPLE or CONTOH and any separator (a dash decoded in another code page is no "-" any more). */
    private val EXAMPLE = Regex("^\\s*(example|contoh)\\s*[^\\p{L}\\p{N}\\s]", RegexOption.IGNORE_CASE)
}
