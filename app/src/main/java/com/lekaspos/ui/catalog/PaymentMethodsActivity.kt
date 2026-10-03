package com.lekaspos.ui.catalog

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.AuditAction
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.data.audit.AuditDao
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.catalog.PaymentMethodRow
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import kotlinx.coroutines.CoroutineScope

/**
 * Payment methods (2026-10): the shop adds its own — DuitNow QR, Touch 'n Go, bank transfer — so each
 * is counted on its own in the shift and sales reports, renames them, and hides those it does not
 * take. Only cash is rounded to the 5-sen step; the others pay the exact amount. Cash and customer
 * credit keep their kind, and cash always stays at the till.
 */
class PaymentMethodsActivity : ScreenActivity() {

    private val adapter = RowAdapter<PaymentMethodRow>(
        bind = { h, m -> h.set(m.name, subtitle(m), tag = if (m.active) null else getString(R.string.pm_hidden)) },
        onClick = { edit(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.pm_title), R.layout.list_plain) ?: return
        addAction(R.drawable.ic_add, R.string.pm_add) { edit(null) }
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        launchUi {
            adapter.submit(graph.db().read { PaymentMethodDao.all(it) })
        }
    }

    private fun subtitle(m: PaymentMethodRow): String {
        val kind = getString(kindLabel(m.kind))
        return if (m.opensDrawer) "$kind · ${getString(R.string.pm_opens_drawer)}" else kind
    }

    private fun kindLabel(kind: Int): Int = when (kind) {
        PaymentKind.CASH -> R.string.pm_kind_cash
        PaymentKind.CARD -> R.string.pm_kind_card
        PaymentKind.EWALLET -> R.string.pm_kind_ewallet
        PaymentKind.CREDIT -> R.string.pm_kind_credit
        else -> R.string.pm_kind_other
    }

    private fun edit(m: PaymentMethodRow?) = requireAccess(Perm.SETTINGS) { editNow(m) }

    private fun editNow(m: PaymentMethodRow?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val minTouch = (48 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        col.addView(TextView(this, null, 0, R.style.Text_Lekas_Caption).apply { setText(R.string.pm_help) })
        val name = EditText(this).apply {
            hint = getString(R.string.pm_name)
            setText(m?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
            minHeight = minTouch
        }
        col.addView(name)
        // Cash and customer credit keep their kind: the till rounds cash and books credit by it.
        val fixedKind = m != null && (m.kind == PaymentKind.CASH || m.kind == PaymentKind.CREDIT)
        val kinds = listOf(PaymentKind.EWALLET, PaymentKind.CARD, PaymentKind.OTHER)
        val kind = Spinner(this).apply {
            val labels = kinds.map { getString(kindLabel(it)) }
            adapter = ArrayAdapter(this@PaymentMethodsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(kinds.indexOf(m?.kind ?: PaymentKind.EWALLET).coerceAtLeast(0))
            minimumHeight = minTouch
        }
        if (fixedKind) {
            val fixed = getString(kindLabel(m?.kind ?: PaymentKind.CASH))
            col.addView(TextView(this, null, 0, R.style.Text_Lekas_Body).apply { text = fixed })
        } else {
            col.addView(TextView(this, null, 0, R.style.Text_Lekas_Caption).apply { setText(R.string.pm_kind) })
            col.addView(kind)
        }
        val isCash = m?.kind == PaymentKind.CASH
        val isCredit = m?.kind == PaymentKind.CREDIT
        // Cash always opens the drawer: this setting is the shop's, on every till (a till without a
        // drawer has its own switch in Printer & drawer) — unticked, no till's drawer opened for cash.
        val drawer = CheckBox(this).apply {
            setText(R.string.pm_opens_drawer)
            isChecked = isCash || (m?.opensDrawer ?: false)
            minHeight = minTouch
            isEnabled = !isCash
        }
        col.addView(drawer)
        // Cash is always taken; customer credit has its own switch (Customers), and hidden here a credit
        // sale's refund was paid out in cash while the debt stayed (2026-10 review).
        val shown = CheckBox(this).apply {
            setText(R.string.pm_active)
            isChecked = m?.active ?: true
            minHeight = minTouch
            isEnabled = !isCash && !isCredit
        }
        col.addView(shown)
        val d = AlertDialog.Builder(this)
            .setTitle(if (m == null) R.string.pm_add else R.string.pm_edit)
            .setView(col)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .trackedBy(this)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                name.error = getString(R.string.pm_error_name)
                return@setOnClickListener
            }
            val k = if (fixedKind) m?.kind ?: PaymentKind.CASH else kinds[kind.selectedItemPosition.coerceIn(0, kinds.size - 1)]
            d.dismiss()
            val opens = isCash || drawer.isChecked
            val active = isCash || isCredit || shown.isChecked
            if (m != null && n != m.name) confirmRename(m, n, k, opens, active) else save(m, n, k, opens, active)
        }
    }

    /**
     * Sales paid with this method show its name as it is now (receipts, reports, closed shifts): a
     * rename changes their name too (2026-10 review), so it is said first.
     */
    private fun confirmRename(m: PaymentMethodRow, name: String, kind: Int, opensDrawer: Boolean, active: Boolean) {
        launchUi {
            val used = graph.db().read { PaymentMethodDao.used(it, m.id) }
            if (!used) {
                save(m, name, kind, opensDrawer, active)
                return@launchUi
            }
            val text = getString(R.string.pm_rename_used, m.name, name)
            Dialogs.confirm(this@PaymentMethodsActivity, getString(R.string.pm_edit), text, getString(R.string.pm_rename_yes)) {
                save(m, name, kind, opensDrawer, active)
            }
        }
    }

    private fun save(before: PaymentMethodRow?, name: String, kind: Int, opensDrawer: Boolean, active: Boolean) {
        launchUi {
            // Checked here too, not only by the screen; a manager's approval is named in the activity log.
            val actor = graph.permissions.actor(Perm.SETTINGS)
            graph.db().write(reserveIds = 4L) { tx ->
                val now = System.currentTimeMillis()
                val id = if (before == null) {
                    PaymentMethodDao.insert(tx, name, kind, opensDrawer, now, active)
                } else {
                    val after = before.copy(name = name, kind = kind, opensDrawer = opensDrawer, active = active)
                    PaymentMethodDao.update(tx, before, after, now)
                    before.id
                }
                val state = (if (active) "shown" else "hidden") + if (opensDrawer) ", opens the drawer" else ""
                val was = before?.let { " (was ${it.name})" }.orEmpty()
                AuditDao.log(
                    tx, AuditAction.SETTINGS_CHANGE, actor.staffId, now, Entity.PAYMENT_METHOD, id,
                    detail = "payment method $name, $state$was", approvedBy = actor.approvedBy,
                )
            }
            reload()
        }
    }
}
