package com.lekaspos.ui.catalog

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.receipt.ReceiptLayout
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.TaxRate
import com.lekaspos.data.catalog.TaxRateDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope

/**
 * Tax rates (e.g. SST 6%, 8%). Products point at a rate; whether prices include tax is a store
 * setting. Stores that do not charge tax at the till simply leave this list empty (D-024).
 */
class TaxRatesActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private val adapter = RowAdapter<TaxRate>(
        bind = { h, t -> h.set(t.name, t.code, ReceiptLayout.percent(t.rateBp)) },
        onClick = { edit(it) },
        onLongClick = { delete(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.tax_title), R.layout.list_plain) ?: return
        addAction(R.drawable.ic_add, R.string.tax_add) { edit(null) }
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.tax_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        launchUi {
            val items = graph.db().read { TaxRateDao.list(it) }
            adapter.submit(items)
            empty.visible(items.isEmpty())
        }
    }

    private fun edit(t: TaxRate?) = requireAccess(Perm.SETTINGS) { editNow(t) }

    private fun editNow(t: TaxRate?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: Int, value: String?, type: Int) = EditText(this).apply {
            this.hint = getString(hint)
            setText(value ?: "")
            inputType = type
            setSingleLine(true)
            col.addView(this)
        }
        val name = field(R.string.tax_name, t?.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val code = field(R.string.tax_code, t?.code, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val rate = field(
            R.string.tax_rate, t?.let { MoneyFormat.plain(it.rateBp.toLong(), 2).trimEnd('0').trimEnd('.') },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
        val d = AlertDialog.Builder(this)
            .setTitle(if (t == null) R.string.tax_add else R.string.tax_edit)
            .setView(col)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .trackedBy(this)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val bp = MoneyFormat.parsePlain(rate.text.toString(), 2)
            when {
                n.isEmpty() -> name.error = getString(R.string.tax_error_name)
                bp == null || bp < 0L || bp > 10_000L -> rate.error = getString(R.string.tax_error_rate)
                else -> {
                    val c = code.text.toString().trim().ifEmpty { null }
                    d.dismiss()
                    val staffId = graph.staff.staffId
                    launchUi {
                        graph.db().write(reserveIds = 4L) { tx ->
                            val now = System.currentTimeMillis()
                            val id = if (t == null) {
                                TaxRateDao.insert(tx, n, c, bp.toInt(), now)
                            } else {
                                TaxRateDao.update(tx, t, TaxRate(t.id, n, c, bp.toInt()), now)
                                t.id
                            }
                            // A tax rate changes every price that uses it: the owner sees who changed it.
                            val was = t?.let { " (was ${it.name} ${percent(it.rateBp)})" } ?: ""
                            AuditDao.log(tx, AuditAction.SETTINGS_CHANGE, staffId, now, Entity.TAX_RATE, id, detail = "tax rate $n ${percent(bp.toInt())}$was")
                        }
                        reload()
                    }
                }
            }
        }
    }

    private fun delete(t: TaxRate) = requireAccess(Perm.SETTINGS) { deleteNow(t) }

    private fun deleteNow(t: TaxRate) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.tax_delete_confirm, t.name), getString(R.string.delete)) {
            val staffId = graph.staff.staffId
            launchUi {
                graph.db().write(reserveIds = 1L) { tx ->
                    val now = System.currentTimeMillis()
                    TaxRateDao.delete(tx, t.id, now)
                    AuditDao.log(tx, AuditAction.SETTINGS_CHANGE, staffId, now, Entity.TAX_RATE, t.id, detail = "tax rate ${t.name} deleted")
                }
                reload()
            }
        }
    }

    private fun percent(bp: Int): String = MoneyFormat.plain(bp.toLong(), 2).trimEnd('0').trimEnd('.') + "%"
}
