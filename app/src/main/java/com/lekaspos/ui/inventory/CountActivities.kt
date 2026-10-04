package com.lekaspos.ui.inventory

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.inventory.CostMath
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ProductListItem
import com.lekaspos.data.stock.CountRow
import com.lekaspos.data.stock.CountSession
import com.lekaspos.data.stock.CountSessionDao
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.FieldScan
import com.lekaspos.ui.common.ScanInput
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.Beeper
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Stock counts (stock takes): open ones first, then finished ones with their reports. */
class CountSessionsActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private val tz = TimeZone.getDefault()
    private val adapter = RowAdapter<CountSession>(
        bind = { h, s ->
            h.set(
                title = s.name,
                subtitle = listOf(DateText.dateTime(s.startedAt, tz), resources.getQuantityString(R.plurals.inv_counted, s.counted, s.counted))
                    .joinToString(" · "),
                tag = if (s.open) getString(R.string.inv_count_open) else null,
            )
        },
        onClick = { s -> startActivity(if (s.open) CountActivity.intent(this, s.id) else CountReportActivity.intent(this, s.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard(Perm.MANAGE_STOCK) // its approval is asked again after Android ended the app (it was held by Inventory)
        val v = setScreen(getString(R.string.inv_count), R.layout.list_plain) ?: return
        addAction(R.drawable.ic_add, R.string.inv_count_new) { newSession() }
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.inv_count_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi {
            val items = graph.db().read { CountSessionDao.list(it) }.sortedByDescending { it.open }
            adapter.submit(items)
            empty.visible(items.isEmpty())
        }
    }

    private fun newSession() {
        launchUi {
            val cats = graph.db().read { CategoryDao.list(it) }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val col = LinearLayout(this@CountSessionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            val name = EditText(this@CountSessionsActivity).apply {
                setText(getString(R.string.inv_count_default_name, DateText.date(com.lekaspos.core.time.Days.epochDay(System.currentTimeMillis(), TimeZone.getDefault()))))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
            }
            col.addView(name)
            col.addView(TextView(this@CountSessionsActivity, null, 0, R.style.Text_Lekas_Label).apply { setText(R.string.inv_count_scope) })
            val scope = Spinner(this@CountSessionsActivity).apply {
                adapter = android.widget.ArrayAdapter(
                    this@CountSessionsActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.inv_count_all)) + cats.map { it.name },
                )
            }
            col.addView(scope)
            val d = AlertDialog.Builder(this@CountSessionsActivity).setTitle(R.string.inv_count_new).setView(col)
                .setPositiveButton(R.string.inv_count_start, null).setNegativeButton(R.string.cancel, null).show().trackedBy(this@CountSessionsActivity)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setOnClickListener
                val category: Category? = cats.getOrNull(scope.selectedItemPosition - 1)
                d.dismiss()
                launchUi {
                    val id = graph.inventory.startCount(n, category?.id)
                    startActivity(CountActivity.intent(this@CountSessionsActivity, id))
                }
            }
        }
    }
}

/**
 * Counting: the products in scope with their current stock; tap one or scan it and type what is
 * on the shelf. Each count applies at once, so the shop can keep selling (D-035).
 */
class CountActivity : ScreenActivity() {

    private var sessionId = 0L
    private var session: CountSession? = null
    private var counted: MutableMap<Long, Long> = HashMap()
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private var beeper: Beeper? = null
    private val scanInput = ScanInput(onScan = { onCode(it) }, onTyped = { text, _ -> typed(text) })
    private val fieldScan = FieldScan { onCode(it) }

    private val adapter = RowAdapter<ProductListItem>(
        bind = { h, p ->
            val stock = p.stockQty ?: 0L
            val c = counted[p.id]
            h.set(
                title = p.name,
                subtitle = getString(R.string.inv_stock_now, InventoryUi.qty(stock, p.unit)),
                value = c?.let { InventoryUi.qty(it, p.unit) },
                tag = when {
                    c != null -> getString(R.string.inv_counted_tag)
                    !p.active -> getString(R.string.product_hidden)
                    else -> null
                },
            )
        },
        onClick = { p -> countProduct(p.id) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard(Perm.MANAGE_STOCK) // its approval is asked again after Android ended the app (it was held by Inventory)
        sessionId = intent.getLongExtra(EXTRA_SESSION, 0L)
        val v = setScreen(getString(R.string.inv_count), R.layout.list_with_search) ?: return
        addAction(R.drawable.ic_more, R.string.inv_count_report) { startActivity(CountReportActivity.intent(this, sessionId)) }
        search = v.findViewById(R.id.list_search)
        search.setHint(R.string.inv_pick_hint)
        empty = v.findViewById(R.id.list_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = reload(debounce = true)
        })
        beeper = Beeper.create()
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi {
            val (s, c) = graph.db().read { CountSessionDao.get(it, sessionId) to CountSessionDao.countedQty(it, sessionId) }
            if (s == null) return@launchUi finish()
            if (!s.open) return@launchUi closed() // finished on another till meanwhile
            session = s
            counted = HashMap(c)
            setScreenTitle(s.name)
            reload(debounce = false)
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

    override fun screenKey(event: KeyEvent): Boolean =
        if (search.hasFocus()) fieldScan.onKey(event, search) else scanInput.onKey(event)

    override fun serialScans(): (String) -> Unit = { onCode(it) }

    private fun typed(text: String) {
        search.setText(text)
        search.setSelection(text.length)
        search.requestFocus()
    }

    private fun reload(debounce: Boolean) {
        val s = session ?: return
        job?.cancel()
        val q = search.text.toString()
        job = scope.launch {
            if (debounce) delay(200L)
            val cat = s.categoryId
            // Switched-off products are counted too (they can still be on the shelf), and a count of
            // one category finds only that category's products (2026-10 review).
            val items = graph.db().read { r ->
                when {
                    // In a category count, a wider search first: the first 100 matches of the whole
                    // shop could hold few of this category's products.
                    q.isNotBlank() -> ProductDao.search(r, q, if (cat == null) 100 else 1000, includeInactive = true)
                        .filter { p -> cat == null || p.categoryId == cat }
                        .take(100)
                    cat != null -> ProductDao.byCategory(r, cat, null, PAGE, includeInactive = true)
                    else -> ProductDao.managePage(r, null, PAGE)
                }
            }
            adapter.submit(items)
            end = q.isNotBlank() || items.size < PAGE
            empty.setText(R.string.products_none_found)
            empty.visible(items.isEmpty())
            val n = counted.size
            setScreenTitle(getString(R.string.inv_count_title, s.name, n))
        }
    }

    private fun loadMore() {
        val s = session ?: return
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val cat = s.categoryId
            val more = graph.db().read { r ->
                if (cat != null) {
                    ProductDao.byCategory(r, cat, after, PAGE, includeInactive = true)
                } else {
                    ProductDao.managePage(r, after, PAGE)
                }
            }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    private fun onCode(code: String) {
        launchUi {
            val p = InventoryUi.resolve(graph, code)
            if (p == null) {
                beeper?.error()
                toast(getString(R.string.sell_not_found_message, code))
            } else {
                beeper?.ok()
                countProduct(p.id)
            }
        }
    }

    private fun countProduct(productId: Long) {
        launchUi {
            val p = InventoryUi.product(graph, productId) ?: return@launchUi
            InventoryUi.askQty(this@CountActivity, getString(R.string.inv_count_of, p.name), p, counted[p.id] ?: 0L, allowZero = true) { qty ->
                launchUi {
                    try {
                        graph.inventory.count(sessionId, p.id, qty)
                    } catch (e: ActionRefused) {
                        if (e.reason != ActionRefused.Reason.NOT_FOUND) throw e
                        return@launchUi closed() // finished on another till: the count was not recorded
                    }
                    counted[p.id] = qty
                    reloadVisible()
                }
            }
        }
    }

    /** The count was finished: says so and leaves (its report is in the list of counts). */
    private fun closed() {
        toast(R.string.inv_count_closed)
        finish()
    }

    /** Refreshes stock figures of the listed products (they changed by the count). */
    private fun reloadVisible() {
        val ids = adapter.items.map { it.id }
        launchUi {
            val levels = graph.db().read { r -> ids.associateWith { com.lekaspos.data.stock.StockDao.level(r, it) } }
            adapter.submit(adapter.items.map { it.copy(stockQty = levels[it.id] ?: it.stockQty) })
            session?.let { setScreenTitle(getString(R.string.inv_count_title, it.name, counted.size)) }
        }
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val PAGE = 60

        fun intent(ctx: Context, sessionId: Long): Intent = Intent(ctx, CountActivity::class.java).putExtra(EXTRA_SESSION, sessionId)
    }
}

/** Variance report of a count: expected vs counted per product, and the value gained or lost. */
class CountReportActivity : ScreenActivity() {

    private var sessionId = 0L
    private lateinit var header: TextView
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<CountRow>(
        bind = { h, c ->
            val currency = graph.settings.store.value.currency
            val expected = c.expected
            val diff = expected?.let { c.qty - it }
            h.set(
                title = c.name ?: "?",
                subtitle = listOfNotNull(
                    DateText.time(c.at, tz),
                    getString(R.string.inv_count_line, expected?.let { InventoryUi.qty(it, c.unit) } ?: "?", InventoryUi.qty(c.qty, c.unit)),
                ).joinToString(" · "),
                value = diff?.let { InventoryUi.signedQty(it, c.unit) },
                tag = if (diff != null && diff != 0L && c.unitCost != null && expected != null) {
                    MoneyFormat.format(CostMath.varianceValue(c.qty, expected, c.unitCost), currency)
                } else {
                    null
                },
            )
        },
        onClick = { startActivity(StockHistoryActivity.intent(this, it.productId)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getLongExtra(EXTRA_SESSION, 0L)
        val v = setScreen(getString(R.string.inv_count_report), R.layout.list_header) ?: return
        header = v.findViewById(R.id.list_header)
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.inv_count_none)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        launchUi {
            val s = graph.db().read { CountSessionDao.get(it, sessionId) } ?: return@launchUi finish()
            setScreenTitle(s.name)
            if (s.open) addAction(R.drawable.ic_close, R.string.inv_count_finish) { finishSession(s) }
        }
    }

    override fun onStarted(scope: CoroutineScope) {
        job?.cancel()
        job = this.scope.launch {
            val rows = graph.db().read { CountSessionDao.counts(it, sessionId, null, PAGE) }
            adapter.submit(rows)
            end = rows.size < PAGE
            empty.visible(rows.isEmpty())
        }
        summary()
    }

    /** Totals over every count of the session: what was gained and lost, at cost. */
    private fun summary() {
        launchUi {
            val s = graph.db().read { CountSessionDao.summary(it, sessionId) }
            val currency = graph.settings.store.value.currency
            header.text = getString(
                R.string.inv_count_summary, s.counts, MoneyFormat.format(s.gained, currency), MoneyFormat.format(s.lost, currency),
                MoneyFormat.format(s.gained + s.lost, currency),
            )
        }
    }

    private fun finishSession(s: CountSession) {
        Dialogs.confirm(this, getString(R.string.inv_count_finish), getString(R.string.inv_count_finish_confirm, s.name), getString(R.string.inv_count_finish)) {
            launchUi {
                graph.inventory.finishCount(s.id)
                toast(R.string.inv_count_finished)
                finish()
            }
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = scope.launch {
            val more = graph.db().read { CountSessionDao.counts(it, sessionId, after, PAGE) }
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val PAGE = 50

        fun intent(ctx: Context, sessionId: Long): Intent = Intent(ctx, CountReportActivity::class.java).putExtra(EXTRA_SESSION, sessionId)
    }
}
