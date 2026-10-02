package com.lekaspos.ui.inventory

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.inventory.ReceiveDraft
import com.lekaspos.core.inventory.ReceiveLine
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.supplier.Supplier
import com.lekaspos.data.supplier.SupplierDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.FieldScan
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.products.ProductEditActivity
import com.lekaspos.ui.scan.CameraScanActivity
import com.lekaspos.ui.sell.AmountDialog
import com.lekaspos.ui.sell.Beeper
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Receiving a delivery: scan or pick products, set quantities and costs (per unit or the
 * invoice's line amount), choose the supplier, save. The draft is saved as it is typed, so a
 * phone call or a crash never loses a half-entered delivery.
 */
class ReceiveActivity : ScreenActivity() {

    private var draft = ReceiveDraft()
    private var loaded = false

    /** The delivery is being recorded: Save does nothing until it has finished or failed. */
    private var saving = false
    private var nextKey = 1L
    private var suppliers: List<Supplier> = emptyList()
    private var beeper: Beeper? = null
    private lateinit var supplierButton: Button
    private lateinit var ref: EditText
    private lateinit var total: TextView
    private lateinit var empty: TextView
    private lateinit var save: Button
    private lateinit var list: RecyclerView
    private val scanInput = ScanInput(onScan = { addByCode(it) }, onTyped = { _, _ -> })
    // The reference field is also scanned on purpose (a delivery order's own barcode): a scan there
    // that is no product stays in it (2026-10 review).
    private val fieldScan = FieldScan(takeOutAtOnce = false) { addByCode(it, fromRef = true) }

    private val currency get() = graph.settings.store.value.currency

    private val adapter = RowAdapter<ReceiveLine>(
        bind = { h, l ->
            h.set(
                title = l.name,
                subtitle = getString(R.string.inv_line_detail, InventoryUi.qty(l.qty, l.unit), MoneyFormat.format(l.unitCost, currency)),
                value = MoneyFormat.format(l.total, currency, withSymbol = false),
            )
        },
        onClick = { editLine(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.inv_receive), R.layout.activity_receive) ?: return
        addAction(R.drawable.ic_camera, R.string.sell_camera) {
            @Suppress("DEPRECATION")
            startActivityForResult(CameraScanActivity.pickIntent(this), REQ_CAMERA)
        }
        addAction(R.drawable.ic_close, R.string.inv_discard) { discard() }
        supplierButton = v.findViewById(R.id.receive_supplier)
        ref = v.findViewById(R.id.receive_ref)
        total = v.findViewById(R.id.receive_total)
        empty = v.findViewById(R.id.list_empty)
        save = v.findViewById(R.id.receive_save)
        list = v.findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        supplierButton.setOnClickListener { chooseSupplier() }
        v.findViewById<Button>(R.id.receive_add).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(ProductPickActivity.intent(this, getString(R.string.inv_add_item)), REQ_PICK)
        }
        save.setOnClickListener { confirmSave() }
        beeper = Beeper.create()
        launchUi {
            draft = graph.inventory.loadDraft()
            suppliers = graph.db().read { SupplierDao.list(it) }
            nextKey = (draft.lines.maxOfOrNull { it.key } ?: 0L) + 1L
            ref.setText(draft.refNo)
            ref.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = change(draft.copy(refNo = s?.toString()?.trim() ?: ""))
            })
            loaded = true
            render()
        }
    }

    override fun onStop() {
        scanInput.clear()
        super.onStop()
    }

    override fun onDestroy() {
        beeper?.release()
        super.onDestroy()
    }

    // No scans while the delivery is being recorded: they belong to no delivery (2026-10 review).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (loaded && !saving && if (ref.hasFocus()) fieldScan.onKey(event, ref) else scanInput.onKey(event)) || super.dispatchKeyEvent(event)

    private fun render() {
        supplierButton.text = draft.supplierId?.let { id -> suppliers.firstOrNull { it.id == id }?.name } ?: getString(R.string.inv_no_supplier)
        adapter.submit(draft.lines)
        empty.visible(draft.isEmpty)
        total.text = getString(R.string.inv_receive_total, MoneyFormat.format(draft.total, currency), draft.lines.size)
        save.isEnabled = !draft.isEmpty && !saving
    }

    /**
     * Applies an edit, shows it and saves the draft (in order, on the database writer). Nothing
     * changes while the delivery is being recorded: a save queued behind it would store the
     * received delivery again (InventoryService also drops such a save).
     */
    private fun change(next: ReceiveDraft) {
        if (saving) return
        draft = next
        render()
        graph.appScope.launch(Dispatchers.Main) { graph.inventory.saveDraft(next) }
    }

    private fun addByCode(code: String, fromRef: Boolean = false) {
        if (saving) return
        launchUi {
            val p = InventoryUi.resolve(graph, code)
            if (saving) return@launchUi
            if (p == null) {
                if (fromRef) return@launchUi // the delivery order's or invoice's number, as typed in
                beeper?.error()
                Dialogs.confirm(this@ReceiveActivity, getString(R.string.sell_not_found_title), getString(R.string.sell_not_found_message, code), getString(R.string.sell_add_product)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(ProductEditActivity.newIntent(this@ReceiveActivity, barcode = code), REQ_NEW)
                }
                return@launchUi
            }
            if (fromRef) fieldScan.takeOut(ref, code)
            beeper?.ok()
            add(p)
        }
    }

    /** Scanned cartons add their pack size, pieces add one; weighed goods ask for the weight. */
    private fun add(p: StockProduct) {
        if (p.weighed || p.scannedQty <= 0L) {
            InventoryUi.askQty(this, p.name, p, if (p.weighed) p.scannedQty else 0L, allowZero = false) { qty -> addQty(p, qty) }
        } else {
            addQty(p, p.scannedQty)
        }
    }

    private fun addQty(p: StockProduct, qty: Long) {
        val (next, key) = draft.add(nextKey, p.id, p.name, p.unit, qty, p.cost)
        if (key == nextKey) nextKey++
        change(next)
        val pos = draft.lines.indexOfFirst { it.key == key }
        if (pos >= 0) list.scrollToPosition(pos)
    }

    private fun editLine(l: ReceiveLine) {
        val options = listOf(getString(R.string.line_qty), getString(R.string.inv_unit_cost), getString(R.string.inv_line_total), getString(R.string.remove))
        Dialogs.choose(this, l.name, options) { which ->
            when (which) {
                0 -> launchUi {
                    val p = InventoryUi.product(graph, l.productId) ?: return@launchUi
                    InventoryUi.askQty(this@ReceiveActivity, l.name, p, l.qty, allowZero = false) { change(draft.setQty(l.key, it)) }
                }
                1 -> AmountDialog(this, getString(R.string.inv_unit_cost), AmountDialog.Kind.MONEY, currency, initial = l.unitCost, allowZero = true) {
                    change(draft.setUnitCost(l.key, it))
                }.show()
                2 -> AmountDialog(this, getString(R.string.inv_line_total), AmountDialog.Kind.MONEY, currency, initial = l.total, allowZero = true) {
                    change(draft.setTotal(l.key, it))
                }.show()
                3 -> change(draft.remove(l.key))
            }
        }
    }

    private fun chooseSupplier() {
        val names = listOf<CharSequence>(getString(R.string.inv_no_supplier)) + suppliers.map { it.name } + getString(R.string.inv_new_supplier)
        Dialogs.choose(this, getString(R.string.inv_supplier), names) { which ->
            when (which) {
                0 -> change(draft.copy(supplierId = null))
                names.size - 1 -> editSupplier(this, null) { s ->
                    suppliers = suppliers + s
                    change(draft.copy(supplierId = s.id))
                }
                else -> change(draft.copy(supplierId = suppliers[which - 1].id))
            }
        }
    }

    private fun confirmSave() {
        if (draft.isEmpty || saving) return
        val d = draft
        Dialogs.confirm(
            this, getString(R.string.inv_receive_save),
            getString(R.string.inv_receive_confirm, d.lines.size, MoneyFormat.format(d.total, currency)), getString(R.string.save),
        ) {
            // A double tap can stack two confirm dialogs: only the first confirmation saves.
            if (saving) return@confirm
            saving = true
            save.isEnabled = false
            launchUi {
                var received = false
                try {
                    // In the app scope: leaving the screen cannot cut a delivery in half.
                    graph.appScope.async(Dispatchers.Main) { graph.inventory.receive(d) }.await()
                    received = true // stays "saving" while the screen closes
                    toast(R.string.inv_received)
                    finish()
                } finally {
                    if (!received) saving = false
                    save.isEnabled = !saving && !draft.isEmpty
                }
            }
        }
    }

    private fun discard() {
        if (draft.isEmpty && draft.supplierId == null && draft.refNo.isEmpty()) return finish()
        Dialogs.confirm(this, getString(R.string.inv_discard), getString(R.string.inv_discard_confirm), getString(R.string.inv_discard)) {
            ref.setText("")
            change(ReceiveDraft())
            finish()
        }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || saving) return
        when (requestCode) {
            REQ_CAMERA -> data.getStringExtra(CameraScanActivity.EXTRA_CODE)?.let { addByCode(it) }
            REQ_PICK, REQ_NEW -> {
                val id = data.getLongExtra(ProductPickActivity.EXTRA_PRODUCT_ID, 0L)
                val scanned = data.getLongExtra(ProductPickActivity.EXTRA_SCANNED_QTY, 0L)
                if (id != 0L) {
                    launchUi {
                        val p = InventoryUi.product(graph, id) ?: return@launchUi
                        add(p.copy(scannedQty = scanned))
                    }
                }
            }
        }
    }

    companion object {
        private const val REQ_CAMERA = 61
        private const val REQ_PICK = 62
        private const val REQ_NEW = 63
    }
}
