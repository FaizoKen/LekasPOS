package com.lekaspos.ui.staff

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.Perm
import com.lekaspos.data.staff.Staff
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.domain.Approval
import com.lekaspos.domain.StaffSession
import com.lekaspos.ui.common.DialogKeys
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.PinPad
import com.lekaspos.ui.common.keys
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Display name of a permission bit. */
fun permLabel(perm: Long): Int = when (perm) {
    Perm.DISCOUNT -> R.string.perm_discount
    Perm.PRICE_OVERRIDE -> R.string.perm_price
    Perm.CANCEL_BILL -> R.string.perm_cancel_bill
    Perm.VOID -> R.string.perm_void
    Perm.REFUND -> R.string.perm_refund
    Perm.REPRINT -> R.string.perm_reprint
    Perm.OPEN_DRAWER -> R.string.perm_drawer
    Perm.CASH_MOVE -> R.string.perm_cash_move
    Perm.SHIFT_REPORT -> R.string.perm_shift_report
    Perm.CUSTOMERS -> R.string.perm_customers
    Perm.CREDIT_SALE -> R.string.perm_credit_sale
    Perm.CREDIT_LIMIT -> R.string.perm_credit_limit
    Perm.MANAGE_PRODUCTS -> R.string.perm_products
    Perm.MANAGE_STOCK -> R.string.perm_stock
    Perm.VIEW_AUDIT -> R.string.perm_audit
    Perm.REPORTS -> R.string.perm_reports
    Perm.SETTINGS -> R.string.perm_settings
    Perm.MANAGE_STAFF -> R.string.perm_staff
    else -> R.string.perm_other
}

/** "m:ss" for a wait. */
fun waitText(ms: Long): String {
    val s = (ms + 999L) / 1000L
    return String.format(Locale.ROOT, "%d:%02d", s / 60L, s % 60L)
}

/** The line shown under a PIN pad after a failed check. */
fun checkMessage(ctx: Context, c: StaffSession.Check): String = when (c) {
    is StaffSession.Check.Ok -> ""
    is StaffSession.Check.WrongPin ->
        if (c.waitMs > 0L) ctx.getString(R.string.pin_wrong_wait, waitText(c.waitMs)) else ctx.getString(R.string.pin_wrong, c.triesLeft)
    is StaffSession.Check.Wait -> ctx.getString(R.string.pin_wait, waitText(c.ms))
    StaffSession.Check.NotAllowed -> ctx.getString(R.string.pin_not_allowed)
}

/**
 * Runs [block] at once when the signed-in staff member may do [perm]; otherwise a manager
 * approves it with their PIN first and [block] gets the approval (for this one action).
 */
fun Activity.withApproval(graph: AppGraph, scope: CoroutineScope, perm: Long, block: (Approval?) -> Unit) {
    if (graph.permissions.allowed(perm)) {
        block(null)
        return
    }
    ApprovalDialog.show(this, graph, scope, perm, onCancel = null) { block(it) }
}

/** A manager approves one permission with their PIN (D-037). */
object ApprovalDialog {

    /** [ownersOnly]: only an owner may approve (changes to owners and to what roles may do). */
    fun show(
        a: Activity,
        graph: AppGraph,
        scope: CoroutineScope,
        perm: Long,
        onCancel: (() -> Unit)?,
        ownersOnly: Boolean = false,
        onApproved: (Approval) -> Unit,
    ) {
        val why = if (ownersOnly) a.getString(R.string.approval_owner_needed) else a.getString(R.string.approval_needed, a.getString(permLabel(perm)))
        ask(
            a, graph, scope, why,
            loadApprovers = { db -> StaffDao.approvers(db, perm).filter { !ownersOnly || it.isOwner } },
            approve = { id, pin -> graph.permissions.approve(id, pin, perm) },
            onCancel = onCancel, onApproved = onApproved,
        )
    }

    /**
     * A manager helps at the till (D-063): their PIN, and the buttons the person signed in does not see
     * show for this bill ([com.lekaspos.domain.PermissionGate.startHelp]). [needed]: a permission the
     * helper must hold (0 = any the person signed in lacks); [why] says what for.
     */
    fun help(a: Activity, graph: AppGraph, scope: CoroutineScope, needed: Long, why: String, onHelping: () -> Unit) {
        val own = graph.staff.perms
        ask(
            a, graph, scope, why,
            loadApprovers = { db -> StaffDao.list(db).filter { it.canSignIn && Perm.has(it.perms, needed) && Perm.addsTo(it.perms, own) } },
            approve = { id, pin -> graph.permissions.approveHelp(id, pin, needed) },
            onCancel = null,
        ) { approval ->
            graph.permissions.startHelp(approval)
            graph.appScope.launch { graph.staff.recordApproval(approval) }
            onHelping()
        }
    }

    private fun ask(
        a: Activity,
        graph: AppGraph,
        scope: CoroutineScope,
        why: String,
        loadApprovers: (SQLiteDatabase) -> List<Staff>,
        approve: suspend (staffId: Long, pin: String) -> Pair<StaffSession.Check, Approval?>,
        onCancel: (() -> Unit)?,
        onApproved: (Approval) -> Unit,
    ) {
        scope.launch {
            val approvers = try {
                graph.db().read(loadApprovers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // the screen closed: no "not allowed" on it (it crashed the app)
            } catch (e: Exception) {
                Log.e("Loading approvers failed", e)
                emptyList()
            }
            if (approvers.isEmpty()) {
                Dialogs.message(a, null, a.getString(R.string.not_allowed)).setOnDismissListener { onCancel?.invoke() }
                return@launch
            }
            val pad = (20 * a.resources.displayMetrics.density).toInt()
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            col.addView(TextView(a, null, 0, R.style.Text_Lekas_Body).apply { text = why })
            val picker = Spinner(a)
            picker.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, approvers.map { it.name })
            picker.minimumHeight = (48 * a.resources.displayMetrics.density).toInt()
            picker.visibility = if (approvers.size > 1) View.VISIBLE else View.GONE
            col.addView(picker)
            if (approvers.size == 1) {
                col.addView(TextView(a, null, 0, R.style.Text_Lekas_Section).apply { text = approvers[0].name })
            }
            var dialog: AlertDialog? = null
            var approved = false
            lateinit var pinPad: PinPad
            pinPad = PinPad(a) { pin ->
                val who = approvers[picker.selectedItemPosition.coerceAtLeast(0)]
                pinPad.setEnabled(false)
                scope.launch {
                    val (check, approval) = try {
                        approve(who.id, pin)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Said as it is (a full phone, say), not "cannot approve this" (2026-10 review).
                        Log.e("Approval failed", e)
                        pinPad.setEnabled(true)
                        pinPad.setMessage(com.lekaspos.ui.common.ScreenActivity.errorText(a, e))
                        return@launch
                    }
                    pinPad.setEnabled(true)
                    if (approval != null) {
                        approved = true
                        dialog?.dismiss()
                        onApproved(approval)
                    } else {
                        pinPad.setMessage(checkMessage(a, check))
                    }
                }
            }
            col.addView(pinPad.view)
            val d = AlertDialog.Builder(a)
                .setTitle(R.string.approval_title)
                .setView(Dialogs.scrolling(col))
                .setNegativeButton(R.string.cancel, null)
                .create()
            d.keys { e -> pinPad.onKey(e) || DialogKeys.pressesFocused(e.keyCode) }
            d.setOnDismissListener { if (!approved) onCancel?.invoke() }
            dialog = d
            d.show()
            d.trackedBy(a)
        }
    }
}

/** Asks for a PIN once (e.g. the current PIN). [onPin] may return a message to show and keep asking. */
fun askPin(a: Activity, title: CharSequence, message: CharSequence?, onPin: (String, (String?) -> Unit) -> Unit): AlertDialog {
    val col = pinColumn(a, message)
    lateinit var d: AlertDialog
    lateinit var pad: PinPad
    pad = PinPad(a) { pin ->
        pad.setEnabled(false)
        onPin(pin) { error ->
            pad.setEnabled(true)
            if (error == null) d.dismiss() else pad.setMessage(error)
        }
    }
    col.addView(pad.view)
    d = AlertDialog.Builder(a).setTitle(title).setView(Dialogs.scrolling(col)).setNegativeButton(R.string.cancel, null).create()
    d.keys { e -> pad.onKey(e) || DialogKeys.pressesFocused(e.keyCode) }
    d.show()
    return d.trackedBy(a)
}

/** Asks for a new PIN (4–6 digits) twice; [onPin] gets it when both match. */
fun askNewPin(a: Activity, title: CharSequence, onPin: (String) -> Unit): AlertDialog {
    val col = pinColumn(a, a.getString(R.string.pin_new_hint))
    var first: String? = null
    lateinit var d: AlertDialog
    lateinit var pad: PinPad
    pad = PinPad(a) { pin ->
        val f = first
        when {
            f == null -> {
                first = pin
                pad.setMessage(a.getString(R.string.pin_repeat), error = false)
            }
            f == pin -> {
                d.dismiss()
                onPin(pin)
            }
            else -> {
                first = null
                pad.setMessage(a.getString(R.string.pin_mismatch))
            }
        }
    }
    col.addView(pad.view)
    d = AlertDialog.Builder(a).setTitle(title).setView(Dialogs.scrolling(col)).setNegativeButton(R.string.cancel, null).create()
    d.keys { e -> pad.onKey(e) || DialogKeys.pressesFocused(e.keyCode) }
    d.show()
    return d.trackedBy(a)
}

/** Shows a new owner recovery code once, with what it is for. */
fun showRecoveryCode(a: Activity, code: String): AlertDialog = AlertDialog.Builder(a)
    .setTitle(R.string.recovery_title)
    .setMessage(a.getString(R.string.recovery_message, code))
    .setPositiveButton(R.string.recovery_done, null)
    .setCancelable(false)
    .show()
    .trackedBy(a)

private fun pinColumn(a: Activity, message: CharSequence?): LinearLayout {
    val pad = (20 * a.resources.displayMetrics.density).toInt()
    val col = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
    }
    if (message != null) col.addView(TextView(a, null, 0, R.style.Text_Lekas_Body).apply { text = message })
    return col
}
