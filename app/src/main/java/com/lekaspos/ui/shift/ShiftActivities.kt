package com.lekaspos.ui.shift

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.app.AppGraph
import com.lekaspos.core.model.CashMoveKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.shift.ShiftReportLayout
import com.lekaspos.core.shift.ShiftText
import com.lekaspos.core.time.DateText
import com.lekaspos.data.shift.CashMove
import com.lekaspos.data.shift.Shift
import com.lekaspos.data.shift.ShiftDao
import com.lekaspos.data.staff.StaffDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.sell.AmountDialog
import com.lekaspos.ui.sell.visible
import com.lekaspos.util.Log
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Asks for the opening float and opens a shift on this till; [opened] runs after it is open. */
fun openShift(a: Activity, graph: AppGraph, scope: CoroutineScope, opened: () -> Unit) {
    val currency = graph.settings.store.value.currency
    AmountDialog(a, a.getString(R.string.shift_open), AmountDialog.Kind.MONEY, currency, message = a.getString(R.string.shift_float_hint), allowZero = true) { float ->
        scope.launch {
            try {
                graph.shifts.open(float)
                opened()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // the screen closed: not an error to report
            } catch (e: Exception) {
                Log.e("Opening the shift failed", e)
                Dialogs.message(a, a.getString(R.string.error_title), ScreenActivity.errorText(a, e))
            }
        }
    }.show()
}

/** The till's shift: open it, move cash in and out, close it with the counted cash (D-038). */
class ShiftActivity : ScreenActivity() {

    private val tz = TimeZone.getDefault()
    private var names: Map<Long, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen(getString(R.string.shift_title))
    }

    override fun onStarted(scope: CoroutineScope) {
        scope.launch {
            try {
                graph.shifts.load()
                names = graph.db().read { StaffDao.names(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Loading the shift failed", e)
            }
            graph.shifts.current.collect { render(it) }
        }
    }

    private fun money(v: Long) = MoneyFormat.format(v, graph.settings.store.value.currency)

    private fun render(s: Shift?) {
        val form = Form(this)
        if (s == null) {
            form.info(getString(R.string.shift_none))
            form.button(getString(R.string.shift_open), primary = true) { openShift(this, graph, scope) {} }
        } else {
            form.section(getString(R.string.shift_current))
            form.row(getString(R.string.shift_opened_by), names[s.openedBy] ?: "-")
            form.row(getString(R.string.shift_opened_at), DateText.dateTime(s.openedAt, tz))
            form.row(getString(R.string.shift_float), money(s.openingFloat))
            form.button(getString(R.string.shift_cash_in)) { moveCash(CashMoveKind.CASH_IN) }
            form.button(getString(R.string.shift_cash_out)) { moveCash(CashMoveKind.CASH_OUT) }
            form.button(getString(R.string.shift_drop)) { moveCash(CashMoveKind.DROP) }
            form.button(getString(R.string.shift_report)) { requireAccess(Perm.SHIFT_REPORT) { startActivity(ShiftReportActivity.intent(this, s.id)) } }
            form.button(getString(R.string.shift_close), primary = true) { close() }
        }
        form.button(getString(R.string.shift_history)) { startActivity(Intent(this, ShiftsActivity::class.java)) }
        content.removeAllViews()
        content.addView(form.view)
    }

    private fun moveCash(kind: Int) {
        val title = getString(
            when (kind) {
                CashMoveKind.CASH_IN -> R.string.shift_cash_in
                CashMoveKind.CASH_OUT -> R.string.shift_cash_out
                else -> R.string.shift_drop
            },
        )
        withApproval(Perm.CASH_MOVE) { approval ->
            AmountDialog(this, title, AmountDialog.Kind.MONEY, graph.settings.store.value.currency) { amount ->
                val needReason = kind == CashMoveKind.CASH_OUT
                Dialogs.input(this, title, getString(if (needReason) R.string.shift_reason_required else R.string.shift_reason_optional)) { reason ->
                    if (needReason && reason.isEmpty()) return@input false
                    launchUi {
                        graph.shifts.moveCash(kind, amount, reason.ifEmpty { null }, approval)
                        toast(getString(R.string.shift_cash_done, money(amount)))
                    }
                    true
                }
            }.show()
        }
    }

    private fun close() {
        AmountDialog(this, getString(R.string.shift_count), AmountDialog.Kind.MONEY, graph.settings.store.value.currency, message = getString(R.string.shift_count_hint), allowZero = true) { counted ->
            // With the confirmation, an optional note for the owner (why the drawer is over or short):
            // it could not be written anywhere (2026-10 review). It shows on the shift report.
            Dialogs.input(
                this, getString(R.string.shift_close), getString(R.string.shift_close_note_hint),
                message = getString(R.string.shift_close_confirm, money(counted)),
            ) { note ->
                launchUi {
                    val shiftId = graph.shifts.current.value?.id ?: return@launchUi
                    graph.shifts.close(counted, note.ifEmpty { null })
                    if (graph.permissions.allowed(Perm.SHIFT_REPORT)) {
                        startActivity(ShiftReportActivity.intent(this@ShiftActivity, shiftId))
                    } else {
                        Dialogs.message(this@ShiftActivity, getString(R.string.shift_closed), getString(R.string.shift_closed_blind, money(counted)))
                    }
                }
                true
            }
        }.show()
    }
}

/** A shift's report: sales, payments, cash drawer, credit; printable (needs SHIFT_REPORT). */
class ShiftReportActivity : ScreenActivity() {

    private var shiftId = 0L
    private val tz = TimeZone.getDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shiftId = intent.getLongExtra(EXTRA_ID, 0L)
        setScreen(getString(R.string.shift_report))
        guard(Perm.SHIFT_REPORT)
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi {
            val report = graph.shifts.report(shiftId) ?: return@launchUi finish()
            val (moves, names) = graph.db().read { ShiftDao.moves(it, shiftId) to StaffDao.names(it) }
            val store = graph.settings.store.value
            val layout = ShiftReportLayout(store.currency, ShiftText.forLanguage(getString(R.string.ui_lang)), tz)
            val form = Form(this@ShiftReportActivity)
            for (s in layout.sections(report)) {
                s.title?.let { form.section(it) }
                for (r in s.rows) form.row(r.label, r.value, r.bold)
            }
            if (moves.isNotEmpty()) {
                form.section(getString(R.string.shift_moves))
                for (m in moves) form.row(moveText(m, names), MoneyFormat.format(if (m.kind == CashMoveKind.CASH_IN) m.amount else -m.amount, store.currency))
            }
            if (graph.settings.device.value.hasPrinter) {
                form.button(getString(R.string.shift_print), primary = true) {
                    launchUi {
                        graph.shifts.print(shiftId)
                        toast(R.string.result_printing)
                    }
                }
            }
            content.removeAllViews()
            content.addView(form.view)
        }
    }

    private fun moveText(m: CashMove, names: Map<Long, String>): String {
        val kind = getString(
            when (m.kind) {
                CashMoveKind.CASH_IN -> R.string.shift_cash_in
                CashMoveKind.CASH_OUT -> R.string.shift_cash_out
                else -> R.string.shift_drop
            },
        )
        return listOfNotNull(DateText.time(m.at, tz), kind, m.reason, m.staffId?.let { names[it] }).joinToString(" · ")
    }

    companion object {
        private const val EXTRA_ID = "shift_id"

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, ShiftReportActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

/** Past shifts of every till, newest first. */
class ShiftsActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private var names: Map<Long, String> = emptyMap()
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<Shift>(
        bind = { h, s ->
            val currency = graph.settings.store.value.currency
            val sub = listOfNotNull(getString(R.string.shift_till, s.deviceNo), names[s.openedBy]).joinToString(" · ")
            val diff = if (s.countedCash != null && s.expectedCash != null) s.countedCash - s.expectedCash else null
            val value = diff?.let { (if (it > 0L) "+" else "") + MoneyFormat.format(it, currency) }
            h.set(DateText.dateTime(s.openedAt, tz), sub, if (graph.permissions.allowed(Perm.SHIFT_REPORT)) value else null, if (s.open) getString(R.string.shift_open_tag) else null)
        },
        onClick = { s -> requireAccess(Perm.SHIFT_REPORT) { startActivity(ShiftReportActivity.intent(this, s.id)) } },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.shift_history), R.layout.list_plain) ?: return
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.shift_history_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
    }

    override fun onStarted(scope: CoroutineScope) {
        job?.cancel()
        job = launchUi {
            names = graph.db().read { StaffDao.names(it) }
            val items = graph.shifts.page(null, PAGE)
            adapter.submit(items)
            end = items.size < PAGE
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = launchUi {
            val more = graph.shifts.page(after, PAGE)
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    private companion object {
        const val PAGE = 50
    }
}
