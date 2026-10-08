package com.lekaspos.ui.reports

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.report.Period
import com.lekaspos.core.staff.StaffCheck
import com.lekaspos.core.staff.Tally
import com.lekaspos.core.time.DateText
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

/**
 * The staff check (D-067), from Reports: for the period, per person, their sales and everything in
 * the activity log that can hide missing cash — items taken off after the customer saw the total
 * first, then bills cleared, items taken off, voids, refunds, the drawer opened without a sale, cash
 * taken out, stock written off — and the cash of the shifts they opened. Needs VIEW_AUDIT.
 */
class StaffCheckActivity : ScreenActivity() {

    private var period = Period(0, 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        period = Period(intent.getLongExtra(EXTRA_FROM, 0L), intent.getLongExtra(EXTRA_TO, 1L))
        setScreen(getString(R.string.staff_check_title))
        guard(Perm.VIEW_AUDIT)
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi { render(graph.reports.staffCheck(period)) }
    }

    private fun money(v: Long) = MoneyFormat.format(v, graph.settings.store.value.currency)

    private fun render(list: List<StaffCheck>) {
        val f = Form(this)
        val p = period
        f.info(if (p.days == 1L) DateText.date(p.from) else getString(R.string.report_range, DateText.date(p.from), DateText.date(p.to - 1)))
        f.info(getString(R.string.staff_check_intro))
        if (list.isEmpty()) f.info(getString(R.string.staff_check_none))
        for (s in list) {
            val c = s.checks
            f.section(s.name ?: "-")
            f.row(getString(R.string.check_sales, s.sales.count), money(s.sales.amount), bold = true)
            if (c.afterPay.count > 0L) f.row(getString(R.string.check_after_pay, c.afterPay.count), money(c.afterPay.amount), bold = true)
            tally(f, R.string.check_cleared, c.cleared)
            tally(f, R.string.check_removed, c.removed)
            tally(f, R.string.check_voids, c.voids)
            tally(f, R.string.check_refunds, c.refunds)
            if (c.discounts > 0L) f.row(getString(R.string.check_discounts), c.discounts.toString())
            if (c.drawerOpens > 0L) f.row(getString(R.string.check_drawer), c.drawerOpens.toString(), bold = true)
            if (c.copies > 0L) f.row(getString(R.string.check_copies), c.copies.toString())
            tally(f, R.string.check_cash_out, c.cashOut)
            tally(f, R.string.check_write_off, c.writeOffs)
            if (c.continued > 0L) f.row(getString(R.string.check_continued), c.continued.toString())
            if (c.floatDiffs.count > 0L) {
                val v = if (c.floatDiffs.amount > 0L) "+" + money(c.floatDiffs.amount) else money(c.floatDiffs.amount)
                f.row(getString(R.string.check_float_diff, c.floatDiffs.count), v, bold = c.floatDiffs.amount < 0L)
            }
            if (s.shifts > 0L) {
                val v = if (s.overShort > 0L) "+" + money(s.overShort) else money(s.overShort)
                f.row(getString(R.string.check_over_short, s.shifts, s.shortShifts), v, bold = s.overShort < 0L)
            }
        }
        content.removeAllViews()
        content.addView(f.view)
    }

    /** "Bills cleared (2)  RM 23.40", only when there were any; refunds as a positive amount. */
    private fun tally(f: Form, label: Int, t: Tally) {
        if (t.count > 0L) f.row(getString(label, t.count), money(abs(t.amount)))
    }

    companion object {
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"

        fun intent(ctx: Context, p: Period): Intent =
            Intent(ctx, StaffCheckActivity::class.java).putExtra(EXTRA_FROM, p.from).putExtra(EXTRA_TO, p.to)
    }
}
