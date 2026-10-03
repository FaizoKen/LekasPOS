package com.lekaspos.ui.sell

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.AppliedPromo
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PricedCart
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.product.ProductListItem

/** One row of the bill as shown: the item plus its priced amounts. */
data class CartRow(val item: CartItem, val amount: Long, val selected: Boolean, val promo: AppliedPromo? = null)

/** What a tap on a bill line and the buttons of the selected line do. */
interface LineActions {
    fun select(item: CartItem)
    /** − or + : [delta] is added to the quantity the line has now. */
    fun changeQty(item: CartItem, delta: Long)
    fun enterQty(item: CartItem)
    fun more(item: CartItem)
    fun remove(item: CartItem)
}

/**
 * The bill. The selected line (the one just scanned, or tapped) shows its buttons in place —
 * remove, −, quantity, +, more — so changing a count is one tap, not a dialog (D-049).
 */
class CartAdapter(private val actions: LineActions) : RecyclerView.Adapter<CartAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
        val amount: TextView = v.findViewById(R.id.amount)
        val controls: View = v.findViewById(R.id.controls)
        val qtyRow: View = v.findViewById(R.id.line_qty_row)
        val remove: View = v.findViewById(R.id.line_remove)
        val minus: View = v.findViewById(R.id.line_minus)
        val qty: TextView = v.findViewById(R.id.line_qty)
        val plus: View = v.findViewById(R.id.line_plus)
        val more: View = v.findViewById(R.id.line_more)
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
        // A label-price line has no quantity: its whole − quantity + row goes, not an empty row.
        h.qtyRow.visibility = if (it.fixedGross == null) View.VISIBLE else View.GONE
        h.qty.text = if (weighed) "${MoneyFormat.formatQty(it.qty)} ${it.unit ?: "kg"}" else MoneyFormat.formatQty(it.qty)
        // One is the least: taking the last one off is "Remove", never a tap too many on "−".
        val canLower = it.qty > ONE
        h.minus.isEnabled = canLower
        h.minus.alpha = if (canLower) 1f else 0.35f
        h.minus.setOnClickListener { _ -> actions.changeQty(it, -ONE) }
        h.plus.setOnClickListener { _ -> actions.changeQty(it, ONE) }
        h.qty.setOnClickListener { _ -> actions.enterQty(it) }
        h.remove.setOnClickListener { _ -> actions.remove(it) }
        h.more.setOnClickListener { _ -> actions.more(it) }
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

class ProductTileAdapter(private val onClick: (ProductListItem) -> Unit) : RecyclerView.Adapter<ProductTileAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val price: TextView = v.findViewById(R.id.price)
        val stock: TextView = v.findViewById(R.id.stock)
        val inBill: TextView = v.findViewById(R.id.in_bill)
    }

    var items: List<ProductListItem> = emptyList()
        private set
    var currency: CurrencySpec = CurrencySpec.MYR

    /** Product id → quantity on the bill (selling units, milli). */
    private var onBill: Map<Long, Long> = emptyMap()

    /** Marks the tiles of products on the bill; only tiles whose count changed are redrawn. */
    fun setOnBill(next: Map<Long, Long>) {
        val old = onBill
        if (old == next) return
        onBill = next
        for ((i, p) in items.withIndex()) if (old[p.id] != next[p.id]) notifyItemChanged(i)
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

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_product_tile, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val p = items[position]
        h.name.text = p.name
        val price = MoneyFormat.format(p.price, currency)
        h.price.text = when (p.sellMode) {
            SellMode.WEIGHT -> "$price/${p.unit}"
            SellMode.OPEN_PRICE -> h.itemView.context.getString(R.string.tile_open_price)
            else -> price
        }
        val stock = p.stockQty
        h.stock.text = if (stock == null) "" else MoneyFormat.formatQty(stock)
        h.stock.visibility = if (stock == null) View.GONE else View.VISIBLE // its own line: none when not tracked
        val q = onBill[p.id]
        h.itemView.isSelected = q != null
        h.inBill.visibility = if (q != null) View.VISIBLE else View.GONE
        if (q != null) h.inBill.text = h.itemView.context.getString(R.string.tile_in_bill, MoneyFormat.formatQty(q))
        h.itemView.setOnClickListener { onClick(p) }
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
        h.text.setOnClickListener { onClick(c.id) }
    }

    companion object {
        const val ALL = -1L

        /** Best sellers of the last 30 days (D-049). */
        const val POPULAR = -2L
    }
}
