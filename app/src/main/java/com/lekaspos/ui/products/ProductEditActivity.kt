package com.lekaspos.ui.products

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.MovementKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.catalog.TaxRate
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.data.product.Barcode
import com.lekaspos.data.product.Product
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductLook
import com.lekaspos.data.product.ProductLookDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.FieldScan
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.inventory.StockHistoryActivity
import com.lekaspos.ui.inventory.adjustProduct
import com.lekaspos.ui.scan.CameraScanActivity

/**
 * Add or edit a product: name, price, how it is sold, barcodes (including pack/carton
 * barcodes and the scale PLU), category, tax, cost, SKU, stock tracking. Saved as LWW edits of
 * the changed fields only; price changes are audited.
 */
class ProductEditActivity : ScreenActivity() {

    /** A barcode row being edited; [id] null = not saved yet. */
    private data class Code(val id: Long?, val code: String, val kind: Int, val packQty: Long, val packPrice: Long?)

    private var productId = 0L
    private var original: Product? = null

    /** The product as the untouched form reads back (the baseline for "what the user changed"). */
    private var shown: Product? = null
    private var originalCodes: List<Barcode> = emptyList()

    /** A save is running: a second tap on Save must not create the product twice. */
    private var saving = false
    private val codes = ArrayList<Code>()
    private val scannedEarly = ArrayList<String>()
    private var categories: List<Category> = emptyList()
    private var taxes: List<TaxRate> = emptyList()

    private lateinit var form: Form
    private val look = LookEditor(this)
    private lateinit var name: EditText
    private lateinit var price: EditText
    private lateinit var sellMode: Spinner
    private lateinit var unit: EditText
    private lateinit var codeList: LinearLayout
    private lateinit var plu: EditText
    private lateinit var category: Spinner
    private lateinit var tax: Spinner
    private lateinit var cost: EditText
    private lateinit var sku: EditText
    private lateinit var trackStock: Switch
    private lateinit var lowStock: EditText
    private lateinit var active: Switch
    private lateinit var stockInfo: TextView
    private var opening: EditText? = null

    private val currency get() = graph.settings.store.value.currency

    /** What was typed before Android ended the app (another app opened to check a price), put back once loaded. */
    private var typed: Bundle? = null

    /** The form as first shown (before typing): only fields that differ from it are put back. */
    private var shownForm: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        productId = intent.getLongExtra(EXTRA_PRODUCT_ID, 0L)
        typed = savedInstanceState?.getBundle(STATE_FORM)
        shownForm = savedInstanceState?.getBundle(STATE_SHOWN)
        look.restore(savedInstanceState)
        setScreen(getString(if (productId == 0L) R.string.product_new else R.string.product_edit))
        launchUi { load() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val now = if (::form.isInitialized) form.save() else typed
        now?.let { outState.putBundle(STATE_FORM, it) }
        shownForm?.let { outState.putBundle(STATE_SHOWN, it) }
        look.save(outState)
    }

    private suspend fun load() {
        val id = productId
        val data = graph.db().read { r ->
            val codes = if (id != 0L) ProductDao.barcodes(r, id) else emptyList()
            Loaded(
                product = if (id != 0L) ProductDao.get(r, id) else null,
                codes = codes,
                categories = CategoryDao.list(r),
                taxes = TaxRateDao.list(r),
                stock = if (id != 0L) StockDao.level(r, id) else null,
                look = if (id != 0L) ProductLookDao.get(r, id) else ProductLook(),
                // A barcode another product has too (e.g. added on two tills while offline).
                shared = codes.filter { it.kind == BarcodeKind.BARCODE }.mapNotNull { b ->
                    ProductDao.codeOwners(r, b.code, id).firstOrNull()?.let { b.code to it.second }
                },
            )
        }
        original = data.product
        originalCodes = data.codes
        categories = data.categories
        taxes = data.taxes
        codes.clear()
        for (b in data.codes) codes.add(Code(b.id, b.code, b.kind, b.packQty, b.packPrice))
        intent.getStringExtra(EXTRA_BARCODE)?.let(Gtin::canonical)?.takeIf { it.isNotBlank() && codes.none { c -> c.code == it } }?.let {
            codes.add(Code(null, it, BarcodeKind.BARCODE, 1000L, null))
        }
        // A code scanned with the camera before the form had loaded (Android ended the app meanwhile):
        // clearing the codes for the loaded ones dropped it (2026-10 review).
        for (c in scannedEarly) if (codes.none { it.code == c }) codes.add(Code(null, c, BarcodeKind.BARCODE, 1000L, null))
        scannedEarly.clear()
        build(data.product, data.stock, data.shared, data.look)
        shown = data.product?.let { formProduct() ?: it }
        // After [shown]: it is what the screen first showed (the edit's "before"), the typed values are
        // the edit. Only into the same fields (the form can differ, e.g. the opening stock field).
        // A price typed here was lost when Android ended the app meanwhile (2026-10 review).
        // Only what the user changed: a field another till changed meanwhile keeps that change.
        val fresh = form.save()
        val before = shownForm
        val t = typed
        if (t != null && before != null && t.size() == fresh.size()) {
            for (k in t.keySet()) {
                @Suppress("DEPRECATION") // Bundle.get: the form keeps strings, booleans and ints
                val v = t.get(k)
                @Suppress("DEPRECATION")
                if (v == before.get(k)) continue
                when (v) {
                    is String -> fresh.putString(k, v)
                    is Boolean -> fresh.putBoolean(k, v)
                    is Int -> fresh.putInt(k, v)
                }
            }
            form.restore(fresh)
        }
        typed = null
        if (shownForm == null || t == null) shownForm = form.save()
    }

    private class Loaded(
        val product: Product?,
        val codes: List<Barcode>,
        val categories: List<Category>,
        val taxes: List<TaxRate>,
        val stock: Long?,
        val look: ProductLook,
        /** Barcode → name of another product that uses it. */
        val shared: List<Pair<String, String>>,
    )

    private fun build(p: Product?, stock: Long?, shared: List<Pair<String, String>>, shownLook: ProductLook) {
        form = Form(this)
        name = form.text(getString(R.string.product_name), p?.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        price = form.text(getString(R.string.product_price), p?.let { MoneyFormat.format(it.price, currency, withSymbol = false) }, MONEY_INPUT)
        sellMode = form.choice(
            getString(R.string.product_sell_mode),
            listOf(getString(R.string.sell_mode_unit), getString(R.string.sell_mode_weight), getString(R.string.sell_mode_open)),
            p?.sellMode ?: SellMode.UNIT,
        ) { mode ->
            if (mode == SellMode.WEIGHT && unit.text.toString().trim() == UNIT_PCS) unit.setText(UNIT_KG)
            if (mode != SellMode.WEIGHT && unit.text.toString().trim() == UNIT_KG) unit.setText(UNIT_PCS)
        }
        unit = form.text(getString(R.string.product_unit), p?.unit ?: "pcs", InputType.TYPE_CLASS_TEXT)

        // Its colour and picture on the selling screen (D-066).
        form.section(getString(R.string.look_section))
        form.add(look.build(shownLook))

        form.section(getString(R.string.product_barcodes))
        codeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.add(codeList)
        renderCodes()
        // Not baseline-aligned: when a label wraps (longer in Malay), both buttons stay level and equally tall.
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val add = Button(this, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
            text = getString(R.string.product_barcode_add)
            setOnClickListener { addBarcode() }
        }
        val pack = Button(this, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
            text = getString(R.string.product_pack_add)
            setOnClickListener { addPack() }
        }
        buttons.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        buttons.addView(pack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(8) })
        form.add(buttons)
        for ((code, other) in shared) form.info(getString(R.string.product_barcode_shared, code, other))
        plu = form.text(getString(R.string.product_plu), codes.firstOrNull { it.kind == BarcodeKind.SCALE_PLU }?.code, InputType.TYPE_CLASS_NUMBER)

        form.section(getString(R.string.product_more))
        // A new product after "Save and add another": the last one's category and tax (a shelf is entered in a row).
        val likeCategory = if (p == null) intent.getLongExtra(EXTRA_CATEGORY, 0L) else 0L
        val likeTax = if (p == null) intent.getLongExtra(EXTRA_TAX, 0L) else 0L
        category = form.choice(
            getString(R.string.product_category),
            listOf(getString(R.string.product_no_category)) + categories.map { it.name },
            categories.indexOfFirst { it.id == (p?.categoryId ?: likeCategory) } + 1,
        )
        tax = form.choice(
            getString(R.string.product_tax),
            listOf(getString(R.string.product_no_tax)) + taxes.map { "${it.name} ${ReceiptLayout.percent(it.rateBp)}" },
            taxes.indexOfFirst { it.id == (p?.taxRateId ?: likeTax) } + 1,
        )
        cost = form.text(getString(R.string.product_cost), p?.let { MoneyFormat.format(it.cost, currency, withSymbol = false) }, MONEY_INPUT)
        sku = form.text(getString(R.string.product_sku), p?.sku, InputType.TYPE_CLASS_TEXT)
        trackStock = form.switch(getString(R.string.product_track_stock), p?.trackStock ?: true)
        lowStock = form.text(
            getString(R.string.product_low_stock), p?.lowStock?.takeIf { it > 0L }?.let { MoneyFormat.formatQty(it) }, QTY_INPUT,
        )
        stockInfo = form.info(if (stock != null) getString(R.string.product_stock_now, MoneyFormat.formatQty(stock)) else "")
        if (p == null) {
            // Stock is set by whoever may receive, adjust and count it (as for a CSV import, D-054).
            if (graph.permissions.allowed(Perm.MANAGE_STOCK)) {
                opening = form.text(getString(R.string.product_opening_stock), null, QTY_INPUT, hint = getString(R.string.product_opening_hint))
            }
        } else {
            form.button(getString(R.string.hist_title)) { startActivity(StockHistoryActivity.intent(this, p.id)) }
            form.button(getString(R.string.inv_adjust)) { adjustProduct(p.id) { reloadStock(p.id) } }
        }
        active = form.switch(getString(R.string.product_active), p?.active ?: true)
        form.button(getString(R.string.save), primary = true) { save() }
        if (p == null && intent.getBooleanExtra(EXTRA_SERIES, false)) form.button(getString(R.string.product_save_next)) { save(next = true) }
        if (p != null) form.button(getString(R.string.delete)) { delete() }
        content.removeAllViews()
        content.addView(form.view)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun reloadStock(id: Long) {
        launchUi {
            val stock = graph.db().read { StockDao.level(it, id) }
            stockInfo.text = getString(R.string.product_stock_now, MoneyFormat.formatQty(stock))
        }
    }

    // ------------------------------------------------------------------ barcodes

    private fun renderCodes() {
        codeList.removeAllViews()
        for (c in codes.filter { it.kind == BarcodeKind.BARCODE }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
            }
            val label = TextView(this, null, 0, R.style.Text_Lekas_Body).apply {
                text = if (c.packQty == 1000L) {
                    c.code
                } else {
                    val packPrice = c.packPrice?.let { MoneyFormat.format(it, currency) } ?: getString(R.string.product_pack_auto_price)
                    getString(R.string.product_pack_row, c.code, MoneyFormat.formatQty(c.packQty), packPrice)
                }
            }
            val remove = ImageButton(this, null, 0, R.style.Widget_Lekas_IconButton).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = getString(R.string.remove)
                setOnClickListener {
                    codes.remove(c)
                    renderCodes()
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)))
            codeList.addView(row)
        }
        if (codeList.childCount == 0) {
            codeList.addView(TextView(this, null, 0, R.style.Text_Lekas_Caption).apply { text = getString(R.string.product_no_barcodes) })
        }
    }

    private fun addBarcode() {
        Dialogs.input(
            this, getString(R.string.product_barcode_add), getString(R.string.product_barcode_hint),
            inputType = InputType.TYPE_CLASS_TEXT,
            neutral = getString(R.string.product_scan) to {
                @Suppress("DEPRECATION")
                startActivityForResult(CameraScanActivity.pickIntent(this), REQ_SCAN)
            },
        ) { typed ->
            // Stored in the form a scan finds (spaces of the printed digits, a 14-digit GTIN, 2026-10 review).
            val code = Gtin.canonical(typed)
            if (code.isEmpty()) return@input false
            if (codes.none { it.code == code && it.kind == BarcodeKind.BARCODE }) codes.add(Code(null, code, BarcodeKind.BARCODE, 1000L, null))
            renderCodes()
            true
        }
    }

    private fun addPack() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: Int, type: Int) = EditText(this).apply {
            this.hint = getString(hint)
            inputType = type
            setSingleLine(true)
            col.addView(this)
        }
        val code = field(R.string.product_barcode_hint, InputType.TYPE_CLASS_TEXT)
        val qty = field(R.string.product_pack_qty, InputType.TYPE_CLASS_NUMBER)
        val packPrice = field(R.string.product_pack_price, MONEY_INPUT)
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.product_pack_add)
            .setView(col)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val c = Gtin.canonical(code.text.toString())
            val pieces = qty.text.toString().trim().toLongOrNull()
            val priceText = packPrice.text.toString().trim()
            val pp = if (priceText.isEmpty()) null else MoneyFormat.parse(priceText, currency)
            when {
                c.isEmpty() -> code.error = getString(R.string.product_error_barcode)
                pieces == null || pieces < 2L || pieces > 100_000L -> qty.error = getString(R.string.product_error_pack_qty)
                priceText.isNotEmpty() && (pp == null || pp < 0L) -> packPrice.error = getString(R.string.product_error_price)
                else -> {
                    codes.removeAll { it.code == c && it.kind == BarcodeKind.BARCODE }
                    codes.add(Code(null, c, BarcodeKind.BARCODE, pieces * 1000L, pp))
                    renderCodes()
                    d.dismiss()
                }
            }
        }
    }

    /**
     * A scanner fired on the form: its code went into whatever field had the cursor ("Milo
     * 1kg9556001234567"), and adding a product by scanning took "Add barcode" first (2026-10 review).
     * Now the code is taken out of the field again and added to the barcodes.
     */
    private val fieldScan = FieldScan { scanned(it) }
    private val scanInput = ScanInput(onScan = { scanned(it) }, onTyped = { _, _ -> })

    override fun screenKey(event: KeyEvent): Boolean {
        if (saving) return false
        val field = currentFocus as? EditText
        return if (field != null) fieldScan.onKey(event, field) else scanInput.onKey(event)
    }

    override fun serialScans(): (String) -> Unit = { scanned(it) }

    private fun scanned(raw: String) {
        val code = Gtin.canonical(raw)
        if (code.isEmpty() || saving) return
        if (!::codeList.isInitialized) {
            scannedEarly.add(code) // the form loads after this: kept for it
            return
        }
        if (codes.none { it.code == code && it.kind == BarcodeKind.BARCODE }) {
            codes.add(Code(null, code, BarcodeKind.BARCODE, 1000L, null))
            renderCodes()
        }
        toast(getString(R.string.product_barcode_added, code))
    }

    override fun onStop() {
        scanInput.clear()
        fieldScan.clear()
        super.onStop()
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (look.onResult(requestCode, resultCode, data)) return // a photo or a picture (D-066)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            val code = Gtin.canonical(data?.getStringExtra(CameraScanActivity.EXTRA_CODE) ?: return) // a camera code can end in a line break
            if (codes.none { it.code == code && it.kind == BarcodeKind.BARCODE }) codes.add(Code(null, code, BarcodeKind.BARCODE, 1000L, null))
            if (::codeList.isInitialized) renderCodes() else scannedEarly.add(code) // the form loads after this: kept for it
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        look.onPermission(requestCode, grantResults)
    }

    /**
     * The price field; empty is 0 for a product priced at the till (kuih, vegetables — D-050), which
     * could not be saved without a made-up price (2026-10 review).
     */
    private fun enteredPrice(): Long? =
        if (price.text.isBlank() && sellMode.selectedItemPosition == SellMode.OPEN_PRICE) 0L else MoneyFormat.parse(price.text.toString(), currency)

    // ------------------------------------------------------------------ save / delete

    /** The product the form's fields describe, or null while a money or quantity field does not parse. */
    private fun formProduct(): Product? {
        val pr = enteredPrice() ?: return null
        val c = if (cost.text.isBlank()) 0L else MoneyFormat.parse(cost.text.toString(), currency) ?: return null
        val low = if (lowStock.text.isBlank()) 0L else MoneyFormat.parseQty(lowStock.text.toString()) ?: return null
        val mode = sellMode.selectedItemPosition.coerceIn(0, 2)
        return Product(
            id = productId,
            name = name.text.toString().trim(),
            sku = sku.text.toString().trim().ifEmpty { null },
            categoryId = categories.getOrNull(category.selectedItemPosition - 1)?.id,
            unit = unit.text.toString().trim().ifEmpty { if (mode == SellMode.WEIGHT) "kg" else "pcs" },
            sellMode = mode,
            price = pr,
            cost = c,
            taxRateId = taxes.getOrNull(tax.selectedItemPosition - 1)?.id,
            trackStock = trackStock.isChecked,
            lowStock = low,
            active = active.isChecked,
        )
    }

    /** With [next], the form of the next new product opens once this one is saved. */
    private fun save(confirmedDuplicates: Boolean = false, next: Boolean = false) {
        if (saving) return
        if (look.busy) return toast(R.string.look_reading) // the picture just taken would be left out
        if (!graph.permissions.allowed(Perm.MANAGE_PRODUCTS)) {
            requireAccess(Perm.MANAGE_PRODUCTS) { save(confirmedDuplicates, next) }
            return
        }
        val n = name.text.toString().trim()
        val pr = enteredPrice()
        val c = if (cost.text.isBlank()) 0L else MoneyFormat.parse(cost.text.toString(), currency)
        val low = if (lowStock.text.isBlank()) 0L else MoneyFormat.parseQty(lowStock.text.toString())
        val pluText = plu.text.toString().trim().trimStart('0')
        val openingText = opening?.text?.toString()?.trim().orEmpty()
        val openingQty = if (openingText.isEmpty()) 0L else MoneyFormat.parseQty(openingText)
        when {
            n.isEmpty() -> return fieldError(name, R.string.product_error_name)
            pr == null || pr < 0L -> return fieldError(price, R.string.product_error_price)
            c == null || c < 0L -> return fieldError(cost, R.string.product_error_price)
            low == null || low < 0L -> return fieldError(lowStock, R.string.product_error_qty)
            plu.text.isNotBlank() && pluText.isEmpty() -> return fieldError(plu, R.string.product_error_plu)
            openingQty == null || openingQty < 0L -> return opening?.let { fieldError(it, R.string.product_error_qty) } ?: Unit
        }
        val p = formProduct() ?: return
        val wanted = ArrayList(codes.filter { it.kind == BarcodeKind.BARCODE })
        if (pluText.isNotEmpty()) {
            val old = codes.firstOrNull { it.kind == BarcodeKind.SCALE_PLU }
            wanted.add(Code(old?.takeIf { it.code == pluText }?.id, pluText, BarcodeKind.SCALE_PLU, 1000L, null))
        }
        saving = true
        launchUi {
            var saved = false
            try {
                if (!confirmedDuplicates) {
                    val dup = graph.db().read { r ->
                        wanted.filter { it.id == null }.firstNotNullOfOrNull { w ->
                            ProductDao.codeOwners(r, w.code, productId).firstOrNull()?.let { w.code to it.second }
                        }
                    }
                    if (dup != null) {
                        Dialogs.confirm(
                            this@ProductEditActivity, getString(R.string.product_duplicate_title),
                            getString(R.string.product_error_barcode_dup, dup.first, dup.second), getString(R.string.save),
                        ) { save(confirmedDuplicates = true, next = next) }
                        return@launchUi
                    }
                }
                val id = persist(p, wanted, openingQty ?: 0L)
                saved = true // stays "saving" while the screen closes: a queued tap does nothing
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PRODUCT_ID, id))
                toast(R.string.product_saved)
                if (next) startActivity(newIntent(this@ProductEditActivity, series = true, category = p.categoryId, tax = p.taxRateId))
                finish()
            } finally {
                if (!saved) saving = false
            }
        }
    }

    /** For the activity log (its details are English, like the rest of them). */
    private fun sellModeName(mode: Int) = when (mode) {
        SellMode.WEIGHT -> "weight"
        SellMode.OPEN_PRICE -> "open price"
        else -> "piece"
    }

    private suspend fun persist(p: Product, wanted: List<Code>, openingQty: Long): Long {
        val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS)
        val stockAllowed = graph.permissions.allowed(Perm.MANAGE_STOCK)
        val staff = actor.staffId
        val c = currency
        val seen = shown
        val seenCodes = originalCodes
        // Colour and picture (D-066): a new picture is stored as a new row the look points at.
        val picture = look.picture
        val wantLook = look.edited(null)
        val seenLook = look.shown
        return graph.db().write(reserveIds = wanted.size + 16L) { tx ->
            val now = System.currentTimeMillis()
            val before = if (p.id != 0L) ProductDao.get(tx.db, p.id) else null
            if (before == null) {
                val id = tx.nextId()
                val barcodes = wanted.map { Barcode(tx.nextId(), id, it.code, it.kind, it.packQty, it.packPrice) }
                ProductDao.create(tx, p.copy(id = id), barcodes, now)
                if (openingQty > 0L && stockAllowed) StockDao.insertMovement(tx, id, MovementKind.OPENING, openingQty, p.cost, null, null, staff, now)
                val imageId = picture?.let { ProductLookDao.addImage(tx, id, it, staff, now) }
                ProductLookDao.update(tx, id, ProductLook(), wantLook.copy(imageId = imageId ?: wantLook.imageId), now)
                id
            } else {
                // Only what the user changed on this screen is written, against the row as it is now:
                // a field another till changed while the screen was open keeps that change.
                val changed = ProductDao.update(tx, seen ?: before, p, before, now)
                val imageId = picture?.let { ProductLookDao.addImage(tx, p.id, it, staff, now) }
                ProductLookDao.update(tx, p.id, seenLook, wantLook.copy(imageId = imageId ?: wantLook.imageId), now)
                val label = if ("name" in changed) p.name else before.name
                if ("price" in changed) {
                    AuditDao.log(
                        tx, AuditAction.PRODUCT_PRICE_CHANGE, staff, now, Entity.PRODUCT, p.id, p.price,
                        "$label: ${MoneyFormat.format(before.price, c)} -> ${MoneyFormat.format(p.price, c)}", actor.approvedBy,
                    )
                }
                // What else changes what the till charges is on record too: "open price" lets the
                // cashier type any price, a tax rate changes the total (2026-10 review).
                if ("sell_mode" in changed) {
                    AuditDao.log(
                        tx, AuditAction.PRODUCT_PRICE_CHANGE, staff, now, Entity.PRODUCT, p.id, p.price,
                        "$label: sold by ${sellModeName(before.sellMode)} -> ${sellModeName(p.sellMode)}", actor.approvedBy,
                    )
                }
                if ("tax_rate_id" in changed) {
                    AuditDao.log(tx, AuditAction.PRODUCT_PRICE_CHANGE, staff, now, Entity.PRODUCT, p.id, p.price, "$label: tax rate changed", actor.approvedBy)
                }
                // The cost (the profit the reports show) and the barcodes (a dear item's barcode moved onto a
                // cheap product rings up the cheap price) are on record too (D-067).
                if ("cost" in changed) {
                    AuditDao.log(
                        tx, AuditAction.PRODUCT_PRICE_CHANGE, staff, now, Entity.PRODUCT, p.id, p.cost,
                        "$label: cost ${MoneyFormat.format(before.cost, c)} -> ${MoneyFormat.format(p.cost, c)}", actor.approvedBy,
                    )
                }
                val codesBefore = seenCodes.map { it.code }.toSet()
                val codesAfter = wanted.map { it.code }.toSet()
                if (codesBefore != codesAfter) {
                    val text = (codesAfter - codesBefore).map { "+$it" } + (codesBefore - codesAfter).map { "-$it" }
                    AuditDao.log(tx, AuditAction.PRODUCT_PRICE_CHANGE, staff, now, Entity.PRODUCT, p.id, p.price, "$label: barcodes ${text.joinToString(" ")}".take(300), actor.approvedBy)
                }
                val packsBefore = seenCodes.filter { it.packQty != 1000L || it.packPrice != null }.map { Triple(it.code, it.packQty, it.packPrice) }.toSet()
                val packsAfter = wanted.filter { it.packQty != 1000L || it.packPrice != null }.map { Triple(it.code, it.packQty, it.packPrice) }.toSet()
                if (packsBefore != packsAfter) {
                    val text = packsAfter.joinToString { (code, qty, price) ->
                        "$code ×${MoneyFormat.formatQty(qty)}" + (price?.let { " = ${MoneyFormat.format(it, c)}" } ?: "")
                    }
                    AuditDao.log(tx, AuditAction.PRODUCT_PRICE_CHANGE, staff, now, Entity.PRODUCT, p.id, p.price, "$label: packs: $text", actor.approvedBy)
                }
                val current = ProductDao.barcodes(tx.db, p.id)
                val keep = wanted.mapNotNull { it.id }.toSet()
                val shownIds = seenCodes.map { it.id }.toSet()
                // Removed = shown on this screen and taken off; a barcode added on another till meanwhile stays.
                for (b in current) if (b.id in shownIds && b.id !in keep) ProductDao.removeBarcode(tx, b.id, now)
                for (w in wanted) {
                    val id = w.id
                    if (id == null) {
                        ProductDao.addBarcode(tx, Barcode(tx.nextId(), p.id, w.code, w.kind, w.packQty, w.packPrice), now)
                    } else {
                        val old = seenCodes.firstOrNull { it.id == id }
                        val next = Barcode(id, p.id, w.code, w.kind, w.packQty, w.packPrice)
                        if (old != null && old != next) ProductDao.updateBarcode(tx, old, next, now)
                    }
                }
                p.id
            }
        }
    }

    private fun delete() {
        val p = original ?: return
        if (!graph.permissions.allowed(Perm.MANAGE_PRODUCTS)) {
            requireAccess(Perm.MANAGE_PRODUCTS) { delete() }
            return
        }
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.product_delete_confirm, p.name), getString(R.string.delete)) {
            launchUi {
                val actor = graph.permissions.actor(Perm.MANAGE_PRODUCTS)
                val staff = actor.staffId
                graph.db().write(reserveIds = 8L) { tx ->
                    val now = System.currentTimeMillis()
                    ProductDao.delete(tx, p.id, now)
                    AuditDao.log(tx, AuditAction.PRODUCT_DELETE, staff, now, Entity.PRODUCT, p.id, detail = p.name, approvedBy = actor.approvedBy)
                }
                toast(R.string.product_deleted)
                finish()
            }
        }
    }

    private fun fieldError(field: EditText, message: Int) {
        field.error = getString(message)
        field.requestFocus()
    }

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
        const val EXTRA_BARCODE = "barcode"

        /** Opened to enter products one after another: "Save and add another" is offered. */
        private const val EXTRA_SERIES = "series"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_TAX = "tax"
        private const val STATE_FORM = "product.form"
        private const val STATE_SHOWN = "product.shown"
        private const val REQ_SCAN = 7
        private const val UNIT_PCS = "pcs" // unit symbols are data (printed on receipts), not UI text
        private const val UNIT_KG = "kg"
        private const val MONEY_INPUT = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        private const val QTY_INPUT = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        /** [series]: products entered one after another; [category] and [tax] preselected for a new one. */
        fun newIntent(
            ctx: Context,
            productId: Long = 0L,
            barcode: String? = null,
            series: Boolean = false,
            category: Long? = null,
            tax: Long? = null,
        ): Intent = Intent(ctx, ProductEditActivity::class.java)
            .putExtra(EXTRA_PRODUCT_ID, productId)
            .putExtra(EXTRA_BARCODE, barcode)
            .putExtra(EXTRA_SERIES, series)
            .putExtra(EXTRA_CATEGORY, category ?: 0L)
            .putExtra(EXTRA_TAX, tax ?: 0L)
    }
}
