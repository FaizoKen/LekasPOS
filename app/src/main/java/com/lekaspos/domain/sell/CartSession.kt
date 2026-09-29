package com.lekaspos.domain.sell

import com.lekaspos.app.AppGraph
import com.lekaspos.core.cart.Cart
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.CartStatus
import com.lekaspos.core.model.DiscountKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PricedCart
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.cart.CartDao
import com.lekaspos.data.cart.CartLine
import com.lekaspos.data.cart.StoredCart
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.product.ScanHit
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.domain.Approval
import com.lekaspos.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The open bill (references/architecture.md §4). App-scoped, so rotating the screen or
 * opening another screen never loses it. Every change updates memory first (the cart shows
 * it at once) and is then written to `cart`/`cart_line` by one background writer, in order;
 * after a crash or kill, [load] restores the bill.
 *
 * Mutating functions must be called on the main thread.
 */
class CartSession(private val graph: AppGraph) {

    data class State(
        val loaded: Boolean = false,
        /** 0 = the bill has no row yet (created with its first line). */
        val cartId: Long = 0L,
        val openedAt: Long = 0L,
        val cart: Cart = Cart(),
        val priced: PricedCart = PricedCart.EMPTY,
        /** Line added or changed last: the list scrolls to it. */
        val lastKey: Long = 0L,
        val heldCount: Int = 0,
        /** A checkout or resume is running; the bill cannot change meanwhile. */
        val busy: Boolean = false,
        /** The payment dialog is open: the bill is frozen until it is paid or cancelled. */
        val paying: Boolean = false,
        /** The customer the bill is for (customers & credit, D-039). */
        val customerId: Long? = null,
        val customerName: String? = null,
    ) {
        val canEdit: Boolean get() = loaded && !busy && !paying
    }

    data class HeldBill(val id: Long, val label: String?, val updatedAt: Long, val lines: Int, val total: Long)

    sealed class ScanResult {
        data class Added(val key: Long, val name: String) : ScanResult()
        data class NeedsWeight(val product: SellableProduct, val code: String) : ScanResult()
        data class NeedsPrice(val product: SellableProduct, val code: String) : ScanResult()
        data class NotFound(val code: String) : ScanResult()
        object Busy : ScanResult()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var nextCartId = 1L
    private var nextLineId = 1L
    private var nextLineNo = 1
    private val lineNos = HashMap<Long, Int>()

    private class Op(val ids: Long, val run: ((Db.Tx) -> Unit)?, val done: CompletableDeferred<Unit>? = null)

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val loadLock = Mutex()

    /** Restores the open bill once per process. */
    suspend fun load() = loadLock.withLock {
        if (_state.value.loaded) return@withLock
        graph.settings.load()
        val db = graph.db()
        val stored = db.read { r -> Triple(CartDao.loadOpen(r), CartDao.heldCount(r), CartDao.maxIds(r)) }
        val customer = stored.first?.customerId?.let { id -> db.read { CustomerDao.name(it, id) } }
        nextCartId = stored.third.first + 1L
        nextLineId = stored.third.second + 1L
        startWriter(db)
        val base = State(loaded = true, heldCount = stored.second)
        _state.value = stored.first?.let { fromStored(it, base, customer) } ?: base
    }

    // ------------------------------------------------------------------ adding items

    /** Resolves a scanned code and adds the item when no further input is needed. */
    suspend fun scan(code: String): ScanResult {
        if (!_state.value.canEdit) return ScanResult.Busy
        val templates = graph.settings.store.value.templates()
        val res = graph.db().read { r -> BarcodeLookup.resolve(r, code, templates) }
        return when (res) {
            is Resolution.NotFound -> ScanResult.NotFound(res.code)
            is Resolution.Scale -> added(add(itemForLabel(res)))
            is Resolution.Plain -> {
                val p = res.hit.product
                when {
                    p.sellMode == SellMode.WEIGHT && res.hit.packQty == 1000L && res.hit.packPrice == null ->
                        ScanResult.NeedsWeight(p, res.hit.code)
                    p.sellMode == SellMode.OPEN_PRICE -> ScanResult.NeedsPrice(p, res.hit.code)
                    else -> added(add(itemFor(res.hit)))
                }
            }
        }
    }

    private fun added(key: Long): ScanResult {
        val item = _state.value.cart.item(key) ?: return ScanResult.Busy
        return ScanResult.Added(key, item.name)
    }

    /** One unit of a product tapped in the catalogue. */
    fun addProduct(p: SellableProduct, qty: Long = 1000L, barcode: String? = null): Long = add(
        CartItem(
            key = 0L, productId = p.id, name = p.name, barcode = barcode, unit = p.unit, categoryId = p.categoryId,
            sellMode = if (p.sellMode == SellMode.WEIGHT) SellMode.WEIGHT else SellMode.UNIT, qty = qty,
            unitPrice = p.price, taxRateId = p.taxRateId, taxBp = p.taxBp, unitCost = p.cost, trackStock = p.trackStock,
        ),
    )

    /** An open-price product sold at [price]. */
    fun addAtPrice(p: SellableProduct, price: Long, barcode: String? = null): Long = add(
        CartItem(
            key = 0L, productId = p.id, name = p.name, barcode = barcode, unit = p.unit, categoryId = p.categoryId,
            qty = 1000L, unitPrice = price, taxRateId = p.taxRateId, taxBp = p.taxBp, unitCost = p.cost,
            trackStock = p.trackStock,
        ),
    )

    /** An item that is not in the catalogue (unknown barcode sold anyway, "misc" item). */
    fun addCustom(name: String, price: Long, barcode: String?): Long = add(
        CartItem(key = 0L, productId = null, name = name, barcode = barcode, qty = 1000L, unitPrice = price, trackStock = false),
    )

    /** Adds [template] (its key is assigned here). Returns the key of the new or grown line, 0 if busy. */
    fun add(template: CartItem): Long {
        val st = _state.value
        if (!st.canEdit) return 0L
        val now = System.currentTimeMillis()
        // One rate, one percentage per bill: if a tax rate changed since earlier lines were
        // added, the new percentage applies to the whole bill (the pricing engine requires it).
        var cart = st.cart
        val retaxed = ArrayList<CartItem>()
        if (template.taxRateId != null) {
            cart = cart.copy(
                items = cart.items.map {
                    if (it.taxRateId == template.taxRateId && it.taxBp != template.taxBp) {
                        it.copy(taxBp = template.taxBp).also { changed -> retaxed.add(changed) }
                    } else {
                        it
                    }
                },
            )
        }
        val key = nextLineId
        val r = cart.add(template.copy(key = key, addedAt = now))
        if (!r.merged) {
            nextLineId++
            lineNos[key] = nextLineNo++
        }
        val line = r.cart.item(r.key) ?: return 0L
        val rows = (retaxed.filter { it.key != line.key } + line).map { it.toLine(lineNo(it.key)) }
        commit(r.cart, r.key) { tx, cartId -> for (row in rows) CartDao.putLine(tx, cartId, row, now) }
        return r.key
    }

    fun itemFor(hit: ScanHit): CartItem {
        val p = hit.product
        val pack = hit.packQty != 1000L
        return CartItem(
            key = 0L, productId = p.id, name = if (pack) "${p.name} x${MoneyFormat.formatQty(hit.packQty)}" else p.name,
            barcode = hit.code, unit = p.unit, categoryId = p.categoryId, qty = 1000L, packQty = hit.packQty,
            unitPrice = BarcodeLookup.unitPrice(hit), taxRateId = p.taxRateId, taxBp = p.taxBp, unitCost = p.cost,
            trackStock = p.trackStock,
        )
    }

    private fun itemForLabel(res: Resolution.Scale): CartItem {
        val p = res.product
        val price = res.label.priceMinor
        return if (price != null) {
            CartItem(
                key = 0L, productId = p.id, name = p.name, barcode = res.code, unit = p.unit, categoryId = p.categoryId,
                sellMode = p.sellMode, qty = BarcodeLookup.labelQty(p, price), unitPrice = p.price, fixedGross = price,
                taxRateId = p.taxRateId, taxBp = p.taxBp, unitCost = p.cost, trackStock = p.trackStock,
            )
        } else {
            CartItem(
                key = 0L, productId = p.id, name = p.name, barcode = res.code, unit = p.unit, categoryId = p.categoryId,
                sellMode = SellMode.WEIGHT, qty = (res.label.weightMilli ?: 0L).coerceAtLeast(1L), unitPrice = p.price,
                taxRateId = p.taxRateId, taxBp = p.taxBp, unitCost = p.cost, trackStock = p.trackStock,
            )
        }
    }

    // ------------------------------------------------------------------ editing lines

    fun setQty(key: Long, qty: Long) {
        val st = _state.value
        if (!st.canEdit || st.cart.item(key) == null) return
        if (qty <= 0L) return remove(key)
        val cart = st.cart.setQty(key, qty)
        putLine(cart, key)
    }

    fun remove(key: Long) {
        val st = _state.value
        if (!st.canEdit || st.cart.item(key) == null) return
        lineNos.remove(key)
        val now = System.currentTimeMillis()
        commit(st.cart.remove(key), st.cart.items.lastOrNull { it.key != key }?.key ?: 0L) { tx, cartId ->
            CartDao.deleteLine(tx, cartId, key, now)
        }
    }

    /** Line discount (permission or a manager's [approval], audited). Returns false when not allowed. */
    fun setLineDiscount(key: Long, d: Discount, approval: Approval? = null): Boolean {
        val st = _state.value
        val item = st.cart.item(key) ?: return false
        if (!st.canEdit) return false
        val actor = graph.permissions.actorOrNull(Perm.DISCOUNT, approval) ?: return false
        val detail = "${item.name}: ${describe(d)}"
        putLine(st.cart.setDiscount(key, d), key) { tx, now ->
            AuditDao.log(tx, AuditAction.LINE_DISCOUNT, actor.staffId, now, Entity.PRODUCT, item.productId, amountOf(d), detail, actor.approvedBy)
        }
        return true
    }

    /** Price override (permission or a manager's [approval], audited). Returns false when not allowed. */
    fun overridePrice(key: Long, unitPrice: Long, approval: Approval? = null): Boolean {
        val st = _state.value
        val item = st.cart.item(key) ?: return false
        if (!st.canEdit || unitPrice < 0L) return false
        val actor = graph.permissions.actorOrNull(Perm.PRICE_OVERRIDE, approval) ?: return false
        val c = graph.settings.store.value.currency
        val detail = "${item.name}: ${MoneyFormat.format(item.unitPrice, c)} -> ${MoneyFormat.format(unitPrice, c)}"
        putLine(st.cart.overridePrice(key, unitPrice), key) { tx, now ->
            AuditDao.log(tx, AuditAction.PRICE_OVERRIDE, actor.staffId, now, Entity.PRODUCT, item.productId, unitPrice, detail, actor.approvedBy)
        }
        return true
    }

    /** Bill discount (permission or a manager's [approval], audited). Returns false when not allowed. */
    fun setBillDiscount(d: Discount, approval: Approval? = null): Boolean {
        val st = _state.value
        if (!st.canEdit || st.cart.isEmpty) return false
        val actor = graph.permissions.actorOrNull(Perm.DISCOUNT, approval) ?: return false
        val now = System.currentTimeMillis()
        val (kind, value) = discountColumns(d)
        val detail = describe(d)
        commit(st.cart.withBillDiscount(d), st.lastKey, ids = 1L) { tx, cartId ->
            CartDao.setBillDiscount(tx, cartId, kind, value, now)
            AuditDao.log(tx, AuditAction.BILL_DISCOUNT, actor.staffId, now, amount = amountOf(d), detail = detail, approvedBy = actor.approvedBy)
        }
        return true
    }

    /** Cancels the whole bill (audited when it had lines). Returns false when not allowed. */
    fun clear(approval: Approval? = null): Boolean {
        val st = _state.value
        if (!st.canEdit) return false
        if (st.cart.isEmpty && st.cartId == 0L) return true
        val actor = if (st.cart.isEmpty) null else graph.permissions.actorOrNull(Perm.CANCEL_BILL, approval) ?: return false
        val cartId = st.cartId
        val now = System.currentTimeMillis()
        val total = st.priced.total
        val lines = st.cart.items.size
        if (cartId != 0L) {
            enqueue(if (lines > 0) 1L else 0L) { tx ->
                CartDao.deleteCart(tx, cartId)
                if (lines > 0) {
                    AuditDao.log(
                        tx, AuditAction.BILL_CANCEL, actor?.staffId ?: graph.staff.staffId, now, amount = total, detail = "$lines lines",
                        approvedBy = actor?.approvedBy,
                    )
                }
            }
        }
        resetEmpty(st.heldCount)
        return true
    }

    /** Sets (or with null, removes) the customer the bill is for. Returns false while the bill is frozen. */
    fun setCustomer(customerId: Long?, name: String?): Boolean {
        val st = _state.value
        if (!st.canEdit) return false
        if (st.customerId == customerId) return true
        val now = System.currentTimeMillis()
        commit(st.cart, st.lastKey) { tx, cartId -> CartDao.setCustomer(tx, cartId, customerId, now) }
        _state.value = _state.value.copy(customerId = customerId, customerName = if (customerId == null) null else name)
        return true
    }

    // ------------------------------------------------------------------ held (parked) bills

    suspend fun hold(label: String?): Boolean {
        val st = _state.value
        if (!st.canEdit || st.cart.isEmpty || st.cartId == 0L) return false
        val cartId = st.cartId
        val now = System.currentTimeMillis()
        enqueue { tx -> CartDao.setStatus(tx, cartId, CartStatus.HELD, label?.takeIf { it.isNotBlank() }, now) }
        resetEmpty(st.heldCount + 1)
        flush()
        return true
    }

    suspend fun heldBills(): List<HeldBill> {
        flush()
        val inclTax = graph.settings.store.value.pricesIncludeTax
        return graph.db().read { r ->
            CartDao.held(r).map { row ->
                val total = CartDao.load(r, row.id)?.let { toCart(it).price(inclTax).total } ?: 0L
                HeldBill(row.id, row.label, row.updatedAt, row.lines, total)
            }
        }
    }

    /** Makes held bill [heldId] the open bill; the current bill (if any) is held in its place. */
    suspend fun resume(heldId: Long): Boolean {
        val st = _state.value
        if (!st.canEdit || heldId == st.cartId) return false
        val now = System.currentTimeMillis()
        val current = st.cartId
        if (current != 0L) {
            if (st.cart.isEmpty) {
                enqueue { tx -> CartDao.deleteCart(tx, current) }
            } else {
                enqueue { tx -> CartDao.setStatus(tx, current, CartStatus.HELD, null, now) }
            }
        }
        enqueue { tx -> CartDao.setStatus(tx, heldId, CartStatus.OPEN, null, now) }
        _state.value = st.copy(busy = true)
        try {
            flush()
            val loaded = graph.db().read { r -> CartDao.load(r, heldId) to CartDao.heldCount(r) }
            val customer = loaded.first?.customerId?.let { id -> graph.db().read { CustomerDao.name(it, id) } }
            val base = State(loaded = true, heldCount = loaded.second)
            _state.value = loaded.first?.let { fromStored(it, base, customer) } ?: base
            return loaded.first != null
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            throw e
        }
    }

    suspend fun deleteHeld(heldId: Long) {
        enqueue { tx -> CartDao.deleteCart(tx, heldId) }
        flush()
        val held = graph.db().read { CartDao.heldCount(it) }
        _state.value = _state.value.copy(heldCount = held)
    }

    // ------------------------------------------------------------------ checkout

    /**
     * Runs [block] (the sale commit) in one write transaction after every pending cart write,
     * deletes the bill in the same transaction, then starts a new empty bill. If [block]
     * throws, nothing is written and the bill stays as it was.
     */
    suspend fun <T> checkout(reserveIds: Long, block: (Db.Tx, State) -> T): T {
        val st = _state.value
        check(st.loaded && !st.busy && !st.cart.isEmpty) { "nothing to check out" }
        _state.value = st.copy(busy = true)
        try {
            flush()
            val cartId = st.cartId
            val result = graph.db().write(reserveIds) { tx ->
                val r = block(tx, st)
                if (cartId != 0L) CartDao.deleteCart(tx, cartId)
                r
            }
            resetEmpty(st.heldCount)
            return result
        } catch (e: Throwable) {
            _state.value = _state.value.copy(busy = false)
            throw e
        }
    }

    /** Freezes the bill while the payment dialog is open (scans and edits are refused). */
    fun setPaying(on: Boolean) {
        val st = _state.value
        if (st.paying != on) _state.value = st.copy(paying = on)
    }

    /** Waits until every change made so far is committed. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        ops.send(Op(0L, null, done))
        done.await()
    }

    // ------------------------------------------------------------------ internals

    private fun putLine(cart: Cart, key: Long, audit: ((Db.Tx, Long) -> Unit)? = null) {
        val row = cart.item(key)?.toLine(lineNo(key)) ?: return
        val now = System.currentTimeMillis()
        commit(cart, key, ids = if (audit != null) 1L else 0L) { tx, cartId ->
            CartDao.putLine(tx, cartId, row, now)
            audit?.invoke(tx, now)
        }
    }

    /** Publishes [cart] and queues its persistence (creating the bill's row first if needed). */
    private fun commit(cart: Cart, lastKey: Long, ids: Long = 0L, persist: (Db.Tx, Long) -> Unit) {
        val st = _state.value
        val now = System.currentTimeMillis()
        var cartId = st.cartId
        var openedAt = st.openedAt
        if (cartId == 0L) {
            cartId = nextCartId++
            openedAt = now
            val id = cartId
            val staff = graph.staff.staffId
            enqueue { tx -> CartDao.insertCart(tx, id, staff, now, now) }
        }
        val id = cartId
        enqueue(ids) { tx -> persist(tx, id) }
        _state.value = st.copy(cartId = cartId, openedAt = openedAt, cart = cart, priced = price(cart), lastKey = lastKey)
    }

    private fun enqueue(ids: Long = 0L, run: (Db.Tx) -> Unit) {
        ops.trySend(Op(ids, run))
    }

    private fun resetEmpty(heldCount: Int) {
        lineNos.clear()
        nextLineNo = 1
        _state.value = State(loaded = true, heldCount = heldCount)
    }

    private fun startWriter(db: Db) {
        graph.appScope.launch {
            val batch = ArrayList<Op>()
            for (first in ops) {
                batch.clear()
                batch.add(first)
                while (true) batch.add(ops.tryReceive().getOrNull() ?: break)
                try {
                    if (batch.any { it.run != null }) {
                        db.write(reserveIds = batch.sumOf { it.ids }) { tx -> for (op in batch) op.run?.invoke(tx) }
                    }
                    for (op in batch) op.done?.complete(Unit)
                } catch (e: CancellationException) {
                    throw e // the app scope ends only in tests
                } catch (e: Exception) {
                    Log.e("Saving the open bill failed", e)
                    for (op in batch) op.done?.completeExceptionally(e)
                }
            }
        }
    }

    private fun price(cart: Cart): PricedCart = cart.price(graph.settings.store.value.pricesIncludeTax)

    private fun lineNo(key: Long): Int = lineNos[key] ?: nextLineNo.also {
        lineNos[key] = it
        nextLineNo++
    }

    private fun fromStored(s: StoredCart, base: State, customerName: String?): State {
        lineNos.clear()
        nextLineNo = 1
        for (l in s.lines) {
            lineNos[l.id] = l.lineNo
            if (l.lineNo >= nextLineNo) nextLineNo = l.lineNo + 1
            if (l.id >= nextLineId) nextLineId = l.id + 1L
        }
        val cart = toCart(s)
        return base.copy(
            cartId = s.id, openedAt = s.openedAt, cart = cart, priced = price(cart), lastKey = cart.items.lastOrNull()?.key ?: 0L,
            customerId = s.customerId, customerName = customerName,
        )
    }

    private fun toCart(s: StoredCart): Cart {
        val items = ArrayList<CartItem>(s.lines.size)
        val bpByRate = HashMap<Long, Int>()
        for (l in s.lines) {
            try {
                items.add(l.toItem())
                l.taxRateId?.let { bpByRate[it] = l.taxBp } // the latest line's percentage wins
            } catch (e: IllegalArgumentException) {
                Log.w("Skipping an invalid bill line ${l.id}", e)
            }
        }
        val harmonized = items.map { i -> i.taxRateId?.let { bpByRate[it] }?.let { if (it != i.taxBp) i.copy(taxBp = it) else i } ?: i }
        return Cart(items = harmonized, billDiscount = discountOf(s.billDiscKind, s.billDiscValue))
    }

    private fun describe(d: Discount): String = when (d) {
        Discount.None -> "none"
        is Discount.Amount -> MoneyFormat.format(d.minor, graph.settings.store.value.currency)
        is Discount.Percent -> ReceiptLayout.percent(d.bp)
    }

    companion object {
        fun discountOf(kind: Int, value: Long): Discount = when (kind) {
            DiscountKind.AMOUNT -> if (value > 0L) Discount.Amount(value) else Discount.None
            DiscountKind.PERCENT -> if (value in 1L..10_000L) Discount.Percent(value.toInt()) else Discount.None
            else -> Discount.None
        }

        fun discountColumns(d: Discount): Pair<Int, Long> = when (d) {
            Discount.None -> DiscountKind.NONE to 0L
            is Discount.Amount -> DiscountKind.AMOUNT to d.minor
            is Discount.Percent -> DiscountKind.PERCENT to d.bp.toLong()
        }

        private fun amountOf(d: Discount): Long? = when (d) {
            Discount.None -> 0L
            is Discount.Amount -> d.minor
            is Discount.Percent -> d.bp.toLong()
        }

        fun CartItem.toLine(lineNo: Int): CartLine {
            val (kind, value) = discountColumns(discount)
            return CartLine(
                id = key, lineNo = lineNo, productId = productId, name = name, barcode = barcode, unit = unit,
                categoryId = categoryId, sellMode = sellMode, qty = qty, packQty = packQty, baseQty = baseQty,
                unitPrice = unitPrice, fixedGross = fixedGross, priceOverridden = priceOverridden, discKind = kind,
                discValue = value, taxRateId = taxRateId, taxBp = taxBp, unitCost = unitCost, trackStock = trackStock,
                addedAt = addedAt,
            )
        }

        fun CartLine.toItem(): CartItem = CartItem(
            key = id, productId = productId, name = name, barcode = barcode, unit = unit, categoryId = categoryId,
            sellMode = sellMode, qty = qty, packQty = packQty, unitPrice = unitPrice, fixedGross = fixedGross,
            priceOverridden = priceOverridden, discount = discountOf(discKind, discValue), taxRateId = taxRateId,
            taxBp = taxBp, unitCost = unitCost, trackStock = trackStock, addedAt = addedAt,
        )
    }
}
