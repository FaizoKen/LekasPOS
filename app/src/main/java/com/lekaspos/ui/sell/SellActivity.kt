package com.lekaspos.ui.sell

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.app.Work
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.domain.Approval
import com.lekaspos.domain.StaffSession
import com.lekaspos.domain.backup.BackupService
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.hw.printer.PrinterService
import com.lekaspos.sync.SyncEngine
import com.lekaspos.ui.Insets
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.common.DialogHost
import com.lekaspos.ui.common.DialogTracker
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.customers.CustomersActivity
import com.lekaspos.ui.customers.pickCustomer
import com.lekaspos.ui.diag.DiagnosticsActivity
import com.lekaspos.ui.inventory.InventoryActivity
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.products.ProductListActivity
import com.lekaspos.ui.products.PromotionsActivity
import com.lekaspos.ui.reports.ReportsActivity
import com.lekaspos.ui.sales.ReceiptShare
import com.lekaspos.ui.sales.SalesActivity
import com.lekaspos.ui.scan.CameraScanActivity
import com.lekaspos.ui.settings.BackupActivity
import com.lekaspos.ui.settings.PrinterSettingsActivity
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.ui.settings.SetupActivity
import com.lekaspos.ui.settings.SyncActivity
import com.lekaspos.ui.shift.ShiftActivity
import com.lekaspos.ui.shift.openShift
import com.lekaspos.ui.staff.LockActivity
import com.lekaspos.ui.staff.changeOwnPin
import com.lekaspos.ui.staff.withApproval
import com.lekaspos.util.Log
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The selling screen: launcher and home (references/architecture.md §4). Scanners work without
 * focusing anything: keyboard-wedge scanners are read here in [dispatchKeyEvent], SPP scanners
 * and the camera feed the same [onScanned]. The bill itself lives in [CartSession].
 */
class SellActivity : Activity(), LineActions, DialogHost {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val graph get() = LekasApp.graph(this)
    private val scope = MainScope()
    private var started: CoroutineScope? = null
    private var reportedDrawn = false

    private lateinit var titleView: TextView
    private lateinit var printerState: TextView
    private lateinit var syncState: TextView
    private lateinit var heldPill: TextView
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
    private lateinit var totalView: TextView
    private lateinit var lastSale: View
    private lateinit var lastSaleTitle: TextView
    private lateinit var lastSaleText: TextView
    private lateinit var lastSaleChange: TextView
    private lateinit var lastSalePrint: View
    private lateinit var holdButton: Button
    private lateinit var discountButton: Button
    private lateinit var payButton: Button
    private lateinit var staffChip: TextView
    private lateinit var customerChip: TextView
    private var lockShown = false

    /** Managers' approvals given while the payment dialog is open (credit sales). */
    private var paymentApprovals = ArrayList<Approval>()
    private var creditTaken = 0L
    private var twoPane = false
    private var catalogOpen = false

    private val cartAdapter = CartAdapter(this)

    /** The bill line showing its buttons: the one scanned last, until another is tapped. */
    private var selectedKey = 0L
    private var lastSeenKey = 0L
    private var lastSeenLine: CartItem? = null
    private val productAdapter = ProductTileAdapter { tapProduct(it) }
    private val categoryAdapter = CategoryChipAdapter { selectCategory(it) }
    private var selectedCategory = CategoryChipAdapter.ALL
    private var categoryChosen = false
    private var catalogJob: Job? = null
    private var catalogEnd = false
    private var searchJob: Job? = null

    private val scanInput = ScanInput(onScan = { onScanned(it) }, onTyped = { text, submit -> typed(text, submit) })

    private var beeper: Beeper? = null
    private var outcomeDialog: AlertDialog? = null
    private var paymentDialog: AlertDialog? = null
    private var hasCamera = false
    private var priceCheck: PriceCheckDialog? = null
    private var setupChecked = false
    private var backCallback: Any? = null
    private val dialogs = DialogTracker()

    private val currency: CurrencySpec get() = graph.settings.store.value.currency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sell)
        Insets.apply(findViewById(R.id.root), findViewById(R.id.top_bar))
        titleView = findViewById(R.id.title)
        printerState = findViewById(R.id.printer_state)
        syncState = findViewById(R.id.sync_state)
        heldPill = findViewById(R.id.held_pill)
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
        totalView = findViewById(R.id.total)
        lastSale = findViewById(R.id.last_sale)
        lastSaleTitle = findViewById(R.id.last_sale_title)
        lastSaleText = findViewById(R.id.last_sale_text)
        lastSaleChange = findViewById(R.id.last_sale_change)
        lastSalePrint = findViewById(R.id.last_sale_print)
        holdButton = findViewById(R.id.btn_hold)
        discountButton = findViewById(R.id.btn_discount)
        payButton = findViewById(R.id.btn_pay)
        staffChip = findViewById(R.id.staff_chip)
        customerChip = findViewById(R.id.customer_chip)
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
        findViewById<View>(R.id.btn_other).setOnClickListener { sellOther() }
        browseButton?.visible(!twoPane)
        findViewById<View>(R.id.btn_price_check).apply {
            visible(twoPane) // phones: in the menu (the search row has no room for a third tool)
            setOnClickListener { showPriceCheck() }
        }
        findViewById<TextView>(R.id.cart_empty_help).setText(if (twoPane) R.string.sell_empty_help_wide else R.string.sell_empty_help)
        if (!twoPane) search.setHint(R.string.sell_search_hint_short) // room for the tools beside it
        lastSalePrint.setOnClickListener { printLastSale() }
        payButton.setOnClickListener { openPayment() }
        holdButton.setOnClickListener { hold() }
        discountButton.setOnClickListener { billDiscount() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        heldPill.setOnClickListener { showHeld() }
        cameraButton.setOnClickListener { startActivity(CameraScanActivity.sellIntent(this)) }
        printerState.setOnClickListener { startActivity(Intent(this, PrinterSettingsActivity::class.java)) }
        staffChip.setOnClickListener { staffMenu(it) }
        customerChip.setOnClickListener { customerAction() }
        beeper = Beeper.create()
        showPanels()
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        started = s
        lockShown = false
        s.launch {
            try {
                graph.cart.load()
                graph.staff.load()
                graph.shifts.load()
            } catch (e: Exception) {
                Log.e("Loading the bill failed", e)
                Dialogs.message(this@SellActivity, getString(R.string.error_title), getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            }
            if (!reportedDrawn) {
                reportedDrawn = true
                reportFullyDrawn()
            }
            if (!setupChecked) {
                setupChecked = true
                if (graph.settings.needsSetup()) startActivity(Intent(this@SellActivity, SetupActivity::class.java))
            }
            delay(HARDWARE_DELAY_MS) // keep Bluetooth work out of the cold-start path
            graph.printer.start()
            graph.sppScanner.start()
            val app = applicationContext
            graph.appScope.launch(Dispatchers.IO) { Work.schedule(app) } // background jobs, after the till is usable (WorkManager starts here, off the main thread)
            graph.sync.refreshStatus()
            graph.backups.refreshProtection()
        }
        s.launch { graph.cart.state.collect { render(it) } }
        s.launch { graph.staff.state.collect { renderStaff(it) } }
        s.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                graph.staff.lockIfIdle()
            }
        }
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
        s.launch {
            combine(graph.sync.status, graph.backups.protection) { a, b -> a to b }.collect { renderSafety(it.first, it.second) }
        }
        s.launch { graph.sync.status.map { it.lastSuccessAt }.distinctUntilChanged().collect { graph.backups.refreshProtection() } }
        s.launch { graph.sppScanner.codes.collect { onScanned(it) } }
        s.launch { graph.checkout.last.collect { renderLastSale(it) } }
        s.launch {
            graph.checkout.outcome.collect {
                showOutcome(it)
                if (it != null) graph.backups.refreshProtection() // the first sale makes "not backed up" possible
            }
        }
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

    private fun render(st: CartSession.State) {
        if (!st.loaded) return
        // A new scan (or change) selects its line — also the same product scanned again, which
        // changes the line but not its key; a tap selects another line until the next scan.
        val last = st.cart.item(st.lastKey)
        val scanned = st.lastKey != lastSeenKey || last != lastSeenLine
        if (scanned) {
            lastSeenKey = st.lastKey
            lastSeenLine = last
            selectedKey = st.lastKey
        }
        if (st.cart.item(selectedKey) == null) selectedKey = 0L
        cartAdapter.submit(st.cart.items, st.priced, selectedKey)
        if (scanned) cartAdapter.positionOf(selectedKey).takeIf { it >= 0 }?.let { cartList.scrollToPosition(it) }
        val empty = st.cart.isEmpty
        cartEmpty.visible(empty)
        cartList.visible(!empty)
        payButton.isEnabled = !empty && st.canEdit
        totalView.text = money(st.priced.total)
        holdButton.isEnabled = !empty && st.canEdit
        discountButton.isEnabled = !empty && st.canEdit
        summary.text = summaryText(st)
        heldPill.text = resources.getQuantityString(R.plurals.held_pill, st.heldCount, st.heldCount)
        heldPill.visible(st.heldCount > 0)
        val onBill = HashMap<Long, Long>()
        for (item in st.cart.items) item.productId?.let { onBill[it] = (onBill[it] ?: 0L) + item.qty }
        productAdapter.setOnBill(onBill)
        val credit = graph.settings.store.value.creditEnabled
        customerChip.visible(credit)
        if (credit) customerChip.text = st.customerName?.let { getString(R.string.sell_customer, it) } ?: getString(R.string.sell_customer_none)
    }

    private fun renderStaff(s: StaffSession.State) {
        staffChip.text = s.current?.name
        staffChip.visible(s.loginRequired && s.current != null)
        if (s.locked) showLock()
    }

    /** Nobody is signed in: the lock screen covers the till (Back there leaves the app). */
    private fun showLock() {
        if (lockShown) return
        lockShown = true
        paymentDialog?.dismiss()
        dialogs.dismissAll()
        startActivity(Intent(this, LockActivity::class.java))
    }

    private fun staffMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, R.string.menu_lock, 0, R.string.menu_lock)
        m.menu.add(0, R.string.staff_change_own_pin, 1, R.string.staff_change_own_pin)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.menu_lock -> graph.staff.lock()
                R.string.staff_change_own_pin -> changeOwnPin(this, graph, scope)
            }
            true
        }
        m.show()
    }

    /** Choose, change or remove the bill's customer. */
    private fun customerAction() {
        val st = graph.cart.state.value
        if (!st.canEdit) return
        if (st.customerId == null) {
            pickCustomer(this, REQ_CUSTOMER)
            return
        }
        val options = listOf(getString(R.string.sell_customer_change), getString(R.string.sell_customer_remove))
        Dialogs.choose(this, st.customerName ?: "", options) { i ->
            if (i == 0) pickCustomer(this, REQ_CUSTOMER) else graph.cart.setCustomer(null, null)
        }
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

    /** The empty bill shows the last sale: the change again (customers ask), and a copy of the receipt. */
    private fun renderLastSale(done: CheckoutService.Done?) {
        lastSale.visible(done != null)
        if (done == null) return
        lastSaleTitle.text = getString(R.string.last_sale_title, DateText.time(done.at, TimeZone.getDefault()), done.receiptNo)
        lastSaleText.text = if (done.change > 0L) {
            getString(R.string.last_sale_received, money(done.total), money(done.received))
        } else {
            getString(R.string.last_sale_paid, money(done.total))
        }
        lastSaleChange.text = getString(R.string.pay_change, money(done.change))
        lastSaleChange.visible(done.change > 0L)
        lastSalePrint.visible(graph.settings.device.value.hasPrinter)
    }

    private fun printLastSale() {
        val done = graph.checkout.last.value ?: return
        withApproval(graph, scope, Perm.REPRINT) { approval ->
            scope.launch {
                try {
                    graph.sales.print(done.saleId, copy = true, approval = approval)
                } catch (e: Exception) {
                    notAllowed()
                }
            }
        }
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

    /**
     * Quiet while the data is safe (D-048). A pill only when something needs the owner: Google
     * sign-in, a damaged database, sync not working for a day, or no copy off this phone lately.
     */
    private fun renderSafety(s: SyncEngine.Status, p: BackupService.Protection) {
        val quiet = System.currentTimeMillis() - (s.lastSuccessAt ?: 0L) > SYNC_STALE_MS
        val stale = s.enabled && !s.running && s.pending > 0L && quiet
        val risk = p.state == BackupService.Protection.State.AT_RISK
        val pill: Pair<Int, () -> Unit>? = when {
            s.enabled && s.needsSignIn -> R.string.sync_pill_sign_in to { open(SyncActivity::class.java) }
            p.state == BackupService.Protection.State.DAMAGED ->
                R.string.safety_pill_damaged to { open(BackupActivity::class.java) }
            stale -> R.string.sync_pill_stale to { open(SyncActivity::class.java) }
            risk && s.enabled -> R.string.sync_pill_stale to { open(SyncActivity::class.java) }
            risk -> R.string.safety_pill_at_risk to { explainAtRisk() }
            else -> null
        }
        syncState.text = pill?.let { getString(it.first) }
        syncState.setOnClickListener { pill?.second?.invoke() }
        syncState.visible(pill != null)
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    /** The shop's data is only on this phone: what that means and the two ways to fix it. */
    private fun explainAtRisk() {
        AlertDialog.Builder(this)
            .setTitle(R.string.safety_title)
            .setMessage(R.string.safety_at_risk)
            .setPositiveButton(R.string.safety_use_drive) { _, _ -> open(SyncActivity::class.java) }
            .setNeutralButton(R.string.safety_use_folder) { _, _ ->
                startActivity(Intent(this, BackupActivity::class.java).putExtra(BackupActivity.EXTRA_PICK_FOLDER, true))
            }
            .setNegativeButton(R.string.safety_later, null)
            .show()
            .trackedBy(this)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        graph.staff.touch()
        return backKey(event) || (!search.hasFocus() && scanKey(event)) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        graph.staff.touch()
        return super.dispatchTouchEvent(ev)
    }

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
        priceCheck?.takeIf { it.isShowing }?.let {
            it.lookup(code) // price check open: look up, never add to the bill
            return
        }
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

    /** An item without a barcode (loose vegetables, kuih …): name optional, price typed in. */
    private fun showPriceCheck() {
        val camera: (() -> Unit)? = if (graph.settings.device.value.cameraScan && hasCamera) {
            {
                @Suppress("DEPRECATION")
                startActivityForResult(CameraScanActivity.pickIntent(this), REQ_PRICE_SCAN)
            }
        } else {
            null
        }
        priceCheck = PriceCheckDialog(this, scope, graph.priceCheck, currency, camera).also { it.show() }
    }

    private fun sellOther() {
        showOtherItem(this, currency) { name, price -> graph.cart.addCustom(name, price, null) }
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
        if (requestCode == REQ_CUSTOMER && resultCode == RESULT_OK && data != null) {
            val id = data.getLongExtra(CustomersActivity.EXTRA_ID, 0L)
            if (id != 0L) graph.cart.setCustomer(id, data.getStringExtra(CustomersActivity.EXTRA_NAME))
        }
        if (requestCode == REQ_PRICE_SCAN && resultCode == RESULT_OK) {
            data?.getStringExtra(CameraScanActivity.EXTRA_CODE)?.let { priceCheck?.lookup(it) }
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
            val popular = graph.popular.load().isNotEmpty()
            val chips = ArrayList<Category>(cats.size + 2)
            if (popular) chips.add(Category(CategoryChipAdapter.POPULAR, getString(R.string.sell_popular)))
            chips.add(Category(CategoryChipAdapter.ALL, getString(R.string.sell_all)))
            chips.addAll(cats)
            // Best sellers first once the shop has sold something; the owner's choice is kept after that.
            if (!categoryChosen && popular) selectedCategory = CategoryChipAdapter.POPULAR
            if (chips.none { it.id == selectedCategory }) selectedCategory = CategoryChipAdapter.ALL
            categoryAdapter.submit(chips)
            categoryAdapter.selected = selectedCategory
            if (search.text.isEmpty()) loadCatalog(reset = true)
        }
    }

    private fun selectCategory(id: Long) {
        categoryChosen = true
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
            val popular = category == CategoryChipAdapter.POPULAR
            val page = if (popular) {
                graph.popular.load() // one short list, no more pages
            } else {
                graph.db().read { r ->
                    if (category == CategoryChipAdapter.ALL) ProductDao.sellPage(r, after, PAGE) else ProductDao.byCategory(r, category, after, PAGE)
                }
            }
            if (reset) {
                productAdapter.submit(page)
                productGrid.scrollToPosition(0)
            } else {
                productAdapter.append(page)
            }
            catalogEnd = popular || page.size < PAGE
            catalogEmpty.text = getString(R.string.sell_no_products)
            catalogEmpty.visible(productAdapter.itemCount == 0)
        }
    }

    /** A tile: added to the bill. Picked from search results, the search closes and the bill shows again. */
    private fun tapProduct(item: ProductListItem) {
        addProductById(item.id)
        if (search.text.isNotEmpty()) clearSearch()
    }

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

    /** A tap on a line shows its buttons (a second tap hides them). */
    override fun select(item: CartItem) {
        selectedKey = if (selectedKey == item.key) 0L else item.key
        render(graph.cart.state.value)
    }

    override fun changeQty(item: CartItem, qty: Long) = graph.cart.setQty(item.key, qty)

    override fun more(item: CartItem) {
        if (!graph.cart.state.value.canEdit) return
        showLineMore(this, item, onQty = { enterQty(item) }, onDiscount = { lineDiscount(item) }, onPrice = { linePrice(item) })
    }

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

    private fun lineDiscount(item: CartItem) {
        withApproval(graph, scope, Perm.DISCOUNT) { approval ->
            DiscountDialog(this, getString(R.string.discount_title), currency, item.discount) { d ->
                if (!graph.cart.setLineDiscount(item.key, d, approval)) notAllowed()
            }.show()
        }
    }

    private fun linePrice(item: CartItem) {
        withApproval(graph, scope, Perm.PRICE_OVERRIDE) { approval ->
            AmountDialog(this, getString(R.string.price_title), AmountDialog.Kind.MONEY, currency, initial = item.unitPrice, allowZero = true) { p ->
                if (!graph.cart.overridePrice(item.key, p, approval)) notAllowed()
            }.show()
        }
    }

    override fun remove(item: CartItem) = graph.cart.remove(item.key)

    private fun billDiscount() {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty) return
        withApproval(graph, scope, Perm.DISCOUNT) { approval ->
            DiscountDialog(this, getString(R.string.bill_discount_title), currency, st.cart.billDiscount) { d ->
                if (!graph.cart.setBillDiscount(d, approval)) notAllowed()
            }.show()
        }
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
        ) { withApproval(graph, scope, Perm.CANCEL_BILL) { approval -> if (!graph.cart.clear(approval)) notAllowed() } }
    }

    private fun openDrawer() {
        if (!graph.settings.device.value.hasPrinter) {
            Dialogs.message(this, null, getString(R.string.drawer_no_printer))
            return
        }
        withApproval(graph, scope, Perm.OPEN_DRAWER) { approval ->
            scope.launch {
                try {
                    graph.sales.openDrawer(approval)
                    Dialogs.message(this@SellActivity, null, getString(R.string.drawer_opened))
                } catch (e: Exception) {
                    notAllowed()
                }
            }
        }
    }

    private fun notAllowed() {
        Dialogs.message(this, null, getString(R.string.not_allowed))
    }

    /**
     * The cashier's jobs first; the shop's back office (products, stock, reports, settings…) under
     * one "Manage shop" entry, so a new cashier sees a short list (D-049).
     */
    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = ArrayList<Int>(10)
        items += listOf(R.string.price_check_title, R.string.held_title, R.string.menu_sales)
        if (graph.settings.store.value.creditEnabled) items += R.string.menu_customers
        items += listOf(R.string.menu_shift, R.string.menu_open_drawer, R.string.menu_cancel_bill)
        if (graph.staff.state.value.loginRequired) items += R.string.menu_lock
        for ((i, res) in items.withIndex()) m.menu.add(0, res, i, res)
        val manage = m.menu.addSubMenu(0, R.string.menu_manage, items.size, getString(R.string.menu_manage) + "  ›")
        val office = listOf(
            R.string.menu_products, R.string.menu_inventory, R.string.menu_categories, R.string.promo_title,
            R.string.menu_tax_rates, R.string.menu_reports, R.string.menu_settings, R.string.menu_diagnostics,
        )
        manage.setHeaderTitle(R.string.menu_manage)
        for ((i, res) in office.withIndex()) manage.add(0, res, i, res)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.held_title -> showHeld()
                R.string.price_check_title -> showPriceCheck()
                R.string.menu_products -> startActivity(Intent(this, ProductListActivity::class.java))
                R.string.menu_inventory -> startActivity(Intent(this, InventoryActivity::class.java))
                R.string.menu_categories -> startActivity(Intent(this, CategoriesActivity::class.java))
                R.string.promo_title -> startActivity(Intent(this, PromotionsActivity::class.java))
                R.string.menu_tax_rates -> startActivity(Intent(this, TaxRatesActivity::class.java))
                R.string.menu_sales -> startActivity(Intent(this, SalesActivity::class.java))
                R.string.menu_reports -> startActivity(Intent(this, ReportsActivity::class.java))
                R.string.menu_shift -> startActivity(Intent(this, ShiftActivity::class.java))
                R.string.menu_customers -> startActivity(Intent(this, CustomersActivity::class.java))
                R.string.menu_lock -> graph.staff.lock()
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
        val store = graph.settings.store.value
        if (store.shiftRequired && graph.shifts.current.value == null) {
            Dialogs.confirm(this, getString(R.string.shift_needed_title), getString(R.string.shift_needed), getString(R.string.shift_open)) {
                openShift(this, graph, scope) { openPayment() }
            }
            return
        }
        scope.launch {
            val credit = store.creditEnabled && graph.cart.state.value.customerId != null
            val methods = graph.db().read { PaymentMethodDao.active(it) }.filter { it.kind != PaymentKind.CREDIT || credit }
            val now = graph.cart.state.value
            if (!now.canEdit || now.cart.isEmpty) return@launch
            graph.cart.setPaying(true)
            paymentApprovals = ArrayList()
            creditTaken = 0L
            val d = PaymentDialog(this@SellActivity, now.priced.total, currency, methods, authorize = { m, amount, done -> authorize(m, amount, done) }) { tenders, rounding ->
                graph.checkout.start(tenders, rounding, paymentApprovals.toList())
            }.show()
            d.setOnDismissListener {
                graph.cart.setPaying(false)
                paymentDialog = null
            }
            paymentDialog = d
        }
    }

    /**
     * A credit tender needs the bill's customer, the CREDIT_SALE permission (or a manager) and,
     * over the customer's limit, a manager's CREDIT_LIMIT approval (D-039).
     */
    private fun authorize(m: PaymentMethod, amount: Long, done: (Boolean) -> Unit) {
        if (m.kind != PaymentKind.CREDIT) return done(true)
        val customerId = graph.cart.state.value.customerId
        if (customerId == null) {
            Dialogs.message(this, null, getString(R.string.error_needs_customer))
            return done(false)
        }
        withApproval(graph, scope, Perm.CREDIT_SALE) { saleApproval ->
            saleApproval?.let { paymentApprovals.add(it) }
            scope.launch {
                val loaded = graph.customers.get(customerId) ?: return@launch done(false)
                val (c, balance) = loaded
                if (!CreditMath.overLimit(balance, creditTaken + amount, c.creditLimit)) {
                    creditTaken += amount
                    done(true)
                    return@launch
                }
                val msg = getString(R.string.credit_over_limit, c.name, money(balance), money(c.creditLimit), money(amount))
                Dialogs.confirm(this@SellActivity, getString(R.string.credit_over_limit_title), msg, getString(R.string.credit_allow)) {
                    withApproval(graph, scope, Perm.CREDIT_LIMIT) { limitApproval ->
                        limitApproval?.let { paymentApprovals.add(it) }
                        creditTaken += amount
                        done(true)
                    }
                }
            }
        }
    }

    private fun showOutcome(o: CheckoutService.Outcome?) {
        outcomeDialog?.setOnDismissListener(null)
        outcomeDialog?.dismiss()
        outcomeDialog = null
        when (o) {
            null -> Unit
            is CheckoutService.Outcome.Completed -> outcomeDialog = showResult(o.done)
            is CheckoutService.Outcome.Refused -> {
                val d = Dialogs.message(this, getString(R.string.pay_failed_title), ScreenActivity.errorText(this, ActionRefused(o.reason)))
                d.setOnDismissListener { graph.checkout.acknowledge() }
                outcomeDialog = d
            }
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
        v.findViewById<TextView>(R.id.result_amount).apply {
            text = money(if (done.change > 0L) done.change else done.total)
            if (done.change > 0L) setBackgroundResource(R.drawable.change_bg)
        }
        v.findViewById<TextView>(R.id.result_received).apply {
            text = getString(R.string.result_received, money(done.received), money(done.total))
            visible(done.change > 0L)
        }
        v.findViewById<TextView>(R.id.result_receipt).text = getString(R.string.result_receipt, done.receiptNo)
        v.findViewById<TextView>(R.id.result_print_state).text = when {
            done.receiptQueued -> getString(R.string.result_printing)
            !device.hasPrinter -> getString(R.string.result_no_printer)
            else -> ""
        }
        val cust = v.findViewById<TextView>(R.id.result_customer)
        val owes = done.customerBalance
        cust.text = if (done.customerName != null && owes != null) getString(R.string.result_customer, done.customerName, money(owes)) else ""
        cust.visible(done.customerName != null && owes != null)
        val low = v.findViewById<TextView>(R.id.result_low_stock)
        low.text = if (done.lowStock.isEmpty()) "" else getString(R.string.result_low_stock, done.lowStock.joinToString(", ") { "${it.name} (${MoneyFormat.formatQty(it.qty)})" })
        low.visible(done.lowStock.isNotEmpty())
        val d = AlertDialog.Builder(this).setView(v).create()
        val print = v.findViewById<Button>(R.id.result_print)
        print.visible(device.hasPrinter)
        print.setText(if (done.receiptQueued) R.string.result_print_again else R.string.result_print)
        print.setOnClickListener {
            val copy = done.receiptQueued
            val go = { approval: Approval? ->
                scope.launch {
                    try {
                        graph.sales.print(done.saleId, copy = copy, approval = approval)
                        print.isEnabled = false
                    } catch (e: Exception) {
                        notAllowed()
                    }
                }
                Unit
            }
            if (copy) withApproval(graph, scope, Perm.REPRINT) { go(it) } else go(null)
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
        private const val REQ_PRICE_SCAN = 7
        private const val REQ_CUSTOMER = 2
        private const val IDLE_CHECK_MS = 15_000L
        private const val PAGE = 60
        private const val SEARCH_DEBOUNCE_MS = 150L
        private const val HARDWARE_DELAY_MS = 1500L
        private const val SYNC_STALE_MS = 24L * 60L * 60L * 1000L
    }
}
