package com.lekaspos.ui.reports

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.report.Bucket
import com.lekaspos.core.report.Granularity
import com.lekaspos.core.report.Period
import com.lekaspos.core.report.Preset
import com.lekaspos.core.report.ReportMath
import com.lekaspos.core.time.DateText
import com.lekaspos.core.time.Days
import com.lekaspos.data.report.SlowMover
import com.lekaspos.domain.report.ReportService
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.visible
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Sales reports (needs REPORTS): a period (today, this week, last month, … or any dates), its
 * totals and gross profit against the period before, sales by day, week or month, payment
 * methods, cashiers, categories, best sellers and the stock on hand. Everything can be
 * exported to CSV, including the receipt list for the monthly consolidated e-invoice.
 */
class ReportsActivity : ScreenActivity() {

    private val tz = TimeZone.getDefault()
    private lateinit var chips: LinearLayout
    private lateinit var body: LinearLayout
    private var period: Period = Preset.TODAY.period(today())
    private var preset: Preset? = Preset.TODAY
    private var job: Job? = null

    private fun today() = Days.epochDay(System.currentTimeMillis(), TimeZone.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            period = Period(it.getLong(STATE_FROM), it.getLong(STATE_TO))
            preset = it.getString(STATE_PRESET)?.let { name -> Preset.valueOf(name) }
        }
        setScreen(getString(R.string.reports_title))
        val density = resources.displayMetrics.density
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), 0)
        }
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(column)
        lateinit var more: ImageButton
        more = addAction(R.drawable.ic_more, R.string.export_title) { exportMenu(more) }
        buildChips()
        guard(Perm.REPORTS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_FROM, period.from)
        outState.putLong(STATE_TO, period.to)
        outState.putString(STATE_PRESET, preset?.name)
    }

    override fun onStarted(scope: CoroutineScope) {
        // "Today" (or "This week" …) is today's again when the screen comes back the next morning.
        preset?.let { period = it.period(today()) }
        load()
    }

    private fun buildChips() {

        chips.removeAllViews()
        val density = resources.displayMetrics.density
        val entries = PRESETS.map { (p, label) -> getString(label) to { choose(p) } } +
            (getString(R.string.report_custom) to { pickDates() })
        for ((i, e) in entries.withIndex()) {
            val selected = if (i < PRESETS.size) preset == PRESETS[i].first else preset == null
            val b = Button(this, null, 0, R.style.Widget_Lekas_Toggle)
            b.text = e.first
            b.isSelected = selected
            b.setOnClickListener { e.second() }
            chips.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * density).toInt()).apply {
                marginEnd = (6 * density).toInt()
            })
        }
    }

    private fun choose(p: Preset) {
        preset = p
        period = p.period(today())
        buildChips()
        load()
    }

    /** Custom period: first day, then last day (inclusive). */
    private fun pickDates() {
        fun ask(title: Int, day: Long, then: (Long) -> Unit) {
            val ymd = Days.toYmd(day)
            val d = DatePickerDialog(this, { _, y, m, dd -> then(Days.fromYmd(y * 10_000 + (m + 1) * 100 + dd)) }, ymd / 10_000, ymd / 100 % 100 - 1, ymd % 100)
            d.setTitle(title)
            d.datePicker.maxDate = System.currentTimeMillis()
            d.show()
        }
        ask(R.string.report_from, period.from) { from ->
            ask(R.string.report_to, maxOf(from, period.to - 1)) { last ->
                preset = null
                period = Period(minOf(from, last), maxOf(from, last) + 1)
                buildChips()
                load()
            }
        }
    }

    private fun money(v: Long) = MoneyFormat.format(v, graph.settings.store.value.currency)

    private fun load() {
        job?.cancel()
        val p = period
        job = launchUi {
            val rep = graph.reports.build(p)
            val stock = graph.reports.stock()
            render(rep, stock)
        }
    }

    private fun periodText(p: Period): String =
        if (p.days == 1L) DateText.date(p.from) else getString(R.string.report_range, DateText.date(p.from), DateText.date(p.to - 1))

    private fun render(rep: ReportService.Report, stock: ReportService.Stock) {
        val t = rep.totals
        val f = Form(this)
        f.info(periodText(rep.period))
        f.section(getString(R.string.report_summary))
        val sales = t.total + t.refundTotal
        f.row(getString(R.string.report_sales_count, t.saleCount), money(sales), bold = true)
        if (t.refundCount > 0) f.row(getString(R.string.report_refunds_count, t.refundCount), money(-t.refundTotal))
        if (t.voidCount > 0) f.row(getString(R.string.report_voids), t.voidCount.toString())
        if (t.discount != 0L) f.row(getString(R.string.report_discounts), money(t.discount))
        f.row(getString(R.string.report_net_ex_tax), money(t.netEx))
        if (t.tax != 0L) f.row(getString(R.string.report_tax), money(t.tax))
        if (t.rounding != 0L) f.row(getString(R.string.report_rounding), money(t.rounding))
        f.row(getString(R.string.report_total), money(t.total), bold = true)
        f.row(getString(R.string.report_cost), money(t.cost))
        f.row(getString(R.string.report_profit), money(t.grossProfit), bold = true)
        rep.marginBp?.let { f.row(getString(R.string.report_margin), percent(it.toLong())) }
        if (t.saleCount > 0) f.row(getString(R.string.report_average), money(rep.averageSale))
        val prev = rep.previous.total
        if (prev != 0L) {
            val change = ReportMath.changeBp(t.total, prev)
            val text = change?.let { (if (it > 0L) "+" else "") + percent(it) } ?: "-"
            f.row(getString(R.string.report_vs_previous, money(prev)), text)
        }

        if (rep.buckets.isNotEmpty() && rep.period.days > 1L) {
            f.section(getString(when (rep.granularity) {
                Granularity.DAY -> R.string.report_by_day
                Granularity.WEEK -> R.string.report_by_week
                Granularity.MONTH -> R.string.report_by_month
            }))
            for (b in rep.buckets) f.row(bucketLabel(b, rep.granularity), bucketValue(b))
        }
        if (rep.payments.isNotEmpty()) {
            f.section(getString(R.string.report_payments))
            for (x in rep.payments) f.row(getString(R.string.report_count_label, x.name ?: "-", x.count), money(x.amount))
        }
        if (rep.staff.size > 1 || rep.staff.any { it.staffId != 0L }) {
            f.section(getString(R.string.report_cashiers))
            for (x in rep.staff) f.row(getString(R.string.report_count_label, x.name ?: "-", x.saleCount), money(x.total))
        }
        if (rep.categories.isNotEmpty()) {
            f.section(getString(R.string.report_categories))
            for (x in rep.categories) {
                f.row(x.name ?: getString(R.string.report_no_category), getString(R.string.report_net_profit, money(x.netEx), money(x.grossProfit)))
            }
        }
        if (rep.topProducts.isNotEmpty()) {
            f.section(getString(R.string.report_top_products))
            for (x in rep.topProducts) {
                val name = x.name ?: getString(R.string.report_other_items)
                f.row(getString(R.string.report_qty_label, name, MoneyFormat.formatQty(x.qty)), money(x.netEx))
            }
        }
        f.section(getString(R.string.report_stock))
        f.row(getString(R.string.report_stock_value, stock.products), money(stock.value), bold = true)
        for (c in stock.categories.take(12)) {
            f.row(c.name ?: getString(R.string.report_no_category), money(ReportMath.value(c.valueMilli, 1L)))
        }
        f.button(getString(R.string.report_slow_movers)) { startActivity(SlowMoversActivity.intent(this, rep.period)) }
        f.info(getString(R.string.report_note))
        body.removeAllViews()
        body.addView(f.view)
    }

    private fun percent(bp: Long): String = MoneyFormat.plain(bp, 2) + "%"

    private fun bucketLabel(b: Bucket, g: Granularity): String = when (g) {
        Granularity.DAY -> DateText.date(b.start)
        Granularity.WEEK -> getString(R.string.report_range, DateText.date(b.start), DateText.date(b.end - 1))
        Granularity.MONTH -> {
            val ymd = Days.toYmd(b.start)
            val c = Calendar.getInstance()
            c.clear()
            c.set(ymd / 10_000, ymd / 100 % 100 - 1, 1)
            android.text.format.DateFormat.format("MMMM yyyy", c).toString()
        }
    }

    private fun bucketValue(b: Bucket): String =
        if (b.sales == 0L && b.total == 0L) "-" else getString(R.string.report_bucket_value, money(b.total), b.sales)

    private fun exportMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = listOf(R.string.export_summary, R.string.export_daily, R.string.export_products, R.string.export_receipts)
        for ((i, res) in items.withIndex()) m.menu.add(0, res, i, res)
        m.setOnMenuItemClickListener {
            val (kind, name) = when (it.itemId) {
                R.string.export_summary -> ReportService.Export.SUMMARY to "summary"
                R.string.export_daily -> ReportService.Export.DAILY to "daily-sales"
                R.string.export_products -> ReportService.Export.PRODUCTS to "products-sold"
                else -> ReportService.Export.RECEIPTS to "receipts"
            }
            val p = period
            val file = "lekaspos-$name-${DateText.isoDate(p.from)}_${DateText.isoDate(p.to - 1)}.csv"
            exportCsv(file) { out -> graph.reports.export(kind, p, out, tz) }
            true
        }
        m.show()
    }

    companion object {
        private const val STATE_FROM = "report.from"
        private const val STATE_TO = "report.to"
        private const val STATE_PRESET = "report.preset"
        private val PRESETS = listOf(
            Preset.TODAY to R.string.report_today,
            Preset.YESTERDAY to R.string.report_yesterday,
            Preset.THIS_WEEK to R.string.report_this_week,
            Preset.LAST_WEEK to R.string.report_last_week,
            Preset.THIS_MONTH to R.string.report_this_month,
            Preset.LAST_MONTH to R.string.report_last_month,
            Preset.THIS_YEAR to R.string.report_this_year,
            Preset.LAST_YEAR to R.string.report_last_year,
        )
    }
}

/** Products with stock that did not sell in a period, most stock value first. */
class SlowMoversActivity : ScreenActivity() {

    private lateinit var header: TextView
    private lateinit var empty: TextView
    private var period = Period(0, 1)

    private val adapter = RowAdapter<SlowMover>(
        bind = { h, s ->
            val currency = graph.settings.store.value.currency
            h.set(s.name, getString(R.string.report_in_stock, MoneyFormat.formatQty(s.qty)), MoneyFormat.format(ReportMath.value(s.qty, s.cost), currency))
        },
        onClick = { s -> startActivity(com.lekaspos.ui.products.ProductEditActivity.newIntent(this, productId = s.productId)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        period = Period(intent.getLongExtra(EXTRA_FROM, 0L), intent.getLongExtra(EXTRA_TO, 1L))
        val v = setScreen(getString(R.string.report_slow_movers), R.layout.list_header) ?: return
        header = v.findViewById(R.id.list_header)
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.report_slow_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        guard(Perm.REPORTS)
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi {
            val (items, total) = graph.reports.slowMovers(period)
            val currency = graph.settings.store.value.currency
            header.text = getString(
                R.string.report_slow_header,
                if (period.days == 1L) DateText.date(period.from) else getString(R.string.report_range, DateText.date(period.from), DateText.date(period.to - 1)),
                total.first, MoneyFormat.format(ReportMath.value(total.second, 1L), currency),
            )
            adapter.submit(items)
            empty.visible(items.isEmpty())
        }
    }

    companion object {
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"

        fun intent(ctx: Context, p: Period): Intent =
            Intent(ctx, SlowMoversActivity::class.java).putExtra(EXTRA_FROM, p.from).putExtra(EXTRA_TO, p.to)
    }
}
