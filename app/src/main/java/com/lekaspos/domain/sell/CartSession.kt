package com.lekaspos.domain.sell

import android.database.sqlite.SQLiteDatabase
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
import com.lekaspos.core.pricing.Promotion
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.cart.CartDao
import com.lekaspos.data.cart.CartLine
import com.lekaspos.data.cart.StoredCart
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.customer.CustomerDao
import com.lekaspos.data.db.Db
import com.lekaspos.data.product.ScanHit
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.domain.Approval
import com.lekaspos.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /**
     * The payment being entered while [State.paying]: what was already taken, and the managers'
     * approvals for it. It outlives the selling screen, so a screen rebuilt mid-payment (a tablet
     * turned, dark mode) shows the payment again instead of dropping a split payment half entered
     * (2026-10 review). Main thread only; forgotten when paying ends ([setPaying]).
     */
    class PaymentDraft(val total: Long, val methods: List<PaymentMethod>) {
        var tenders: List<Tender> = emptyList()
        val approvals = ArrayList<Approval>()

        /** Credit already allowed for the customer in this payment (limit checks). */
        var creditTaken = 0L
    }

    var payment: PaymentDraft? = null

    /**
     * A weight or price being asked for a product scanned or tapped (the scanner already beeped "ok"):
     * asked again when the selling screen is rebuilt (the phone turned) instead of being lost with the
     * dialog (2026-10 review). Main thread.
     */
    data class Prompt(val product: SellableProduct, val code: String?, val weight: Boolean)

    var prompt: Prompt? = null

    /** [repair]: a whole-bill rewrite (see [repair]); its own failure is not repaired at once again. */
    private class Op(val ids: Long, val run: ((Db.Tx) -> Unit)?, val done: CompletableDeferred<Unit>? = null, val repair: Boolean = false)

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val loadLock = Mutex()
    private var writerStarted = false

    /**
     * Set by the writer when a write failed (its changes are lost): the bill is then written whole
     * again before the next change, so one lost write (say the bill's own row) cannot make every
     * later line write fail too (2026-10 review).
     */
    @Volatile
    private var repairNeeded = false

    /** Restores the open bill once per process. */
    suspend fun load() = loadLock.withLock {
        if (_state.value.loaded) return@withLock
        graph.settings.load()
        graph.promotions.load()
        val db = graph.db()
        val stored = db.read { r -> Triple(CartDao.loadOpen(r), CartDao.heldCount(r), CartDao.maxIds(r)) }
        val customer = stored.first?.customerId?.let { id -> db.read { billCustomer(it, id) } }
        nextCartId = stored.third.first + 1L
        nextLineId = stored.third.second + 1L
        if (!writerStarted) {
            writerStarted = true
            startWriter(db)
        }
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
            is Resolution.Scale -> {
                val l = res.label
                val p = res.product
                if (l.priceMinor == 0L || (l.priceMinor == null && (l.weightMilli ?: 0L) <= 0L)) {
                    // A label printed with no weight or a price of 0.00 (a scale fault, a test label) is
                    // weighed or priced by the cashier: it went on the bill as 0.001 kg, or free, with
                    // the "ok" beep (2026-10 review).
                    if (p.sellMode == SellMode.WEIGHT && p.price > 0L) ScanResult.NeedsWeight(p, res.code) else ScanResult.NeedsPrice(p, res.code)
                } else {
                    added(add(itemForLabel(res)))
                }
            }
            is Resolution.Plain -> {
                val p = res.hit.product
                when {
                    p.sellMode == SellMode.WEIGHT && res.hit.packQty == 1000L && res.hit.packPrice == null ->
                        ScanResult.NeedsWeight(p, res.hit.code)
                    p.sellMode == SellMode.OPEN_PRICE -> ScanResult.NeedsPrice(p, res.hit.code)
                    // No price yet (a product added in a hurry): the price is asked, it never sells free
                    // with the normal beep (2026-10 review).
                    p.sellMode == SellMode.UNIT && p.price == 0L && res.hit.packQty == 1000L && res.hit.packPrice == null ->
                        ScanResult.NeedsPrice(p, res.hit.code)
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
        val r = try {
            cart.add(template.copy(key = key, addedAt = now))
        } catch (e: ArithmeticException) {
            return 0L
        }
        val line = r.cart.item(r.key) ?: return 0L
        if (line.qty > MAX_QTY) return 0L
        if (!r.merged) lineNos[key] = nextLineNo
        val rows = try {
            (retaxed.filter { it.key != line.key } + line).map { it.toLine(lineNo(it.key)) }
        } catch (e: ArithmeticException) {
            null
        }
        if (rows == null || !commit(r.cart, r.key) { tx, cartId -> for (row in rows) CartDao.putLine(tx, cartId, row, now) }) {
            if (!r.merged) lineNos.remove(key)
            return 0L
        }
        if (!r.merged) {
            nextLineId++
            nextLineNo++
        }
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

    /** Sets a line's quantity; false when it is refused (bill frozen, or more than [MAX_QTY]). */
    fun setQty(key: Long, qty: Long): Boolean {
        val st = _state.value
        if (!st.canEdit || st.cart.item(key) == null || qty > MAX_QTY) return false
        if (qty <= 0L) {
            remove(key)
            return true
        }
        return putLine(st.cart.setQty(key, qty), key)
    }

    /**
     * The − and + buttons: changes the quantity the line has *now* by [delta]. (Taps come faster
     * than the list redraws, so a button must never carry the quantity it was drawn with.)
     */
    fun changeQty(key: Long, delta: Long) {
        val item = _state.value.cart.item(key) ?: return
        val next = item.qty + delta
        if (next > 0L) setQty(key, next)
    }

    fun remove(key: Long) {
        val st = _state.value
        val item = st.cart.item(key)
        if (!st.canEdit || item == null) return
        lineNos.remove(key)
        val now = System.currentTimeMillis()
        val left = st.cart.remove(key)
        // The last line gone ends the bill as "Cancel bill" does, so it is in the audit log the same
        // way: a bill emptied line by line is never invisible. No PIN is asked: taking a wrong item
        // off stays one tap (2026-10 review).
        val emptied = left.isEmpty
        val staffId = graph.staff.staffId
        val total = st.priced.total
        if (emptied && st.customerId == null) {
            // The last line is gone: the bill ends here, its discount with it.
            val cartId = st.cartId
            if (cartId != 0L) {
                enqueue(1L) { tx ->
                    CartDao.deleteCart(tx, cartId)
                    logEmptied(tx, staffId, now, total, item.name)
                }
            }
            resetEmpty(st.heldCount)
            return
        }
        // An empty bill keeps its customer (shown on the screen) but never a bill discount, which
        // an empty bill does not show: it would have gone to the next customer's first item.
        val dropDiscount = emptied && left.billDiscount != Discount.None
        val next = if (dropDiscount) left.withBillDiscount(Discount.None) else left
        // No line is selected after a removal: the line before took the selection, and on a long bill its
        // Remove button settled right under the finger — a double tap removed two items (2026-10 review).
        commit(next, 0L, ids = if (emptied) 1L else 0L) { tx, cartId ->
            CartDao.deleteLine(tx, cartId, key, now)
            if (dropDiscount) CartDao.setBillDiscount(tx, cartId, DiscountKind.NONE, 0L, now)
            if (emptied) logEmptied(tx, staffId, now, total, item.name)
        }
    }

    private fun logEmptied(tx: Db.Tx, staffId: Long, at: Long, total: Long, lastLine: String) {
        AuditDao.log(tx, AuditAction.BILL_CANCEL, staffId, at, amount = total, detail = "last line removed: $lastLine")
    }

    /** Line discount (permission or a manager's [approval], audited). Returns false when not allowed. */
    fun setLineDiscount(key: Long, d: Discount, approval: Approval? = null): Boolean {
        val st = _state.value
        val item = st.cart.item(key) ?: return false
        if (!st.canEdit) return false
        val actor = graph.permissions.actorOrNull(Perm.DISCOUNT, approval) ?: return false
        val detail = "${item.name}: ${describe(d)}"
        return putLine(st.cart.setDiscount(key, d), key) { tx, now ->
            AuditDao.log(tx, AuditAction.LINE_DISCOUNT, actor.staffId, now, Entity.PRODUCT, item.productId, amountOf(d), detail, actor.approvedBy)
        }
    }

    /** Price override (permission or a manager's [approval], audited). Returns false when not allowed. */
    fun overridePrice(key: Long, unitPrice: Long, approval: Approval? = null): Boolean {
        val st = _state.value
        val item = st.cart.item(key) ?: return false
        if (!st.canEdit || unitPrice < 0L) return false
        val actor = graph.permissions.actorOrNull(Perm.PRICE_OVERRIDE, approval) ?: return false
        val c = graph.settings.store.value.currency
        val detail = "${item.name}: ${MoneyFormat.format(item.unitPrice, c)} -> ${MoneyFormat.format(unitPrice, c)}"
        return putLine(st.cart.overridePrice(key, unitPrice), key) { tx, now ->
            AuditDao.log(tx, AuditAction.PRICE_OVERRIDE, actor.staffId, now, Entity.PRODUCT, item.productId, unitPrice, detail, actor.approvedBy)
        }
    }

    /** Bill discount (permission or a manager's [approval], audited). Returns false when not allowed. */
    fun setBillDiscount(d: Discount, approval: Approval? = null): Boolean {
        val st = _state.value
        if (!st.canEdit || st.cart.isEmpty) return false
        val actor = graph.permissions.actorOrNull(Perm.DISCOUNT, approval) ?: return false
        val now = System.currentTimeMillis()
        val (kind, value) = discountColumns(d)
        val detail = describe(d)
        return commit(st.cart.withBillDiscount(d), st.lastKey, ids = 1L) { tx, cartId ->
            CartDao.setBillDiscount(tx, cartId, kind, value, now)
            AuditDao.log(tx, AuditAction.BILL_DISCOUNT, actor.staffId, now, amount = amountOf(d), detail = detail, approvedBy = actor.approvedBy)
        }
    }

    /**
     * Clears the whole bill (audited when it had lines). No permission is needed (D-063): taking the
     * lines off one by one never needed one, and a cashier waiting for a manager to clear a bill the
     * customer walked away from held up the queue. Returns false only while the bill is frozen.
     */
    fun clear(): Boolean {
        val st = _state.value
        if (!st.canEdit) return false
        if (st.cart.isEmpty && st.cartId == 0L) return true
        val cartId = st.cartId
        val now = System.currentTimeMillis()
        val total = st.priced.total
        val lines = st.cart.items.size
        val staffId = graph.staff.staffId
        if (cartId != 0L) {
            enqueue(if (lines > 0) 1L else 0L) { tx ->
                CartDao.deleteCart(tx, cartId)
                if (lines > 0) AuditDao.log(tx, AuditAction.BILL_CANCEL, staffId, now, amount = total, detail = "$lines lines")
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
        // The bill leaves the screen only once it is parked: cleared first, a failed write left it
        // nowhere — not on the screen, not among the held bills (2026-10 review).
        _state.value = st.copy(busy = true)
        try {
            flush()
        } catch (e: Throwable) {
            _state.value = _state.value.copy(busy = false)
            repair() // still the open bill: written as such again
            throw e
        }
        resetEmpty(st.heldCount + 1)
        return true
    }

    suspend fun heldBills(): List<HeldBill> {
        flush()
        val inclTax = graph.settings.store.value.pricesIncludeTax
        val promos = graph.promotions.active()
        return graph.db().read { r ->
            CartDao.held(r).map { row ->
                val total = CartDao.load(r, row.id)?.let { priceOrNull(toCart(it), inclTax, promos)?.total } ?: 0L
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
            val customer = loaded.first?.customerId?.let { id -> graph.db().read { billCustomer(it, id) } }
            val base = State(loaded = true, heldCount = loaded.second)
            _state.value = loaded.first?.let { fromStored(it, base, customer) } ?: base
            return loaded.first != null
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false)
            // The swap may be half written: the bill on the screen is written back as the open one.
            repairNeeded = true
            repair()
            throw e
        }
    }

    /**
     * Deletes a held bill. It is a cleared bill like any other: no permission ([clear], D-063), the
     * same audit entry.
     */
    suspend fun deleteHeld(heldId: Long): Boolean {
        val staffId = graph.staff.staffId
        flush()
        val inclTax = graph.settings.store.value.pricesIncludeTax
        val promos = graph.promotions.active()
        val bill = graph.db().read { r -> CartDao.load(r, heldId)?.let(::toCart) }
        val lines = bill?.items?.size ?: 0
        val total = bill?.let { priceOrNull(it, inclTax, promos)?.total } ?: 0L
        val now = System.currentTimeMillis()
        enqueue(if (lines > 0) 1L else 0L) { tx ->
            CartDao.deleteCart(tx, heldId)
            if (lines > 0) {
                AuditDao.log(tx, AuditAction.BILL_CANCEL, staffId, now, amount = total, detail = "held bill, $lines lines")
            }
        }
        flush()
        val held = graph.db().read { CartDao.heldCount(it) }
        _state.value = _state.value.copy(heldCount = held)
        return true
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
            repair() // after a failed bill write (see repairNeeded)
            throw e
        }
    }

    /** Freezes the bill while the payment dialog is open (scans and edits are refused). */
    fun setPaying(on: Boolean) {
        val st = _state.value
        if (st.paying != on) _state.value = st.copy(paying = on)
        if (!on) {
            payment = null
            repriceNow() // a promotion that changed meanwhile applies from here
        }
    }

    /** Waits until every change made so far is committed. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        ops.send(Op(0L, null, done))
        done.await()
    }

    // ------------------------------------------------------------------ internals

    private fun putLine(cart: Cart, key: Long, audit: ((Db.Tx, Long) -> Unit)? = null): Boolean {
        val row = try {
            cart.item(key)?.toLine(lineNo(key))
        } catch (e: ArithmeticException) {
            null
        } ?: return false
        val now = System.currentTimeMillis()
        return commit(cart, key, ids = if (audit != null) 1L else 0L) { tx, cartId ->
            CartDao.putLine(tx, cartId, row, now)
            audit?.invoke(tx, now)
        }
    }

    /**
     * Publishes [cart] and queues its persistence (creating the bill's row first if needed).
     * The bill is priced *first*: a change whose total cannot be computed (an absurd quantity
     * times a huge price overflows) is refused with nothing written. Written first, it crashed
     * the till and left a bill that could never be loaded again (2026-10 review).
     */
    private fun commit(cart: Cart, lastKey: Long, ids: Long = 0L, persist: (Db.Tx, Long) -> Unit): Boolean {
        val priced = price(cart) ?: return false
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
        _state.value = st.copy(cartId = cartId, openedAt = openedAt, cart = cart, priced = priced, lastKey = lastKey)
        return true
    }

    private fun enqueue(ids: Long = 0L, run: (Db.Tx) -> Unit) {
        if (repairNeeded) repair()
        ops.trySend(Op(ids, run))
    }

    /**
     * After a failed write (see [repairNeeded]): queues a write of the whole bill as memory has it
     * now — which is what every change queued so far amounts to. Main thread; waits while the
     * state is not the open bill yet (loading, resuming, paying out).
     */
    private fun repair() {
        val st = _state.value
        if (!repairNeeded || !st.loaded || st.busy) return
        repairNeeded = false
        val now = System.currentTimeMillis()
        val cartId = st.cartId
        if (cartId == 0L) {
            // No open bill: one still marked open in the database is parked, never loaded as the bill.
            ops.trySend(Op(0L, { tx -> CartDao.parkOpen(tx, 0L, now) }, repair = true))
            return
        }
        val lines = st.cart.items.mapNotNull { item ->
            try {
                item.toLine(lineNo(item.key))
            } catch (e: ArithmeticException) {
                null
            }
        }
        val (kind, value) = discountColumns(st.cart.billDiscount)
        val staff = graph.staff.staffId
        val openedAt = st.openedAt.takeIf { it > 0L } ?: now
        val customerId = st.customerId
        val rewrite: (Db.Tx) -> Unit = { tx ->
            CartDao.rewriteOpen(tx, cartId, staff, openedAt, customerId, kind, value, lines, now)
        }
        ops.trySend(Op(0L, rewrite, repair = true))
    }

    private fun resetEmpty(heldCount: Int) {
        lineNos.clear()
        nextLineNo = 1
        payment = null
        graph.permissions.endHelp() // the bill a manager helped with is done: the next one is the cashier's (D-063)
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
                } catch (e: Throwable) {
                    // Any failure (an Error too): this loop must go on, or every later flush() — the
                    // checkout's first step — would wait forever (2026-10 review).
                    Log.e("Saving the open bill failed", e)
                    if (batch.any { it.run != null }) {
                        repairNeeded = true
                        // A failed repair waits for the next change or flush: retried at once, a write
                        // that keeps failing (a full disk) spun the writer and the main thread for good.
                        if (batch.none { it.repair }) graph.appScope.launch(Dispatchers.Main) { repair() } // queued before any waiter resumes
                    }
                    for (op in batch) op.done?.completeExceptionally(e)
                }
            }
        }
    }

    /** The priced bill, or null when its amounts cannot be computed (see [commit]). */
    private fun price(cart: Cart): PricedCart? =
        priceOrNull(cart, graph.settings.store.value.pricesIncludeTax, graph.promotions.active())

    private fun priceOrNull(cart: Cart, inclTax: Boolean, promos: List<Promotion>): PricedCart? = try {
        for (i in cart.items) i.cost // stock quantity and cost of every line must be computable too
        cart.price(inclTax, promos)
    } catch (e: ArithmeticException) {
        Log.w("The bill cannot be priced", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w("The bill cannot be priced", e)
        null
    }

    /**
     * Prices the open bill again (promotions changed, or started or ended today). Never while it
     * is being paid: the amount on the payment screen is the amount the sale is stored with.
     * Callable from any thread (sync reloads promotions in the background): the bill itself is
     * only changed on the main thread, like every other change of it (2026-10 review).
     */
    suspend fun reprice() = withContext(Dispatchers.Main.immediate) { repriceNow() }

    private fun repriceNow() {
        _state.update { st ->
            if (!st.loaded || st.cart.isEmpty || st.busy || st.paying) return@update st
            val priced = price(st.cart) ?: return@update st
            st.copy(priced = priced)
        }
    }

    private fun lineNo(key: Long): Int = lineNos[key] ?: nextLineNo.also {
        lineNos[key] = it
        nextLineNo++
    }

    /** The bill's customer as stored: [name] for the screen; a [deleted] one is taken off the bill. */
    private class BillCustomer(val name: String?, val deleted: Boolean)

    private fun billCustomer(r: SQLiteDatabase, id: Long) =
        BillCustomer(CustomerDao.name(r, id), CustomerDao.isDeleted(r, id))

    private fun fromStored(s: StoredCart, base: State, customer: BillCustomer?): State {
        // A customer deleted while the bill was parked or the app was closed (here or on another
        // till) leaves the bill: nothing more can be charged to them (2026-10 review).
        val dropCustomer = customer?.deleted == true
        if (dropCustomer) {
            val cartId = s.id
            val now = System.currentTimeMillis()
            enqueue { tx -> CartDao.setCustomer(tx, cartId, null, now) }
        }
        lineNos.clear()
        nextLineNo = 1
        for (l in s.lines) {
            lineNos[l.id] = l.lineNo
            if (l.lineNo >= nextLineNo) nextLineNo = l.lineNo + 1
            if (l.id >= nextLineId) nextLineId = l.id + 1L
        }
        var cart = toCart(s)
        var priced = price(cart)
        if (priced == null) {
            // A stored bill always loads: lines that cannot be priced are dropped (and deleted).
            var kept = Cart(billDiscount = cart.billDiscount)
            for (item in cart.items) {
                val next = kept.copy(items = kept.items + item)
                if (price(next) != null) kept = next
            }
            val now = System.currentTimeMillis()
            val cartId = s.id
            for (item in cart.items) {
                if (kept.item(item.key) == null) {
                    val key = item.key
                    lineNos.remove(key)
                    enqueue { tx -> CartDao.deleteLine(tx, cartId, key, now) }
                }
            }
            cart = kept
            priced = price(kept) ?: PricedCart.EMPTY
        }
        return base.copy(
            cartId = s.id, openedAt = s.openedAt, cart = cart, priced = priced, lastKey = cart.items.lastOrNull()?.key ?: 0L,
            customerId = if (dropCustomer) null else s.customerId,
            customerName = if (dropCustomer) null else customer?.name,
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
        /** The most of one line: 99,999 pieces (or kg). Far above any real sale, far below an overflow. */
        const val MAX_QTY = 99_999_000L

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
