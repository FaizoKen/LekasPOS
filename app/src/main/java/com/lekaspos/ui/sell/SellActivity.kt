package com.lekaspos.ui.sell

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.app.LekasApp
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.hw.printer.PrinterService
import com.lekaspos.ui.Insets
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.common.DialogHost
import com.lekaspos.ui.common.DialogTracker
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.diag.DiagnosticsActivity
import com.lekaspos.ui.inventory.InventoryActivity
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.products.ProductListActivity
import com.lekaspos.ui.sales.ReceiptShare
import com.lekaspos.ui.sales.SalesActivity
import com.lekaspos.ui.scan.CameraScanActivity
import com.lekaspos.ui.settings.PrinterSettingsActivity
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The selling screen: launcher and home (references/architecture.md §4). Scanners work without
 * focusing anything: keyboard-wedge scanners are read here in [dispatchKeyEvent], SPP scanners
 * and the camera feed the same [onScanned]. The bill itself lives in [CartSession].
 */
class SellActivity : Activity(), LineActions, DialogHost {

    private val graph get() = LekasApp.graph(this)
    private val scope = MainScope()
    private var started: CoroutineScope? = null
    private var reportedDrawn = false

    private lateinit var titleView: TextView
    private lateinit var printerState: TextView
    private lateinit var heldBadge: TextView
    private lateinit var cameraButton: View
    private lateinit var search: EditText
    private lateinit var searchClear: View
    private var browseButton: View? = null
    private lateinit var cartPanel: View
    private lateinit var cartList: RecyclerView
    private lateinit var cartEmpty: View
    private lateinit var catalogPanel: View
    private lateinit var categoryList: RecyclerView
    private lateinit var productGrid: RecyclerView
    private lateinit var catalogEmpty: TextView
    private lateinit var summary: TextView
    private lateinit var holdButton: Button
    private lateinit var discountButton: Button
    private lateinit var payButton: Button
    private var twoPane = false
    private var catalogOpen = false

    private val cartAdapter = CartAdapter { openLine(it) }
    private val productAdapter = ProductTileAdapter { tapProduct(it) }
    private val categoryAdapter = CategoryChipAdapter { selectCategory(it) }
    private var selectedCategory = CategoryChipAdapter.ALL
    private var catalogJob: Job? = null
    private var catalogEnd = false
    private var searchJob: Job? = null

    private val scanInput = ScanInput(onScan = { onScanned(it) }, onTyped = { text, submit -> typed(text, submit) })

    private var beeper: Beeper? = null
    private var outcomeDialog: AlertDialog? = null
    private var paymentDialog: AlertDialog? = null
    private var hasCamera = false
    private var backCallback: Any? = null
    private val dialogs = DialogTracker()

    private val currency: CurrencySpec get() = graph.settings.store.value.currency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sell)
        Insets.apply(findViewById(R.id.root), findViewById(R.id.top_bar))
        titleView = findViewById(R.id.title)
        printerState = findViewById(R.id.printer_state)
        heldBadge = findViewById(R.id.held_badge)
        cameraButton = findViewById(R.id.btn_camera)
        search = findViewById(R.id.search)
        searchClear = findViewById(R.id.search_clear)
        browseButton = findViewById(R.id.btn_browse)
        cartPanel = findViewById(R.id.cart_panel)
        cartList = findViewById(R.id.cart_list)
        cartEmpty = findViewById(R.id.cart_empty)
        catalogPanel = findViewById(R.id.catalog_panel)
        categoryList = findViewById(R.id.category_list)
        productGrid = findViewById(R.id.product_grid)
        catalogEmpty = findViewById(R.id.catalog_empty)
        summary = findViewById(R.id.summary)
        holdButton = findViewById(R.id.btn_hold)
        discountButton = findViewById(R.id.btn_discount)
        payButton = findViewById(R.id.btn_pay)
        twoPane = findViewById<View>(R.id.cart_side) != null
        hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        cartList.layoutManager = LinearLayoutManager(this)
        cartList.adapter = cartAdapter
        categoryList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        categoryList.adapter = categoryAdapter
        val grid = GridLayoutManager(this, spanCount())
        productGrid.layoutManager = grid
        productGrid.adapter = productAdapter
        productGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !catalogEnd && grid.findLastVisibleItemPosition() >= productAdapter.itemCount - 12) loadCatalog(reset = false)
            }
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onSearchChanged(s?.toString() ?: "")
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submitSearch()
                true
            } else {
                false
            }
        }
        searchClear.setOnClickListener { clearSearch() }
        browseButton?.setOnClickListener { toggleCatalog() }
        browseButton?.visible(!twoPane)
        payButton.setOnClickListener { openPayment() }
        holdButton.setOnClickListener { hold() }
        discountButton.setOnClickListener { billDiscount() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btn_held).setOnClickListener { showHeld() }
        cameraButton.setOnClickListener { startActivity(CameraScanActivity.sellIntent(this)) }
        printerState.setOnClickListener { startActivity(Intent(this, PrinterSettingsActivity::class.java)) }
        beeper = Beeper.create()
        showPanels()
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        started = s
        s.launch {
            try {
                graph.cart.load()
            } catch (e: Exception) {
                Log.e("Loading the bill failed", e)
                Dialogs.message(this@SellActivity, getString(R.string.error_title), getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            }
            if (!reportedDrawn) {
                reportedDrawn = true
                reportFullyDrawn()
            }
            delay(HARDWARE_DELAY_MS) // keep Bluetooth work out of the cold-start path
            graph.printer.start()
            graph.sppScanner.start()
        }
        s.launch { graph.cart.state.collect { render(it) } }
        s.launch {
            graph.settings.store.collect { st ->
                titleView.text = st.name.ifBlank { getString(R.string.app_name) }
                cartAdapter.currency = st.currency
                productAdapter.currency = st.currency
                productAdapter.notifyItemRangeChanged(0, productAdapter.itemCount)
                render(graph.cart.state.value)
            }
        }
        s.launch { graph.settings.device.collect { cameraButton.visible(it.cameraScan && hasCamera) } }
        s.launch { combine(graph.printer.status, graph.printer.pending) { st, n -> st to n }.collect { renderPrinter(it.first, it.second) } }
        s.launch { graph.sppScanner.codes.collect { onScanned(it) } }
        s.launch { graph.checkout.outcome.collect { showOutcome(it) } }
        loadCategories()
    }

    override fun onStop() {
        started?.cancel()
        started = null
        graph.sppScanner.stop()
        scanInput.clear()
        super.onStop()
    }

    override fun onDestroy() {
        paymentDialog?.dismiss()
        // Keep the outcome: after a rotation the new screen shows the same result again.
        outcomeDialog?.setOnDismissListener(null)
        outcomeDialog?.dismiss()
        dialogs.dismissAll()
        scope.cancel()
        beeper?.release()
        super.onDestroy()
    }

    override fun track(d: android.app.Dialog) = dialogs.track(d)

    // ------------------------------------------------------------------ rendering

    @SuppressLint("SetTextI18n") // the held-bills badge is a bare count
    private fun render(st: CartSession.State) {
        if (!st.loaded) return
        cartAdapter.submit(st.cart.items, st.priced, st.lastKey)
        val pos = cartAdapter.positionOf(st.lastKey)
        if (pos >= 0) cartList.scrollToPosition(pos)
        val empty = st.cart.isEmpty
        cartEmpty.visible(empty)
        payButton.isEnabled = !empty && st.canEdit
        payButton.text = if (empty) getString(R.string.sell_pay_empty) else getString(R.string.sell_pay, money(st.priced.total))
        holdButton.isEnabled = !empty && st.canEdit
        discountButton.isEnabled = !empty && st.canEdit
        summary.text = summaryText(st)
        heldBadge.text = st.heldCount.toString()
        heldBadge.visible(st.heldCount > 0)
    }

    private fun summaryText(st: CartSession.State): String {
        val p = st.priced
        val parts = ArrayList<String>(3)
        val pieces = st.cart.pieceCount.toInt()
        parts.add(resources.getQuantityString(R.plurals.sell_items, pieces, pieces))
        if (p.discount != 0L) parts.add(getString(R.string.sell_summary_discount, money(p.discount)))
        if (p.tax != 0L) {
            parts.add(getString(if (p.pricesIncludeTax) R.string.sell_summary_tax_incl else R.string.sell_summary_tax, money(p.tax)))
        }
        return parts.joinToString(" · ")
    }

    private fun renderPrinter(s: PrinterService.Status, pending: Int) {
        val text = when (s) {
            PrinterService.Status.NotConfigured, PrinterService.Status.Idle, PrinterService.Status.Ready ->
                if (pending > 0) getString(R.string.printer_pill_waiting, pending) else null
            PrinterService.Status.Connecting, PrinterService.Status.Printing -> getString(R.string.printer_pill_printing)
            PrinterService.Status.NoBluetooth, PrinterService.Status.NoPermission, PrinterService.Status.BluetoothOff, is PrinterService.Status.Offline ->
                if (pending > 0) getString(R.string.printer_pill_offline_waiting, pending) else getString(R.string.printer_pill_offline)
        }
        printerState.text = text
        printerState.visible(text != null)
    }

    private fun money(v: Long) = MoneyFormat.format(v, currency)

    private fun spanCount(): Int {
        val width = resources.configuration.screenWidthDp * (if (twoPane) 0.6f else 1f)
        return (width / 150f).toInt().coerceIn(2, 6)
    }

    // ------------------------------------------------------------------ panels & back

    private fun showPanels() {
        val searching = search.text.isNotEmpty()
        if (twoPane) {
            catalogPanel.visible(true)
        } else {
            val catalog = catalogOpen || searching
            catalogPanel.visible(catalog)
            cartPanel.visible(!catalog)
            browseButton?.isSelected = catalog
        }
        categoryList.visible(!searching)
        updateBackHandling()
    }

    private fun toggleCatalog() {
        catalogOpen = !catalogOpen
        if (!catalogOpen && search.text.isNotEmpty()) clearSearch() else showPanels()
    }

    /** True when back should close the catalogue/search instead of leaving the app. */
    private fun backConsumable(): Boolean = !twoPane && (catalogOpen || search.text.isNotEmpty())

    private fun handleBack(): Boolean {
        if (!backConsumable()) return false
        catalogOpen = false
        if (search.text.isNotEmpty()) clearSearch() else showPanels()
        return true
    }

    /** Below API 33 the back key/gesture arrives as KEYCODE_BACK (see [dispatchKeyEvent]). */
    private fun backKey(e: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 33 || e.keyCode != KeyEvent.KEYCODE_BACK || !backConsumable()) return false
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) handleBack()
        return true
    }

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT >= 33) backCallback = Back33.update(this, backCallback, backConsumable()) { handleBack() }
    }

    @RequiresApi(33)
    private object Back33 {
        fun update(a: Activity, current: Any?, needed: Boolean, onBack: () -> Unit): Any? {
            if (needed && current == null) {
                val cb = OnBackInvokedCallback { onBack() }
                a.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
                return cb
            }
            if (!needed && current is OnBackInvokedCallback) {
                a.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
                return null
            }
            return current
        }
    }

    // ------------------------------------------------------------------ scanning

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        backKey(event) || (!search.hasFocus() && scanKey(event)) || super.dispatchKeyEvent(event)

    /** Keyboard-wedge scanner input while no text field has focus (also forwarded by dialogs). */
    fun scanKey(e: KeyEvent): Boolean = scanInput.onKey(e)

    /** A person typing on a keyboard: continue in the search field. */
    private fun typed(text: String, submit: Boolean) {
        search.setText(text)
        search.setSelection(text.length)
        search.requestFocus()
        if (submit) submitSearch()
    }

    /** Every scan ends here: HID buffer, search field, SPP scanner. */
    fun onScanned(code: String) {
        if (graph.checkout.outcome.value != null) graph.checkout.acknowledge() // next customer
        scope.launch {
            when (val r = graph.cart.scan(code)) {
                is CartSession.ScanResult.Added -> beeper?.ok()
                is CartSession.ScanResult.NeedsWeight -> {
                    beeper?.ok()
                    askWeight(r.product, r.code)
                }
                is CartSession.ScanResult.NeedsPrice -> {
                    beeper?.ok()
                    askPrice(r.product, r.code)
                }
                is CartSession.ScanResult.NotFound -> {
                    beeper?.error()
                    unknownBarcode(r.code)
                }
                CartSession.ScanResult.Busy -> beeper?.error()
            }
        }
    }

    private fun askWeight(p: SellableProduct, code: String?) {
        AmountDialog(this, getString(R.string.weigh_title, p.name), AmountDialog.Kind.WEIGHT, currency, p.unit) { milli ->
            graph.cart.addProduct(p, qty = milli, barcode = code)
        }.show()
    }

    private fun askPrice(p: SellableProduct, code: String?) {
        AmountDialog(this, getString(R.string.price_for_title, p.name), AmountDialog.Kind.MONEY, currency) { price ->
            graph.cart.addAtPrice(p, price, code)
        }.show()
    }

    private fun unknownBarcode(code: String) {
        showUnknownBarcode(
            this, code,
            onAddProduct = { startActivityForResult(ProductEditActivity.newIntent(this, barcode = code), REQ_NEW_PRODUCT) },
            onSellOther = { showOtherItem(this, currency) { name, price -> graph.cart.addCustom(name, price, code) } },
        )
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_NEW_PRODUCT && resultCode == RESULT_OK) {
            val id = data?.getLongExtra(ProductEditActivity.EXTRA_PRODUCT_ID, 0L) ?: 0L
            if (id != 0L) addProductById(id)
        }
    }

    // ------------------------------------------------------------------ search & catalogue

    private fun onSearchChanged(text: String) {
        searchClear.visible(text.isNotEmpty())
        showPanels()
        searchJob?.cancel()
        if (text.isBlank()) {
            loadCatalog(reset = true)
            return
        }
        catalogJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val results = graph.db().read { ProductDao.search(it, text, 60) }
            productAdapter.submit(results)
            productGrid.scrollToPosition(0)
            catalogEnd = true
            catalogEmpty.text = getString(R.string.sell_no_results, text.trim())
            catalogEmpty.visible(results.isEmpty())
        }
    }

    private fun submitSearch() {
        val text = search.text.toString().trim()
        if (text.isEmpty()) return
        scope.launch {
            if (text.length >= 3 && text.none { it.isWhitespace() }) {
                when (val r = graph.cart.scan(text)) {
                    is CartSession.ScanResult.Added -> {
                        beeper?.ok()
                        clearSearch()
                        return@launch
                    }
                    is CartSession.ScanResult.NeedsWeight -> {
                        clearSearch()
                        askWeight(r.product, r.code)
                        return@launch
                    }
                    is CartSession.ScanResult.NeedsPrice -> {
                        clearSearch()
                        askPrice(r.product, r.code)
                        return@launch
                    }
                    is CartSession.ScanResult.NotFound -> if (text.length >= 6 && text.all { it in '0'..'9' }) {
                        beeper?.error()
                        clearSearch()
                        unknownBarcode(text)
                        return@launch
                    }
                    CartSession.ScanResult.Busy -> return@launch
                }
            }
            val results = graph.db().read { ProductDao.search(it, text, 2) }
            if (results.size == 1) {
                tapProduct(results[0])
                clearSearch()
            } else {
                hideKeyboard()
            }
        }
    }

    private fun clearSearch() {
        search.setText("")
        search.clearFocus()
        hideKeyboard()
        showPanels()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun loadCategories() {
        val s = started ?: return
        s.launch {
            val cats = graph.db().read { CategoryDao.list(it) }
            val chips = ArrayList<Category>(cats.size + 1)
            chips.add(Category(CategoryChipAdapter.ALL, getString(R.string.sell_all)))
            chips.addAll(cats)
            if (chips.none { it.id == selectedCategory }) selectedCategory = CategoryChipAdapter.ALL
            categoryAdapter.submit(chips)
            categoryAdapter.selected = selectedCategory
            if (search.text.isEmpty()) loadCatalog(reset = true)
        }
    }

    private fun selectCategory(id: Long) {
        selectedCategory = id
        categoryAdapter.selected = id
        loadCatalog(reset = true)
    }

    private fun loadCatalog(reset: Boolean) {
        if (search.text.isNotEmpty()) return
        if (!reset && (catalogEnd || catalogJob?.isActive == true)) return
        catalogJob?.cancel()
        val category = selectedCategory
        val after = if (reset) null else productAdapter.items.lastOrNull()
        catalogJob = scope.launch {
            val page = graph.db().read { r ->
                if (category == CategoryChipAdapter.ALL) ProductDao.sellPage(r, after, PAGE) else ProductDao.byCategory(r, category, after, PAGE)
            }
            if (reset) {
                productAdapter.submit(page)
                productGrid.scrollToPosition(0)
            } else {
                productAdapter.append(page)
            }
            catalogEnd = page.size < PAGE
            catalogEmpty.text = getString(R.string.sell_no_products)
            catalogEmpty.visible(productAdapter.itemCount == 0)
        }
    }

    private fun tapProduct(item: ProductListItem) = addProductById(item.id)

    private fun addProductById(id: Long) {
        scope.launch {
            val p = graph.db().read { ProductDao.sellableById(it, id) } ?: return@launch
            when (p.sellMode) {
                SellMode.WEIGHT -> askWeight(p, null)
                SellMode.OPEN_PRICE -> askPrice(p, null)
                else -> if (graph.cart.addProduct(p) != 0L) beeper?.ok()
            }
            hideKeyboard()
            search.clearFocus()
        }
    }

    // ------------------------------------------------------------------ bill actions

    private fun openLine(item: CartItem) {
        val st = graph.cart.state.value
        if (!st.canEdit) return
        val i = st.cart.items.indexOfFirst { it.key == item.key }
        val pl = st.priced.lines.getOrNull(i) ?: return
        showLineDialog(this, item, pl.gross - pl.lineDiscount, currency, this)
    }

    override fun changeQty(item: CartItem, qty: Long) = graph.cart.setQty(item.key, qty)

    override fun enterQty(item: CartItem) {
        if (item.sellMode == SellMode.WEIGHT) {
            AmountDialog(this, getString(R.string.line_weight), AmountDialog.Kind.WEIGHT, currency, item.unit, item.qty) {
                graph.cart.setQty(item.key, it)
            }.show()
        } else {
            AmountDialog(this, getString(R.string.line_qty), AmountDialog.Kind.PIECES, currency, initial = item.qty) {
                graph.cart.setQty(item.key, it)
            }.show()
        }
    }

    override fun discount(item: CartItem) {
        if (!graph.permissions.allowed(Perm.DISCOUNT)) return notAllowed()
        DiscountDialog(this, getString(R.string.discount_title), currency, item.discount) { d ->
            if (!graph.cart.setLineDiscount(item.key, d)) notAllowed()
        }.show()
    }

    override fun price(item: CartItem) {
        if (!graph.permissions.allowed(Perm.PRICE_OVERRIDE)) return notAllowed()
        AmountDialog(this, getString(R.string.price_title), AmountDialog.Kind.MONEY, currency, initial = item.unitPrice, allowZero = true) { p ->
            if (!graph.cart.overridePrice(item.key, p)) notAllowed()
        }.show()
    }

    override fun remove(item: CartItem) = graph.cart.remove(item.key)

    private fun billDiscount() {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty) return
        if (!graph.permissions.allowed(Perm.DISCOUNT)) return notAllowed()
        DiscountDialog(this, getString(R.string.bill_discount_title), currency, st.cart.billDiscount) { d ->
            if (!graph.cart.setBillDiscount(d)) notAllowed()
        }.show()
    }

    private fun hold() {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty) return
        Dialogs.input(this, getString(R.string.held_hold_title), getString(R.string.held_label_hint)) { label ->
            graph.appScope.launch(Dispatchers.Main) { graph.cart.hold(label) }
            true
        }
    }

    private fun showHeld() {
        scope.launch {
            val bills = graph.cart.heldBills()
            showHeldBills(
                this@SellActivity, bills, currency,
                onResume = { id -> graph.appScope.launch(Dispatchers.Main) { graph.cart.resume(id) } },
                onDelete = { id -> graph.appScope.launch(Dispatchers.Main) { graph.cart.deleteHeld(id) } },
            )
        }
    }

    private fun cancelBill() {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty) return
        Dialogs.confirm(
            this, getString(R.string.clear_confirm_title),
            resources.getQuantityString(R.plurals.clear_confirm_message, st.cart.items.size, st.cart.items.size),
            getString(R.string.clear_yes),
        ) { if (!graph.cart.clear()) notAllowed() }
    }

    private fun openDrawer() {
        if (!graph.settings.device.value.hasPrinter) {
            Dialogs.message(this, null, getString(R.string.drawer_no_printer))
            return
        }
        scope.launch {
            try {
                graph.sales.openDrawer()
                Dialogs.message(this@SellActivity, null, getString(R.string.drawer_opened))
            } catch (e: Exception) {
                notAllowed()
            }
        }
    }

    private fun notAllowed() {
        Dialogs.message(this, null, getString(R.string.not_allowed))
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = listOf(
            R.string.menu_products, R.string.menu_inventory, R.string.menu_categories, R.string.menu_tax_rates, R.string.menu_sales,
            R.string.menu_open_drawer, R.string.menu_cancel_bill, R.string.menu_settings, R.string.menu_diagnostics,
        )
        for ((i, res) in items.withIndex()) m.menu.add(0, res, i, res)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.menu_products -> startActivity(Intent(this, ProductListActivity::class.java))
                R.string.menu_inventory -> startActivity(Intent(this, InventoryActivity::class.java))
                R.string.menu_categories -> startActivity(Intent(this, CategoriesActivity::class.java))
                R.string.menu_tax_rates -> startActivity(Intent(this, TaxRatesActivity::class.java))
                R.string.menu_sales -> startActivity(Intent(this, SalesActivity::class.java))
                R.string.menu_open_drawer -> openDrawer()
                R.string.menu_cancel_bill -> cancelBill()
                R.string.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.string.menu_diagnostics -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------------------------ payment

    private fun openPayment() {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty) return
        scope.launch {
            val methods = graph.db().read { PaymentMethodDao.active(it) }.filter { it.kind != PaymentKind.CREDIT }
            val now = graph.cart.state.value
            if (!now.canEdit || now.cart.isEmpty) return@launch
            graph.cart.setPaying(true)
            val d = PaymentDialog(this@SellActivity, now.priced.total, currency, methods) { tenders, rounding ->
                graph.checkout.start(tenders, rounding)
            }.show()
            d.setOnDismissListener {
                graph.cart.setPaying(false)
                paymentDialog = null
            }
            paymentDialog = d
        }
    }

    private fun showOutcome(o: CheckoutService.Outcome?) {
        outcomeDialog?.setOnDismissListener(null)
        outcomeDialog?.dismiss()
        outcomeDialog = null
        when (o) {
            null -> Unit
            is CheckoutService.Outcome.Completed -> outcomeDialog = showResult(o.done)
            is CheckoutService.Outcome.Failed -> {
                val d = AlertDialog.Builder(this)
                    .setTitle(R.string.pay_failed_title)
                    .setMessage(getString(R.string.pay_failed, o.error))
                    .setPositiveButton(R.string.ok, null)
                    .create()
                d.setOnDismissListener { graph.checkout.acknowledge() }
                d.show()
                outcomeDialog = d
            }
        }
    }

    private fun showResult(done: CheckoutService.Done): AlertDialog {
        val v = layoutInflater.inflate(R.layout.dialog_result, null)
        val device = graph.settings.device.value
        v.findViewById<TextView>(R.id.result_label).setText(if (done.change > 0L) R.string.result_change else R.string.result_paid)
        v.findViewById<TextView>(R.id.result_amount).text = money(if (done.change > 0L) done.change else done.total)
        v.findViewById<TextView>(R.id.result_receipt).text = getString(R.string.result_receipt, done.receiptNo)
        v.findViewById<TextView>(R.id.result_print_state).text = when {
            done.receiptQueued -> getString(R.string.result_printing)
            !device.hasPrinter -> getString(R.string.result_no_printer)
            else -> ""
        }
        val low = v.findViewById<TextView>(R.id.result_low_stock)
        low.text = if (done.lowStock.isEmpty()) "" else getString(R.string.result_low_stock, done.lowStock.joinToString(", ") { "${it.name} (${MoneyFormat.formatQty(it.qty)})" })
        low.visible(done.lowStock.isNotEmpty())
        val d = AlertDialog.Builder(this).setView(v).create()
        val print = v.findViewById<Button>(R.id.result_print)
        print.visible(device.hasPrinter)
        print.setText(if (done.receiptQueued) R.string.result_print_again else R.string.result_print)
        print.setOnClickListener {
            scope.launch {
                try {
                    graph.sales.print(done.saleId, copy = done.receiptQueued)
                    print.isEnabled = false
                } catch (e: Exception) {
                    notAllowed()
                }
            }
        }
        v.findViewById<View>(R.id.result_share).setOnClickListener { ReceiptShare.chooseAndShare(this, done.saleId) }
        v.findViewById<View>(R.id.result_new).setOnClickListener { d.dismiss() }
        d.setOnDismissListener { graph.checkout.acknowledge() }
        d.forwardKeys { e -> scanKey(e) }
        d.show()
        return d
    }

    companion object {
        private const val REQ_NEW_PRODUCT = 1
        private const val PAGE = 60
        private const val SEARCH_DEBOUNCE_MS = 150L
        private const val HARDWARE_DELAY_MS = 1500L
    }
}
