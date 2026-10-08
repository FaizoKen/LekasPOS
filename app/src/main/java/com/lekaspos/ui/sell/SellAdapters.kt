package com.lekaspos.ui.sell

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.model.TileColor
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.AppliedPromo
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PricedCart
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.ui.colorOf
import com.lekaspos.ui.common.TileColors

/** One row of the bill as shown: the item plus its priced amounts. */
data class CartRow(val item: CartItem, val amount: Long, val selected: Boolean, val promo: AppliedPromo? = null)

/** What a tap on a bill line and the buttons of the selected line do. */
interface LineActions {
    fun select(item: CartItem)
    /** − or + : [delta] is added to the quantity the line has now. */
    fun changeQty(item: CartItem, delta: Long)
    fun enterQty(item: CartItem)
    fun remove(item: CartItem)
}

/**
 * The bill. The selected line (the one just scanned, or tapped) shows its buttons in place —
 * Remove, −, quantity, + on one line under it (LineControls) — so changing a count is one tap,
 * not a dialog (D-049). The same for the owner and a cashier: discounts and prices are in the Menu (D-064).
 */
class CartAdapter(private val actions: LineActions) : RecyclerView.Adapter<CartAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
        val amount: TextView = v.findViewById(R.id.amount)
        val controls: View = v.findViewById(R.id.controls)
        val remove: View = v.findViewById(R.id.line_remove)
        val minus: View = v.findViewById(R.id.line_minus)
        val qty: TextView = v.findViewById(R.id.line_qty)
        val plus: View = v.findViewById(R.id.line_plus)
    }

    private var rows: List<CartRow> = emptyList()
    var currency: CurrencySpec = CurrencySpec.MYR

    fun submit(items: List<CartItem>, priced: PricedCart, selectedKey: Long) {
        val next = items.mapIndexed { i, it ->
            val pl = priced.lines.getOrNull(i)
            val amount = if (pl != null) pl.gross - pl.lineDiscount else 0L
            CartRow(it, amount, it.key == selectedKey, priced.promotions.getOrNull(i))
        }
        val old = rows
        rows = next
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].item.key == next[n].item.key
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == next[n]
        }).dispatchUpdatesTo(this)
    }

    fun positionOf(key: Long): Int = rows.indexOfFirst { it.item.key == key }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_cart_line, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val row = rows[position]
        val it = row.item
        val ctx = h.itemView.context
        h.name.text = it.name
        h.amount.text = MoneyFormat.format(row.amount, currency, withSymbol = false)
        h.detail.text = detail(ctx, it, row.promo)
        h.itemView.setBackgroundResource(if (row.selected) R.drawable.row_highlight else R.drawable.row_ripple)
        h.itemView.isSelected = row.selected // TalkBack says "selected", not only the highlight colour
        h.itemView.setOnClickListener { _ -> actions.select(it) }
        h.controls.visibility = if (row.selected) View.VISIBLE else View.GONE
        if (row.selected) bindControls(h, it)
    }

    private fun bindControls(h: Holder, it: CartItem) {
        val weighed = it.sellMode == SellMode.WEIGHT
        val counted = !weighed && it.fixedGross == null
        h.minus.visibility = if (counted) View.VISIBLE else View.GONE
        h.plus.visibility = if (counted) View.VISIBLE else View.GONE
        // A label-price line has no quantity: − quantity + all go (Remove fills the line).
        h.qty.visibility = if (it.fixedGross == null) View.VISIBLE else View.GONE
        h.qty.text = if (weighed) "${MoneyFormat.formatQty(it.qty)} ${it.unit ?: "kg"}" else MoneyFormat.formatQty(it.qty)
        // One is the least: taking the last one off is "Remove", never a tap too many on "−".
        val canLower = it.qty > ONE
        h.minus.isEnabled = canLower
        h.minus.alpha = if (canLower) 1f else 0.35f
        h.minus.setOnClickListener { _ -> actions.changeQty(it, -ONE) }
        h.plus.setOnClickListener { _ -> actions.changeQty(it, ONE) }
        h.qty.setOnClickListener { _ -> actions.enterQty(it) }
        h.remove.setOnClickListener { _ -> actions.remove(it) }
    }

    private fun detail(ctx: android.content.Context, it: CartItem, promo: AppliedPromo?): String {
        val price = MoneyFormat.format(it.unitPrice, currency, withSymbol = false)
        val qty = MoneyFormat.formatQty(it.qty)
        val sb = StringBuilder()
        when {
            it.fixedGross != null -> sb.append(ctx.getString(R.string.cart_label_price))
            it.sellMode == SellMode.WEIGHT -> {
                val unit = it.unit ?: "kg"
                sb.append("$qty $unit × $price/$unit")
            }
            else -> sb.append("$qty × $price")
        }
        if (it.priceOverridden) sb.append(" · ").append(ctx.getString(R.string.cart_price_changed))
        when (val d = it.discount) {
            is Discount.Amount -> sb.append(" · -").append(MoneyFormat.format(d.minor, currency, withSymbol = false))
            is Discount.Percent -> sb.append(" · -").append(ReceiptLayout.percent(d.bp))
            Discount.None -> Unit
        }
        if (promo != null) {
            val saving = MoneyFormat.format(promo.discount, currency, withSymbol = false)
            sb.append(" · ").append(promo.name).append(" -").append(saving)
        }
        return sb.toString()
    }

    private companion object {
        const val ONE = 1000L
    }
}

/**
 * How big the catalogue tiles are (Settings → Item tiles, per till; D-066): text sizes, the tile's least
 * height and padding, and the height of a picture (from the column's width, so it keeps its shape).
 */
data class TileSpec(
    val nameSp: Float = 15f,
    val priceSp: Float = 16f,
    val minHeightDp: Int = 88,
    val paddingDp: Int = 10,
    val pictureHeightPx: Int = 0,
)

/**
 * The catalogue's tiles. A product's own colour, else its category's, fills the tile with white text
 * on it, and its picture sits on top (D-066): a cashier finds "the red one with the tin" at a glance.
 */
class ProductTileAdapter(
    private val onClick: (ProductListItem) -> Unit,
    /** Shows picture id (or none) in the view, read off the main thread (PictureCache.show). */
    private val showPicture: (ImageView, Long?) -> Unit,
) : RecyclerView.Adapter<ProductTileAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val picture: ImageView = v.findViewById(R.id.picture)
        val texts: View = v.findViewById(R.id.texts)
        val name: TextView = v.findViewById(R.id.name)
        val price: TextView = v.findViewById(R.id.price)
        val stock: TextView = v.findViewById(R.id.stock)
        val inBill: TextView = v.findViewById(R.id.in_bill)
        val nameColor = name.textColors
        val priceColor = price.textColors
        val stockColor = stock.textColors

        /** The [TileSpec] and colour this tile was last laid out with (built again only when they change). */
        var spec: TileSpec? = null
        var color = -1
    }

    var items: List<ProductListItem> = emptyList()
        private set
    var currency: CurrencySpec = CurrencySpec.MYR

    /** Tile sizes; a change redraws every tile. */
    var spec: TileSpec = TileSpec()
        @SuppressLint("NotifyDataSetChanged") // every tile changes size
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Category id → its colour: the colour of products without one of their own. */
    var categoryColors: Map<Long, Int> = emptyMap()
        @SuppressLint("NotifyDataSetChanged") // a category's colour changed: its tiles
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Product id → quantity on the bill (selling units, milli). */
    private var onBill: Map<Long, Long> = emptyMap()

    /**
     * Marks the tiles of products on the bill; only tiles whose count changed are redrawn, and only
     * their badge ([BADGE]): a whole tile bound again for every tap made fast tapping lag (D-063).
     */
    fun setOnBill(next: Map<Long, Long>) {
        val old = onBill
        if (old == next) return
        onBill = next
        for ((i, p) in items.withIndex()) if (old[p.id] != next[p.id]) notifyItemChanged(i, BADGE)
    }

    /** Pictures may have arrived from another till (sync): the tiles that show one are drawn again ([PICTURE]). */
    fun picturesArrived() {
        for ((i, p) in items.withIndex()) if (p.imageId != null) notifyItemChanged(i, PICTURE)
    }

    @SuppressLint("NotifyDataSetChanged") // a new result set replaces the old one
    fun submit(list: List<ProductListItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun append(more: List<ProductListItem>) {
        if (more.isEmpty()) return
        val start = items.size
        items = items + more
        notifyItemRangeInserted(start, more.size)
    }

    /**
     * Fresh rows ([fresh], by id) for the tiles of [checked]: only tiles that changed are redrawn; a
     * checked product missing from [fresh] (deleted or switched off meanwhile) loses its tile.
     */
    @SuppressLint("NotifyDataSetChanged") // tiles removed: rare
    fun refresh(checked: List<Long>, fresh: Map<Long, ProductListItem>) {
        val old = items
        val gone = checked.filterTo(HashSet()) { it !in fresh }
        if (gone.isEmpty()) {
            val next = old.map { fresh[it.id] ?: it }
            items = next
            for (i in next.indices) if (next[i] != old[i]) notifyItemChanged(i)
        } else {
            items = old.filter { it.id !in gone }.map { fresh[it.id] ?: it }
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_product_tile, parent, false)
        v.clipToOutline = true // the picture keeps the tile's rounded corners
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val p = items[position]
        val ctx = h.itemView.context
        layOut(h)
        paint(h, TileColor.of(p.color, p.categoryId?.let { categoryColors[it] } ?: TileColor.NONE))
        h.name.text = p.name
        val price = MoneyFormat.format(p.price, currency)
        h.price.text = when (p.sellMode) {
            SellMode.WEIGHT -> "$price/${p.unit}"
            SellMode.OPEN_PRICE -> ctx.getString(R.string.tile_open_price)
            else -> price
        }
        val stock = p.stockQty
        h.stock.text = if (stock == null) "" else MoneyFormat.formatQty(stock)
        h.stock.visibility = if (stock == null) View.GONE else View.VISIBLE // its own line: none when not tracked
        showPicture(h.picture, p.imageId)
        bindBadge(h, p)
        h.itemView.setOnClickListener { onClick(p) }
    }

    override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.any { it !== BADGE && it !== PICTURE }) return onBindViewHolder(h, position)
        val p = items[position]
        if (payloads.any { it === BADGE }) bindBadge(h, p)
        if (payloads.any { it === PICTURE }) showPicture(h.picture, p.imageId)
    }

    private fun layOut(h: Holder) {
        val s = spec
        if (h.spec == s) return
        h.spec = s
        val d = h.itemView.resources.displayMetrics.density
        h.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.nameSp)
        h.price.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.priceSp)
        val pad = (s.paddingDp * d).toInt()
        h.texts.setPadding(pad, pad, pad, pad)
        h.itemView.minimumHeight = (s.minHeightDp * d).toInt()
        if (s.pictureHeightPx > 0) h.picture.layoutParams = h.picture.layoutParams.also { it.height = s.pictureHeightPx }
    }

    /** White text on a coloured tile; the plain tile as before. */
    private fun paint(h: Holder, color: Int) {
        if (h.color == color) return
        h.color = color
        val ctx = h.itemView.context
        if (color == TileColor.NONE) {
            h.itemView.setBackgroundResource(R.drawable.tile_bg)
            h.name.setTextColor(h.nameColor)
            h.price.setTextColor(h.priceColor)
            h.stock.setTextColor(h.stockColor)
            h.inBill.setBackgroundResource(R.drawable.count_bg)
            h.inBill.setTextColor(ctx.colorOf(R.color.text_on_brand))
        } else {
            h.itemView.background = TileColors.tileBackground(ctx, color)
            val white = ctx.colorOf(R.color.text_on_brand)
            h.name.setTextColor(white)
            h.price.setTextColor(white)
            h.stock.setTextColor(white)
            h.inBill.setBackgroundResource(R.drawable.count_bg_light)
            h.inBill.setTextColor(ctx.colorOf(R.color.text_primary))
        }
    }

    private fun bindBadge(h: Holder, p: ProductListItem) {
        val q = onBill[p.id]
        h.itemView.isSelected = q != null
        h.inBill.visibility = if (q != null) View.VISIBLE else View.GONE
        if (q != null) h.inBill.text = h.itemView.context.getString(R.string.tile_in_bill, MoneyFormat.formatQty(q))
    }

    private companion object {
        /** Payload: only the "×n on the bill" badge changed. */
        val BADGE = Any()

        /** Payload: only the picture may have changed (it arrived). */
        val PICTURE = Any()
    }
}

class CategoryChipAdapter(private val onClick: (Long) -> Unit) : RecyclerView.Adapter<CategoryChipAdapter.Holder>() {

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private var chips: List<Category> = emptyList()
    var selected: Long = ALL
        @SuppressLint("NotifyDataSetChanged") // selection changes two chips; the list is tiny
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Category>) {
        chips = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = chips.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView)

    override fun onBindViewHolder(h: Holder, position: Int) {
        val c = chips[position]
        h.text.text = c.name
        h.text.isSelected = c.id == selected
        // A category with a colour shows it as a dot: the same colour as its products' tiles (D-066).
        val dot = if (TileColor.known(c.color) == TileColor.NONE) null else TileColors.swatch(h.text.context, c.color, chosen = false).also {
            val size = (12 * h.text.resources.displayMetrics.density).toInt()
            it.setBounds(0, 0, size, size)
        }
        h.text.setCompoundDrawablesRelative(dot, null, null, null)
        h.text.setOnClickListener { onClick(c.id) }
    }

    companion object {
        const val ALL = -1L

        /** Best sellers of the last 30 days (D-049). */
        const val POPULAR = -2L
    }
}
