package com.lekaspos.ui.sell

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.AppUpdates
import com.lekaspos.app.ErrorReports
import com.lekaspos.app.LekasApp
import com.lekaspos.app.Work
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.core.report.ReportMath
import com.lekaspos.core.scan.ScanBuffer
import com.lekaspos.core.time.ClockCheck
import com.lekaspos.core.time.DateText
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.PaymentMethod
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.data.product.SellableProduct
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.domain.StaffSession
import com.lekaspos.domain.backup.BackupService
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.domain.sell.CheckoutService
import com.lekaspos.domain.sell.PriceCheck
import com.lekaspos.hw.printer.PrinterService
import com.lekaspos.hw.scanner.SppScanner
import com.lekaspos.perf.PerfDataGenerator
import com.lekaspos.sync.SyncEngine
import com.lekaspos.ui.Insets
import com.lekaspos.ui.catalog.CategoriesActivity
import com.lekaspos.ui.catalog.TaxRatesActivity
import com.lekaspos.ui.common.CsvFiles
import com.lekaspos.ui.common.DialogHost
import com.lekaspos.ui.common.DialogKeys
import com.lekaspos.ui.common.DialogTracker
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.KeyboardTip
import com.lekaspos.ui.common.LaunchGuard
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.TapOnce
import com.lekaspos.ui.common.keys
import com.lekaspos.ui.common.scanChar
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
import com.lekaspos.ui.settings.ReportsChoice
import com.lekaspos.ui.settings.SettingsActivity
import com.lekaspos.ui.settings.SetupActivity
import com.lekaspos.ui.settings.SyncActivity
import com.lekaspos.ui.settings.UpdateUi
import com.lekaspos.ui.shift.ShiftActivity
import com.lekaspos.ui.shift.openShift
import com.lekaspos.ui.staff.LockActivity
import com.lekaspos.ui.staff.changeOwnPin
import com.lekaspos.ui.staff.withApproval
import com.lekaspos.util.Log
import com.lekaspos.util.Storage
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

/**
 * The selling screen: launcher and home (references/architecture.md §4). Scanners work without
 * focusing anything: keyboard-wedge scanners are read here in [dispatchKeyEvent], SPP scanners
 * and the camera feed the same [onScanned]. The bill itself lives in [CartSession].
 */
class SellActivity : Activity(), LineActions, DialogHost {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val graph get() = LekasApp.graph(this)

    /**
     * A job of this screen that fails (a database read, say) is logged and shown; it never takes
     * the till down. Without it a database that could not open crashed the app at every start,
     * before the message saying so could show (2026-10 review).
     */
    private val failures = CoroutineExceptionHandler { _, e -> runOnUiThread { showFailure(e) } }
    private val scope = MainScope() + failures
    private var started: CoroutineScope? = null
    private var failureDialog: AlertDialog? = null
    private var reportedDrawn = false

    private lateinit var titleView: TextView
    private lateinit var printerState: TextView
    private lateinit var syncState: TextView
    private lateinit var heldPill: TextView
    private lateinit var updatePill: TextView
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

    /** The error-reports question was considered this time on screen (D-057). */
    private var reportsChecked = false
    private var backCallback: Any? = null
    private val dialogs = DialogTracker()

    private val currency: CurrencySpec get() = graph.settings.store.value.currency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The icon tapped while the app runs in a task Android started otherwise (the installer's "Open"
        // after an update): a second selling screen would open above the first one and the screens over
        // it. The task as it was shows instead (the manifest's singleTop, 2026-10 review).
        if (alive > 0 && !isTaskRoot && intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }
        alive++
        counted = true
        setContentView(R.layout.activity_sell)
        Insets.apply(findViewById(R.id.root), findViewById(R.id.top_bar))
        keyboardTip = KeyboardTip.watch(this) // a keyboard-mode scanner hides the on-screen keyboard: said once
        titleView = findViewById(R.id.title)
        printerState = findViewById(R.id.printer_state)
        syncState = findViewById(R.id.sync_state)
        heldPill = findViewById(R.id.held_pill)
        updatePill = findViewById(R.id.update_pill)
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
        // No cross-fade when a line changes: the fading copy of a row kept taking taps on its old − and +.
        (cartList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
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
            val key = event?.keyCode
            when {
                // Both halves of Enter: a key-up left to the field would move the focus away.
                key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) submitSearch()
                    true
                }
                actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE -> {
                    submitSearch()
                    true
                }
                else -> false
            }
        }
        searchClear.setOnClickListener { clearSearch() }
        browseButton?.setOnClickListener { toggleCatalog() }
        browseButton?.visible(!twoPane)
        findViewById<View>(R.id.btn_price_check).setOnClickListener { showPriceCheck() }
        findViewById<TextView>(R.id.cart_empty_help).setText(if (twoPane) R.string.sell_empty_help_wide else R.string.sell_empty_help)
        if (!twoPane) search.setHint(R.string.sell_search_hint_short) // room for the tools beside it
        lastSalePrint.setOnClickListener { printLastSale() }
        payButton.setOnClickListener { openPayment() }
        holdButton.setOnClickListener { hold() }
        discountButton.setOnClickListener { billDiscount() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        heldPill.setOnClickListener { showHeld() }
        updatePill.setOnClickListener { UpdateUi.offer(this, scope) }
        @Suppress("DEPRECATION")
        cameraButton.setOnClickListener { startActivityForResult(CameraScanActivity.sellIntent(this), REQ_CAMERA_SELL) }
        printerState.setOnClickListener { startActivity(Intent(this, PrinterSettingsActivity::class.java)) }
        staffChip.setOnClickListener { staffMenu(it) }
        customerChip.setOnClickListener { customerAction() }
        beeper = Beeper.create()
        showPanels()
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope() + failures
        started = s
        lockShown = false
        graph.staff.screenStarted() // idle too long while out of sight: locks now, before the first tap counts as activity
        // Back from a screen that kept the serial scanner connected: kept, not dropped and reconnected.
        if (graph.sppScanner.status.value != SppScanner.Status.OFF) graph.sppScanner.hold(this)
        s.launch {
            try {
                graph.cart.load()
                graph.staff.load()
                graph.shifts.load()
            } catch (e: CancellationException) {
                throw e // the screen stopped meanwhile
            } catch (e: Exception) {
                showFailure(e)
            }
            resumePayment()
            if (paymentDialog == null) resumePrompt()
            if (!reportedDrawn) {
                reportedDrawn = true
                reportFullyDrawn()
            }
            var setupShown = false
            if (!setupChecked) {
                setupChecked = true
                if (graph.settings.needsSetup()) {
                    startActivity(Intent(this@SellActivity, SetupActivity::class.java))
                    setupShown = true
                }
            }
            if (!setupShown && !reportsChecked) askAboutReports()
            delay(HARDWARE_DELAY_MS) // keep Bluetooth work out of the cold-start path
            graph.printer.start()
            graph.sppScanner.hold(this@SellActivity)
            val app = applicationContext
            graph.appScope.launch(Dispatchers.IO) {
                Work.schedule(app) // background jobs, after the till is usable (WorkManager starts here, off the main thread)
                ErrorReports.atStart(app) // waiting error reports, and how the app last ended (D-057)
                CsvFiles.cleanShared(app) // copies shared earlier (a backup holds the whole shop's data)
                // Test data of a performance run Android ended half-way (hundreds of MB).
                if (PerfDataGenerator.exists(app)) withContext(Dispatchers.Main) { graph.perfRunner.cleanLeftovers() }
                // New versions (D-059): the daily check; once after an update, say so.
                graph.updates.atStart()?.let { version ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, app.getString(R.string.update_done, version), Toast.LENGTH_LONG).show()
                    }
                }
            }
            graph.sync.refreshStatus()
            graph.autoSync.start() // the other tills' changes now, and again when the internet comes back (D-053)
            graph.backups.refreshProtection()
        }
        s.launch { graph.cart.state.collect { render(it) } }
        s.launch { graph.staff.state.collect { renderStaff(it) } }
        s.launch { combine(graph.updates.status, graph.staff.state) { u, st -> u to st }.collect { renderUpdate(it.first, it.second) } }
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
        s.launch { graph.sppScanner.codes.collect { if (resumed) onScanned(it) } }
        s.launch { graph.checkout.last.collect { renderLastSale(it) } }
        s.launch { graph.catalogChanges.drop(1).collect { loadCategories(refresh = true) } } // read below at once
        s.launch {
            graph.checkout.outcome.collect {
                showOutcome(it)
                if (it != null) graph.backups.refreshProtection() // the first sale makes "not backed up" possible
            }
        }
        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Many phones only pause the app while their screen is off: waking them calls onResume,
        // not onStart, and the till stayed signed in until the first tap (reported on 1.3.0).
        graph.staff.lockIfIdle()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /**
     * In front: a serial scanner's code goes to the screen in front only. A screen opened over this one
     * starts before this one stops, and a code scanned in between went to both (counted on Count and
     * put on the bill, 2026-10 review).
     */
    private var resumed = false

    /**
     * Error reports (D-057): asked once, of someone who may change settings, with no bill open;
     * otherwise again the next time this screen starts.
     */
    private suspend fun askAboutReports() {
        val app = applicationContext
        if (withContext(Dispatchers.IO) { ErrorReports.consent(app) } != ErrorReports.UNASKED) {
            reportsChecked = true
            return
        }
        if (!maySetUp(graph.staff.state.value) || graph.cart.state.value.cart.items.isNotEmpty() || isFinishing) return
        reportsChecked = true
        ReportsChoice.ask(this)
    }

    /** Whoever is signed in may change settings (or the till has no sign-in). */
    private fun maySetUp(staff: StaffSession.State): Boolean = staff.loaded && !staff.locked &&
        (!staff.loginRequired || staff.current?.let { Perm.has(it.perms, Perm.SETTINGS) } == true)

    /** "Update 1.6.0" while a newer version is known, for someone who may install it (D-059). */
    private fun renderUpdate(u: AppUpdates.Status, staff: StaffSession.State) {
        val update = u.update
        updatePill.text = update?.let { getString(R.string.update_pill, it.version) }
        updatePill.visible(update != null && u.refused == null && u.selfUpdate && maySetUp(staff))
    }

    override fun onStop() {
        started?.cancel()
        started = null
        graph.staff.screenStopped()
        graph.sppScanner.release(this) // kept for a screen opened over this one that takes scans
        scanInput.clear()
        super.onStop()
    }

    override fun onDestroy() {
        if (!counted) {
            // A second selling screen closed at once in onCreate: the bill, its prompt and its payment
            // are the live screen's (clearing the prompt here lost the other's weight being asked).
            scope.cancel()
            super.onDestroy()
            return
        }
        // Only a screen that is finishing drops a weight or price being asked (see keepPrompt).
        destroying = isChangingConfigurations || !isFinishing
        if (!destroying) graph.cart.prompt = null
        // Rebuilt mid-payment (a tablet turned: Android 16 ignores the orientation lock on large
        // screens): the payment stays open with what was already taken, and the new screen shows
        // it again (resumePayment). So also when Android destroys the screen to save memory while the
        // cashier checks a transfer in a bank app (it lost the card and e-wallet parts taken, 2026-10
        // review); only a screen that is finishing ends the payment.
        if (isChangingConfigurations || !isFinishing) paymentDialog?.setOnDismissListener(null)
        paymentDialog?.dismiss()
        // Keep the outcome: after a rotation the new screen shows the same result again.
        outcomeDialog?.setOnDismissListener(null)
        outcomeDialog?.dismiss()
        dialogs.dismissAll()
        scope.cancel()
        beeper?.release()
        // The window outlives a screen rebuilt after a change (Android 7.0+): its watcher must go.
        KeyboardTip.unwatch(this, keyboardTip)
        keyboardTip = null
        if (counted) alive--
        super.onDestroy()
    }

    /** This instance is one of the [alive] selling screens (not one closed at once in onCreate). */
    private var counted = false

    /** This screen's [KeyboardTip] watcher, removed when it is destroyed. */
    private var keyboardTip: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

    override fun track(d: android.app.Dialog) = dialogs.track(d)

    private val starts = LaunchGuard()

    /** A screen asked for twice by a double tap (the camera, a menu entry) opens once (ScreenActivity, 2026-10 review). */
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (starts.allow(intent)) super.startActivityForResult(intent, requestCode, options)
    }

    private fun showFailure(e: Throwable) {
        // A refusal is the rule working, not a bug: no error report (D-057).
        if (e is ActionRefused) Log.w("Selling screen action refused: ${e.reason}") else Log.e("Selling screen job failed", e)
        if (isFinishing || isDestroyed || failureDialog?.isShowing == true) return // one message is enough
        val text = if (e is Exception) {
            ScreenActivity.errorText(this, e)
        } else {
            getString(R.string.error_generic, e.javaClass.simpleName)
        }
        failureDialog = Dialogs.message(this, getString(R.string.error_title), text)
    }

    /**
     * Bill work started here that must finish even if the screen closes (app scope); a failure is
     * shown if the screen is still there, and never crashes the till.
     */
    private fun billJob(block: suspend () -> Unit) {
        graph.appScope.launch(Dispatchers.Main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure(e)
            }
        }
    }

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
        scanInput.clear() // the start of a scan that woke the till is not kept for the next person
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
        printOnce.run { printReceipt(done.saleId) }
    }

    /** A double tap on Print queued a copy too (with its audit entry, or a manager's PIN asked). */
    private val printOnce = TapOnce()

    /**
     * Prints a receipt of [saleId]. SaleActions.print tells the first one from a copy by the print
     * queue: the first prints as is; a copy needs REPRINT, else a manager's PIN is asked for. (Asked
     * for every time, a first receipt — automatic printing off — was a "copy" needing a manager,
     * 2026-10 review.)
     */
    private fun printReceipt(saleId: Long, onQueued: () -> Unit = {}) {
        scope.launch {
            try {
                graph.sales.print(saleId, copy = false)
                onQueued()
            } catch (copyNeedsPermission: ActionRefused) {
                withApproval(graph, scope, Perm.REPRINT) { approval ->
                    scope.launch {
                        try {
                            graph.sales.print(saleId, copy = true, approval = approval)
                            onQueued()
                        } catch (e: ActionRefused) {
                            notAllowed() // other failures are said as they are (the screen's handler)
                        }
                    }
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
            p.state == BackupService.Protection.State.DAMAGED ->
                R.string.safety_pill_damaged to { open(BackupActivity::class.java) }
            // Sync that stopped first: on a phone that stays nearly full for weeks the storage pill hid
            // "Sign in" and the other tills stopped getting this till's sales unseen (2026-10 review).
            s.enabled && s.needsSignIn -> R.string.sync_pill_sign_in to { open(SyncActivity::class.java) }
            stale -> R.string.sync_pill_stale to { open(SyncActivity::class.java) }
            // Before sales stop being saved and while the daily backup has no room (2026-10 review).
            p.storageLow -> R.string.storage_pill to { explainStorage(p.freeBytes) }
            risk && s.enabled -> R.string.sync_pill_stale to { open(SyncActivity::class.java) }
            risk -> R.string.safety_pill_at_risk to { explainAtRisk() }
            else -> null
        }
        syncState.text = pill?.let { getString(it.first) }
        syncState.setOnClickListener { pill?.second?.invoke() }
        syncState.visible(pill != null)
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    /** The phone is nearly full: what stops working, and what to delete. */
    private fun explainStorage(free: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_low_title)
            .setMessage(getString(R.string.storage_low_message, Storage.text(free)))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.backup_title) { _, _ -> open(BackupActivity::class.java) }
            .show()
            .trackedBy(this)
    }

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
        // Fewer, wider tiles at a large font: names and prices were cut (2026-10 review).
        val scale = resources.configuration.fontScale.coerceAtLeast(1f)
        return (width / (150f * scale)).toInt().coerceIn(2, 6)
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
        // Back with the keyboard up closes the keyboard first: on Android 13–14 this screen's Back came
        // before the keyboard's, and the search typed so far was wiped (2026-10 review).
        if (Build.VERSION.SDK_INT >= 30 && Ime30.visible(this)) {
            hideKeyboard()
            return true
        }
        catalogOpen = false
        if (search.text.isNotEmpty()) clearSearch() else showPanels()
        return true
    }

    @RequiresApi(30)
    private object Ime30 {
        fun visible(a: Activity): Boolean = a.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
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
        if (graph.staff.activity()) return true // idle too long: locked; this key was meant for whoever was signed in
        graph.sppScanner.poke() // the till is in use: a serial scanner that dropped out is tried again now
        val scanned = if (search.hasFocus()) scanIntoSearch(event) else scanKey(event)
        return backKey(event) || scanned || super.dispatchKeyEvent(event)
    }

    /** What a scanner "typed" into the focused search field since the last pause (see [scanIntoSearch]). */
    private val fieldBurst = ScanBuffer()

    /**
     * A scanner fired while the search field had focus: its digits were typed into the field
     * ("milo9556001234567") and the item was lost. The field still gets every key; when a burst at
     * scanner speed ends with Enter, it is taken out of the field again and handled as the scan.
     */
    private fun scanIntoSearch(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                val r = fieldBurst.onTerminator() as? ScanBuffer.Result.Scan ?: return false
                clearSearch()
                onScanned(r.code)
                return true
            }
        }
        val ch = scanChar(e)
        if (ch > 0x1F && ch and KeyCharacterMap.COMBINING_ACCENT == 0) {
            if (fieldBurst.isIdle(e.eventTime)) fieldBurst.clear() // a person's earlier typing is not part of a scan
            fieldBurst.onChar(ch.toChar(), e.eventTime)
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (graph.staff.activity()) return true // idle too long: locked (the sign-in shows)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            graph.sppScanner.poke() // the till is in use: a serial scanner that dropped out is tried again now
            shielded = SystemClock.uptimeMillis() < shieldUntil
        }
        if (shielded) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) shielded = false
            return true // the whole touch is dropped
        }
        return super.dispatchTouchEvent(ev)
    }

    private var shieldUntil = 0L
    private var shielded = false

    /**
     * What is under the finger just changed (a search result picked: the catalogue comes back; the
     * sale's result closed): the second tap of a double tap is dropped. It added whatever tile now
     * sat there — to the next customer's bill (2026-10 review).
     */
    private fun shieldTaps() {
        shieldUntil = SystemClock.uptimeMillis() + SHIELD_MS
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
        if (graph.staff.activity()) return // scanning is using the till (serial and camera scanners never touch the screen)
        priceCheck?.takeIf { it.isShowing }?.let {
            it.lookup(code) // price check open: look up, never add to the bill
            return
        }
        if (graph.checkout.outcome.value != null) {
            graph.checkout.acknowledge() // next customer
            if (graph.staff.lockIfIdle()) return // the idle time ran out during the last payment
        }
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
        val d = AmountDialog(this, getString(R.string.weigh_title, p.name), AmountDialog.Kind.WEIGHT, currency, p.unit) { milli ->
            if (graph.cart.addProduct(p, qty = milli, barcode = code) == 0L) beeper?.error()
        }.show()
        keepPrompt(d, CartSession.Prompt(p, code, weight = true))
    }

    /** [d] asks [prompt]: remembered until it is answered or cancelled, not when the screen is rebuilt. */
    private fun keepPrompt(d: AlertDialog, prompt: CartSession.Prompt) {
        graph.cart.prompt = prompt
        promptDialog = d
        d.setOnDismissListener {
            if (promptDialog === d) promptDialog = null
            if (!destroying) graph.cart.prompt = null
        }
    }

    private var promptDialog: AlertDialog? = null

    /** The weight or price asked before the screen was rebuilt, asked again. */
    private fun resumePrompt() {
        val p = graph.cart.prompt ?: return
        if (promptDialog?.isShowing == true) return // still open (the screen only went out of sight)
        if (!graph.cart.state.value.canEdit || isFinishing) {
            graph.cart.prompt = null
            return
        }
        if (p.weight) askWeight(p.product, p.code) else askPrice(p.product, p.code)
    }

    /** onDestroy has begun: dialogs closing now were not answered by the cashier. */
    private var destroying = false

    private fun askPrice(p: SellableProduct, code: String?) {
        // A product priced 0.00 may be sold for 0.00: a free plastic bag or gift could not be sold at all
        // once a product without a price asked for one (1.7.1) — it is still asked, never sold free
        // unseen. A product priced at the till (kuih, vegetables) still needs a price typed.
        val allowZero = p.sellMode != SellMode.OPEN_PRICE
        val d = AmountDialog(this, getString(R.string.price_for_title, p.name), AmountDialog.Kind.MONEY, currency, allowZero = allowZero) { price ->
            if (graph.cart.addAtPrice(p, price, code) == 0L) beeper?.error()
        }.show()
        keepPrompt(d, CartSession.Prompt(p, code, weight = false))
    }

    /** Price check: scan or search to see price, stock and deals; the bill is not touched. */
    private fun showPriceCheck() {
        val camera: (() -> Unit)? = if (graph.settings.device.value.cameraScan && hasCamera) {
            {
                @Suppress("DEPRECATION")
                startActivityForResult(CameraScanActivity.pickIntent(this), REQ_PRICE_SCAN)
            }
        } else {
            null
        }
        priceCheck = PriceCheckDialog(this, scope, graph.priceCheck, currency, camera) { changePrice(it) }.also { it.show() }
    }

    /**
     * A new price for the product the price check found: a wrong price found at the till, or prices
     * raised after a delivery, one scan each instead of opening every product (2026-10 review). Needs
     * MANAGE_PRODUCTS, else a manager's PIN; audited. The bill's lines keep their price.
     */
    private fun changePrice(info: PriceCheck.Info) {
        withApproval(graph, scope, Perm.MANAGE_PRODUCTS) { approval ->
            val c = currency
            val text = StringBuilder(getString(R.string.price_check_now, money(info.price)))
            if (info.cost > 0L) {
                // The margin of the price now, after the tax it includes (the cost is without tax).
                val inclTax = graph.settings.store.value.pricesIncludeTax
                val netEx = if (inclTax && info.taxBp > 0) info.price - PricingEngine.taxInclusive(info.price, info.taxBp) else info.price
                val margin = ReportMath.marginBp(netEx, info.cost)?.let { MoneyFormat.plain(it.toLong(), 2) + "%" } ?: "-"
                text.append(' ').append(getString(R.string.price_check_cost, money(info.cost), margin))
            }
            AmountDialog(
                this, getString(R.string.price_check_new_title, info.name), AmountDialog.Kind.MONEY, c, initial = info.price,
                message = text.toString(),
            ) { price ->
                billJob {
                    graph.priceCheck.setPrice(info.productId, price, approval)
                    Toast.makeText(this, getString(R.string.price_check_saved, info.name, MoneyFormat.format(price, c)), Toast.LENGTH_SHORT).show()
                    priceCheck?.refresh()
                    if (!isDestroyed) refreshTiles() // the tiles show the new price, the grid keeps its place
                }
            }.show()
        }
    }

    /** Only registered products are sold (D-050): an unknown barcode is registered, then it is on the bill. */
    private fun unknownBarcode(code: String) {
        showUnknownBarcode(this, code) { startActivityForResult(ProductEditActivity.newIntent(this, barcode = code), REQ_NEW_PRODUCT) }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (UpdateUi.onResult(this, scope, requestCode)) return // allowed to install the update (or not)
        if (requestCode == REQ_NEW_PRODUCT && resultCode == RESULT_OK) {
            val id = data?.getLongExtra(ProductEditActivity.EXTRA_PRODUCT_ID, 0L) ?: 0L
            if (id != 0L) addProductById(id)
        }
        if (requestCode == REQ_CUSTOMER && resultCode == RESULT_OK && data != null) {
            val id = data.getLongExtra(CustomersActivity.EXTRA_ID, 0L)
            val name = data.getStringExtra(CustomersActivity.EXTRA_NAME)
            // Android may have ended the app meanwhile: the result comes before the bill is loaded
            // again, and was dropped (2026-10 review). Load first (at once when it is loaded).
            if (id != 0L) {
                scope.launch {
                    graph.cart.load()
                    graph.cart.setCustomer(id, name)
                }
            }
        }
        if (requestCode == REQ_CAMERA_SELL && resultCode == RESULT_OK) {
            // A code the camera could not add by itself: unknown (register it), or it needs a weight
            // or a price. The bill is loaded first (Android may have ended the app meanwhile).
            data?.getStringExtra(CameraScanActivity.EXTRA_CODE)?.let { code ->
                scope.launch {
                    graph.cart.load()
                    onScanned(code)
                }
            }
        }
        if (requestCode == REQ_PRICE_SCAN && resultCode == RESULT_OK) {
            data?.getStringExtra(CameraScanActivity.EXTRA_CODE)?.let { code ->
                // The screen may have been rebuilt meanwhile (rotated while aiming): open the price check again.
                if (priceCheck?.isShowing != true) showPriceCheck()
                priceCheck?.lookup(code)
            }
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
        refreshJob?.cancel()
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

    /** The chips, then the tiles from the top; with [refresh] (another till or an import changed them) the tiles where they are. */
    private fun loadCategories(refresh: Boolean = false) {
        val s = started ?: return
        s.launch {
            val cats = graph.db().read { CategoryDao.list(it) }
            val popular = graph.popular.load().isNotEmpty()
            val chips = ArrayList<Category>(cats.size + 2)
            if (popular) chips.add(Category(CategoryChipAdapter.POPULAR, getString(R.string.sell_popular)))
            chips.add(Category(CategoryChipAdapter.ALL, getString(R.string.sell_all)))
            chips.addAll(cats)
            val before = selectedCategory
            // Best sellers first once the shop has sold something; the owner's choice is kept after that.
            if (!refresh && !categoryChosen && popular) selectedCategory = CategoryChipAdapter.POPULAR
            if (chips.none { it.id == selectedCategory }) selectedCategory = CategoryChipAdapter.ALL
            categoryAdapter.submit(chips)
            categoryAdapter.selected = selectedCategory
            if (!refresh || selectedCategory != before) loadCatalog(reset = true) else refreshTiles()
        }
    }

    /**
     * The tiles read again where they are: after a sale (their stock), and when another till or an
     * import changed products (2026-10 review: the tiles kept the stock, prices and names of when the
     * screen opened, all day on a till that never leaves it). At the top, the first page again (new
     * products show); further down, the tiles around those in view, and the grid keeps its place.
     * Search results are read again with the next keystroke.
     */
    private fun refreshTiles() {
        if (search.text.isNotEmpty()) return
        refreshJob?.cancel() // one that read before the latest change
        val grid = productGrid.layoutManager as? GridLayoutManager ?: return
        val items = productAdapter.items
        val n = items.size
        val first = grid.findFirstVisibleItemPosition()
        // Hidden (the bill pane on a phone), its positions are those of the list before the last reload:
        // read from the top again, unseen.
        if (n == 0 || first <= 0 || first >= n || !productGrid.isShown) return loadCatalog(reset = true)
        val last = grid.findLastVisibleItemPosition().coerceIn(first, n - 1)
        val ids = items.subList((first - REFRESH_AROUND).coerceAtLeast(0), (last + REFRESH_AROUND).coerceAtMost(n - 1) + 1).map { it.id }
        refreshJob = scope.launch {
            val fresh = graph.db().read { ProductDao.listByIds(it, ids) }
            if (search.text.isNotEmpty()) return@launch // search results replaced the tiles meanwhile
            productAdapter.refresh(ids, fresh.associateBy { it.id })
            catalogEmpty.visible(productAdapter.itemCount == 0)
        }
    }

    /** The in-place tile refresh ([refreshTiles]); a reload or a search cancels it. */
    private var refreshJob: Job? = null

    /**
     * The last tile of the pages read (the keyset of the next page): an in-place refresh may rename the
     * last tile shown, and the next page started after its new name, skipping or repeating products.
     */
    private var catalogCursor: ProductListItem? = null

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
        if (reset) refreshJob?.cancel()
        val category = selectedCategory
        val after = if (reset) null else catalogCursor
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
                catalogCursor = page.lastOrNull()
            } else {
                productAdapter.append(page)
                page.lastOrNull()?.let { catalogCursor = it }
            }
            catalogEnd = popular || page.size < PAGE
            catalogEmpty.text = getString(R.string.sell_no_products)
            catalogEmpty.visible(productAdapter.itemCount == 0)
        }
    }

    /** A tile: added to the bill. Picked from search results, the search closes and the bill shows again. */
    private fun tapProduct(item: ProductListItem) {
        addProductById(item.id)
        if (search.text.isNotEmpty()) {
            clearSearch()
            shieldTaps()
        }
    }

    private fun addProductById(id: Long) {
        scope.launch {
            graph.cart.load() // a product just added, after Android ended the app meanwhile (see onActivityResult)
            val p = graph.db().read { ProductDao.sellableById(it, id) } ?: return@launch
            when (p.sellMode) {
                SellMode.WEIGHT -> askWeight(p, null)
                SellMode.OPEN_PRICE -> askPrice(p, null)
                // No price yet: asked, never sold free (2026-10 review).
                else -> if (p.price == 0L) askPrice(p, null) else if (graph.cart.addProduct(p) != 0L) beeper?.ok() else beeper?.error()
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

    override fun changeQty(item: CartItem, delta: Long) = graph.cart.changeQty(item.key, delta)

    override fun more(item: CartItem) {
        if (!graph.cart.state.value.canEdit) return
        showLineMore(this, item, onQty = { enterQty(item) }, onDiscount = { lineDiscount(item) }, onPrice = { linePrice(item) })
    }

    override fun enterQty(item: CartItem) {
        if (item.sellMode == SellMode.WEIGHT) {
            AmountDialog(this, getString(R.string.line_weight), AmountDialog.Kind.WEIGHT, currency, item.unit, item.qty) {
                if (!graph.cart.setQty(item.key, it)) beeper?.error()
            }.show()
        } else {
            AmountDialog(this, getString(R.string.line_qty), AmountDialog.Kind.PIECES, currency, initial = item.qty) {
                if (!graph.cart.setQty(item.key, it)) beeper?.error()
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

    /** A double tap opens one dialog, not two stacked ones. */
    private var dialogTapAt = 0L

    private fun firstTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - dialogTapAt < DOUBLE_TAP_MS) return false
        dialogTapAt = now
        return true
    }

    private fun hold() {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty || !firstTap()) return
        Dialogs.input(this, getString(R.string.held_hold_title), getString(R.string.held_label_hint)) { label ->
            billJob { graph.cart.hold(label) } // a failed write keeps the bill on the screen and says so
            true
        }
    }

    private fun showHeld() {
        if (!firstTap()) return
        scope.launch {
            val bills = graph.cart.heldBills()
            showHeldBills(
                this@SellActivity, bills, currency,
                onResume = { id -> billJob { graph.cart.resume(id) } },
                onDelete = { id ->
                    // Throwing a parked bill away is cancelling a bill: same permission, same audit entry.
                    withApproval(graph, scope, Perm.CANCEL_BILL) { approval ->
                        billJob { if (!graph.cart.deleteHeld(id, approval) && !isDestroyed) notAllowed() }
                    }
                },
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
                } catch (e: ActionRefused) {
                    notAllowed() // other failures are said as they are (the screen's handler)
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

    /**
     * Pay was pressed and its checks are reading the database (the clock, the payment methods): the
     * bill only freezes once they are done, so a second tap meanwhile opened a second payment
     * dialog — after the sale the stale one was still there, and paying in it failed (2026-10 review).
     */
    private var paymentOpening = false

    private fun openPayment(clockChecked: Boolean = false) {
        val st = graph.cart.state.value
        if (!st.canEdit || st.cart.isEmpty || paymentOpening || paymentDialog != null) return
        val store = graph.settings.store.value
        if (store.shiftRequired && graph.shifts.current.value == null) {
            Dialogs.confirm(this, getString(R.string.shift_needed_title), getString(R.string.shift_needed), getString(R.string.shift_open)) {
                openShift(this, graph, scope) { openPayment() }
            }
            return
        }
        paymentOpening = true
        scope.launch {
            try {
                if (!clockChecked) {
                    // The sale is filed for good under the phone's date (ClockCheck, 2026-10 review).
                    val now = System.currentTimeMillis()
                    val db = graph.db()
                    val last = db.read { SaleDao.lastOwnSoldAt(it, db.deviceNo) }
                    when (ClockCheck.verdict(now, last)) {
                        ClockCheck.Verdict.OK -> Unit
                        ClockCheck.Verdict.WRONG -> {
                            wrongClock(getString(R.string.clock_wrong, DateText.dateTime(now, TimeZone.getDefault())), sellAnyway = null)
                            return@launch
                        }
                        ClockCheck.Verdict.SUSPECT -> {
                            val tz = TimeZone.getDefault()
                            val text = getString(R.string.clock_suspect, DateText.dateTime(now, tz), DateText.dateTime(last ?: now, tz))
                            wrongClock(text) { openPayment(clockChecked = true) }
                            return@launch
                        }
                    }
                }
                val credit = store.creditEnabled && graph.cart.state.value.customerId != null
                val methods = graph.db().read { PaymentMethodDao.active(it) }.filter { it.kind != PaymentKind.CREDIT || credit }
                // Priced with today's promotions: a bill rung up before midnight kept a deal that had
                // ended, or missed one that started (2026-10 review).
                graph.cart.reprice()
                val now = graph.cart.state.value
                if (!now.canEdit || now.cart.isEmpty || paymentDialog != null) return@launch
                graph.cart.setPaying(true)
                val draft = CartSession.PaymentDraft(now.priced.total, methods)
                graph.cart.payment = draft
                showPayment(draft)
            } finally {
                paymentOpening = false
            }
        }
    }

    /** The phone's date or time looks wrong: set it in Android's settings ([sellAnyway]: or go on). */
    private fun wrongClock(message: String, sellAnyway: (() -> Unit)?) {
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.clock_wrong_title)
            .setMessage(message)
            .setPositiveButton(R.string.clock_settings) { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS))
                } catch (e: android.content.ActivityNotFoundException) {
                    Log.w("No date settings screen", e)
                }
            }
            .setNegativeButton(R.string.cancel, null)
        if (sellAnyway != null) b.setNeutralButton(R.string.clock_sell_anyway) { _, _ -> sellAnyway() }
        b.show().trackedBy(this)
    }

    /**
     * The payment dialog for [draft]. What it takes is kept in the draft (app-scoped), so a screen
     * rebuilt mid-payment shows the same payment again ([resumePayment], 2026-10 review).
     */
    private fun showPayment(draft: CartSession.PaymentDraft) {
        // Turning the phone would rebuild the screen (a large screen on Android 16 turns anyway).
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val d = PaymentDialog(
            this, draft.total, currency, draft.methods, draft.tenders,
            onTenders = { draft.tenders = it },
            authorize = { m, amount, done -> authorize(draft, m, amount, done) },
        ) { tenders, rounding ->
            graph.checkout.start(tenders, rounding, draft.approvals.toList())
        }.show()
        d.setOnDismissListener {
            graph.cart.setPaying(false) // also forgets the draft
            paymentDialog = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        paymentDialog = d
    }

    /** The screen came back while a payment was open: the payment shows again where it was. */
    private fun resumePayment() {
        val st = graph.cart.state.value
        if (!st.paying || paymentDialog != null || st.busy) return
        val draft = graph.cart.payment
        if (draft == null || draft.total != st.priced.total) {
            graph.cart.setPaying(false) // nothing to show again: unfreeze the bill
            return
        }
        showPayment(draft)
    }

    /**
     * A credit tender needs the bill's customer, the CREDIT_SALE permission (or a manager) and,
     * over the customer's limit, a manager's CREDIT_LIMIT approval (D-039).
     */
    private fun authorize(draft: CartSession.PaymentDraft, m: PaymentMethod, amount: Long, done: (Boolean) -> Unit) {
        if (m.kind != PaymentKind.CREDIT) return done(true)
        val customerId = graph.cart.state.value.customerId
        if (customerId == null) {
            Dialogs.message(this, null, getString(R.string.error_needs_customer))
            return done(false)
        }
        withApproval(graph, scope, Perm.CREDIT_SALE) { saleApproval ->
            saleApproval?.let { draft.approvals.add(it) }
            scope.launch {
                val loaded = graph.customers.get(customerId) ?: return@launch done(false)
                val (c, balance) = loaded
                if (!CreditMath.overLimit(balance, draft.creditTaken + amount, c.creditLimit)) {
                    draft.creditTaken += amount
                    done(true)
                    return@launch
                }
                val msg = getString(R.string.credit_over_limit, c.name, money(balance), money(c.creditLimit), money(amount))
                Dialogs.confirm(this@SellActivity, getString(R.string.credit_over_limit_title), msg, getString(R.string.credit_allow)) {
                    withApproval(graph, scope, Perm.CREDIT_LIMIT) { limitApproval ->
                        limitApproval?.let { draft.approvals.add(it) }
                        draft.creditTaken += amount
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
            is CheckoutService.Outcome.Completed -> {
                outcomeDialog = showResult(o.done)
                refreshTiles() // the stock they show
            }
            is CheckoutService.Outcome.Refused -> {
                val d = Dialogs.message(this, getString(R.string.pay_failed_title), ScreenActivity.errorText(this, ActionRefused(o.reason)))
                d.setOnDismissListener { graph.checkout.acknowledge() }
                outcomeDialog = d
            }
            is CheckoutService.Outcome.Failed -> {
                val d = AlertDialog.Builder(this)
                    .setTitle(R.string.pay_failed_title)
                    .setMessage(if (o.storageFull) getString(R.string.pay_failed_storage) else getString(R.string.pay_failed, o.error))
                    .setPositiveButton(R.string.ok, null)
                    .create()
                // A scanner's Enter must not press OK: the message would be gone unread (2026-10 review).
                d.keys { e -> DialogKeys.pressesFocused(e.keyCode) }
                d.setOnDismissListener { graph.checkout.acknowledge() }
                d.show()
                outcomeDialog = d
            }
        }
    }

    private fun showResult(done: CheckoutService.Done): AlertDialog {
        @SuppressLint("InflateParams") // a dialog's view has no parent to inflate into
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
        val d = AlertDialog.Builder(this).setView(Dialogs.scrolling(v)).create()
        val print = v.findViewById<Button>(R.id.result_print)
        print.visible(device.hasPrinter)
        print.setText(if (done.receiptQueued) R.string.result_print_again else R.string.result_print)
        print.setOnClickListener { printOnce.run { printReceipt(done.saleId) { print.isEnabled = false } } }
        v.findViewById<View>(R.id.result_share).setOnClickListener { ReceiptShare.chooseAndShare(this, done.saleId) }
        v.findViewById<View>(R.id.result_new).setOnClickListener { d.dismiss() }
        d.setOnDismissListener {
            graph.checkout.acknowledge()
            shieldTaps()
        }
        d.forwardKeys { e -> scanKey(e) }
        d.show()
        return d
    }

    companion object {
        /** Selling screens not destroyed yet (main thread). */
        private var alive = 0

        private const val REQ_NEW_PRODUCT = 1
        private const val REQ_PRICE_SCAN = 7
        private const val REQ_CAMERA_SELL = 8
        private const val REQ_CUSTOMER = 2
        private const val IDLE_CHECK_MS = 15_000L
        private const val DOUBLE_TAP_MS = 600L
        private const val SHIELD_MS = 400L
        private const val PAGE = 60

        /** Tiles read again on each side of those in view ([refreshTiles]). */
        private const val REFRESH_AROUND = 60
        private const val SEARCH_DEBOUNCE_MS = 150L
        private const val HARDWARE_DELAY_MS = 1500L
        private const val SYNC_STALE_MS = 24L * 60L * 60L * 1000L
    }
}
