package com.lekaspos.core.cart

import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.Checked
import com.lekaspos.core.money.Rounding
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PriceLine
import com.lekaspos.core.pricing.PricedCart
import com.lekaspos.core.pricing.PricingEngine

/**
 * One line of the open bill. Quantities are milli-units of the *selling* unit: a carton scanned
 * from a pack barcode is qty 1000 with packQty 24000 (24 pieces), so stock and reports use
 * [baseQty] while the receipt shows "1 × carton".
 */
data class CartItem(
    /** Local line key (also the `cart_line.id`), assigned by the cart session. */
    val key: Long,
    val productId: Long?,
    val name: String,
    val barcode: String? = null,
    val unit: String? = null,
    val categoryId: Long? = null,
    val sellMode: Int = SellMode.UNIT,
    val qty: Long,
    /** Base units (milli) contained in one selling unit; 1000 for single items and weighed goods. */
    val packQty: Long = 1000L,
    /** Price of one selling unit (per kg for weighed goods), minor units. */
    val unitPrice: Long,
    /** Price printed on a price-embedded scale label: the gross is exactly this. */
    val fixedGross: Long? = null,
    val priceOverridden: Boolean = false,
    val discount: Discount = Discount.None,
    val taxRateId: Long? = null,
    val taxBp: Int = 0,
    /** Cost of one *base* unit, minor units. */
    val unitCost: Long = 0L,
    val trackStock: Boolean = true,
    val addedAt: Long = 0L,
) {
    init {
        require(qty > 0L) { "qty must be > 0" }
        require(packQty > 0L) { "pack size must be > 0" }
        require(unitPrice >= 0L) { "price must be >= 0" }
    }

    /** Quantity in the product's base unit (milli), used for stock and quantity reports. */
    val baseQty: Long get() = Rounding.mulDivHalfUp(qty, packQty, 1000L)

    /** Total cost of the line for profit reports. */
    val cost: Long get() = Rounding.mulDivHalfUp(unitCost, baseQty, 1000L)

    val isWeighed: Boolean get() = sellMode == SellMode.WEIGHT

    fun toPriceLine(): PriceLine = PriceLine(
        qty = qty,
        unitPrice = unitPrice,
        fixedGross = fixedGross,
        discount = discount,
        taxRateId = if (taxBp > 0) taxRateId else null,
        taxBp = if (taxRateId != null) taxBp else 0,
    )

    /**
     * Scanning the same plain item again adds to its quantity instead of a new line. Weighed,
     * price-changed, discounted and scale-label lines always stay separate.
     */
    fun canMergeWith(other: CartItem): Boolean =
        productId != null && productId == other.productId && packQty == other.packQty &&
            unitPrice == other.unitPrice && sellMode == SellMode.UNIT && other.sellMode == SellMode.UNIT &&
            fixedGross == null && other.fixedGross == null && !priceOverridden && !other.priceOverridden &&
            discount == Discount.None && other.discount == Discount.None && name == other.name &&
            taxRateId == other.taxRateId && taxBp == other.taxBp
}

/** Result of adding an item: the new cart and the key of the line that was added or grown. */
data class CartAdd(val cart: Cart, val key: Long, val merged: Boolean)

/**
 * The open bill as an immutable value. Every operation returns a new cart; the cart session
 * persists the difference. Totals always come from [PricingEngine].
 */
data class Cart(
    val items: List<CartItem> = emptyList(),
    val billDiscount: Discount = Discount.None,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    fun item(key: Long): CartItem? = items.firstOrNull { it.key == key }

    fun price(pricesIncludeTax: Boolean): PricedCart =
        PricingEngine.price(items.map { it.toPriceLine() }, billDiscount, pricesIncludeTax)

    /** Number of pieces/packs for the "items" count; weighed lines count as one each. */
    val pieceCount: Long
        get() = items.fold(0L) { acc, it -> Checked.add(acc, if (it.isWeighed || it.fixedGross != null) 1L else (it.qty + 999L) / 1000L) }

    /** Adds [item], merging it into the last mergeable line (which keeps its position). */
    fun add(item: CartItem): CartAdd {
        val existing = items.lastOrNull { it.canMergeWith(item) }
        if (existing != null) {
            val grown = existing.copy(qty = Checked.add(existing.qty, item.qty))
            return CartAdd(copy(items = items.map { if (it.key == existing.key) grown else it }), existing.key, merged = true)
        }
        require(items.none { it.key == item.key }) { "duplicate line key ${item.key}" }
        return CartAdd(copy(items = items + item), item.key, merged = false)
    }

    fun setQty(key: Long, qty: Long): Cart = update(key) { it.copy(qty = qty) }

    fun remove(key: Long): Cart = copy(items = items.filterNot { it.key == key })

    fun setDiscount(key: Long, discount: Discount): Cart = update(key) { it.copy(discount = discount) }

    fun overridePrice(key: Long, unitPrice: Long): Cart =
        update(key) { it.copy(unitPrice = unitPrice, fixedGross = null, priceOverridden = true) }

    fun withBillDiscount(discount: Discount): Cart = copy(billDiscount = discount)

    private inline fun update(key: Long, change: (CartItem) -> CartItem): Cart {
        require(items.any { it.key == key }) { "no line $key" }
        return copy(items = items.map { if (it.key == key) change(it) else it })
    }
}
