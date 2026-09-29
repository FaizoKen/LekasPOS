package com.lekaspos.ui.inventory

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.data.supplier.Supplier
import com.lekaspos.data.supplier.SupplierDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope

/** The supplier list: add, edit, delete; each supplier's deliveries. */
class SuppliersActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private val adapter = RowAdapter<Supplier>(
        bind = { h, s -> h.set(s.name, listOfNotNull(s.contact, s.phone).joinToString(" · ").ifEmpty { null }) },
        onClick = { s -> editSupplier(this, s) { reload() } },
        onLongClick = { delete(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.inv_suppliers), R.layout.list_plain) ?: return
        addAction(R.drawable.ic_add, R.string.inv_new_supplier) { editSupplier(this, null) { reload() } }
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.inv_suppliers_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        launchUi {
            val items = graph.db().read { SupplierDao.list(it) }
            adapter.submit(items)
            empty.visible(items.isEmpty())
        }
    }

    private fun delete(s: Supplier) {
        if (!graph.permissions.allowed(Perm.MANAGE_STOCK)) {
            Dialogs.message(this, null, getString(R.string.not_allowed))
            return
        }
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.inv_supplier_delete_confirm, s.name), getString(R.string.delete)) {
            launchUi {
                graph.db().write(reserveIds = 0L) { tx -> SupplierDao.delete(tx, s.id, System.currentTimeMillis()) }
                reload()
            }
        }
    }
}

/** Add ([existing] = null) or edit a supplier; [onSaved] gets the stored supplier. */
fun editSupplier(a: ScreenActivity, existing: Supplier?, onSaved: (Supplier) -> Unit) {
    if (!a.graph.permissions.allowed(Perm.MANAGE_STOCK)) {
        Dialogs.message(a, null, a.getString(R.string.not_allowed))
        return
    }
    val pad = (20 * a.resources.displayMetrics.density).toInt()
    val col = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
    }
    fun field(hint: Int, value: String?, type: Int) = EditText(a).apply {
        this.hint = a.getString(hint)
        setText(value ?: "")
        inputType = type
        setSingleLine(type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0)
        col.addView(this)
    }
    val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    val name = field(R.string.inv_supplier_name, existing?.name, words)
    val contact = field(R.string.inv_supplier_contact, existing?.contact, words)
    val phone = field(R.string.store_phone, existing?.phone, InputType.TYPE_CLASS_PHONE)
    val email = field(R.string.store_email, existing?.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    val address = field(R.string.store_address, existing?.address, words or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    val note = field(R.string.inv_note_hint, existing?.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    val b = AlertDialog.Builder(a)
        .setTitle(if (existing == null) R.string.inv_new_supplier else R.string.inv_edit_supplier)
        .setView(ScrollView(a).apply { addView(col) })
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
    if (existing != null) {
        b.setNeutralButton(R.string.inv_purchases) { _, _ -> a.startActivity(PurchasesActivity.intent(a, existing.id)) }
    }
    val d = b.show().trackedBy(a)
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            name.error = a.getString(R.string.product_error_name)
            return@setOnClickListener
        }
        fun t(e: EditText) = e.text.toString().trim().ifEmpty { null }
        val s = Supplier(existing?.id ?: 0L, n, t(contact), t(phone), t(email), t(address), t(note))
        d.dismiss()
        a.launchUi {
            val saved = a.graph.db().write(reserveIds = 2L) { tx ->
                val now = System.currentTimeMillis()
                if (existing == null) {
                    s.copy(id = SupplierDao.insert(tx, s, now))
                } else {
                    SupplierDao.update(tx, existing, s, now)
                    s
                }
            }
            onSaved(saved)
        }
    }
}
