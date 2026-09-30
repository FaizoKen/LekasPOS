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
data class CartRow(val item: CartItem, val amount: Long, val highlighted: Boolean, val promo: AppliedPromo? = null)

class CartAdapter(private val onClick: (CartItem) -> Unit) : RecyclerView.Adapter<CartAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
        val amount: TextView = v.findViewById(R.id.amount)
    }

    private var rows: List<CartRow> = emptyList()
    var currency: CurrencySpec = CurrencySpec.MYR

    fun submit(items: List<CartItem>, priced: PricedCart, lastKey: Long) {
        val next = items.mapIndexed { i, it ->
            val pl = priced.lines.getOrNull(i)
            val amount = if (pl != null) pl.gross - pl.lineDiscount else 0L
            CartRow(it, amount, it.key == lastKey, priced.promotions.getOrNull(i))
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
        h.itemView.setBackgroundResource(if (row.highlighted) R.drawable.row_highlight else R.drawable.row_ripple)
        h.itemView.setOnClickListener { _ -> onClick(it) }
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
}

class ProductTileAdapter(private val onClick: (ProductListItem) -> Unit) : RecyclerView.Adapter<ProductTileAdapter.Holder>() {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val price: TextView = v.findViewById(R.id.price)
        val stock: TextView = v.findViewById(R.id.stock)
    }

    var items: List<ProductListItem> = emptyList()
        private set
    var currency: CurrencySpec = CurrencySpec.MYR

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
    }
}
