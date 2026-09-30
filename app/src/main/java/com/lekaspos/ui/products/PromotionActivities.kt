package com.lekaspos.ui.products

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.PromoKind
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.core.time.Days
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.promo.PromotionRow
import com.lekaspos.domain.promo.PromotionService
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.inventory.ProductPickActivity
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope

/** "3 for RM10.00" / "Buy 1 get 1 free". */
fun promoDeal(ctx: Context, p: PromotionRow, currency: CurrencySpec): String = when (p.kind) {
    PromoKind.MULTI_PRICE -> ctx.getString(R.string.promo_deal_multi, p.buyQty, MoneyFormat.format(p.groupPrice, currency))
    else -> ctx.getString(R.string.promo_deal_free, p.buyQty, p.freeQty)
}

/** Promotions (Phase 8): the list; tap to edit, + to add. */
class PromotionsActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private val today get() = Days.epochDay(System.currentTimeMillis(), TimeZone.getDefault())

    private val adapter = RowAdapter<PromotionRow>(
        bind = { h, p -> h.set(p.name, summary(p)) },
        onClick = { p -> startActivity(PromotionEditActivity.intent(this, p.id)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.promo_title), R.layout.list_header) ?: return
        v.findViewById<TextView>(R.id.list_header).setText(R.string.promo_help)
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.promo_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        addAction(R.drawable.ic_add, R.string.promo_add) { startActivity(PromotionEditActivity.intent(this, 0L)) }
    }

    override fun onStarted(scope: CoroutineScope) {
        launchUi {
            graph.settings.load()
            graph.promotions.load()
            val all = graph.promotions.all()
            adapter.submit(all)
            empty.visible(all.isEmpty())
        }
    }

    private fun summary(p: PromotionRow): String {
        val parts = ArrayList<String>(4)
        parts.add(promoDeal(this, p, graph.settings.store.value.currency))
        parts.add(getString(R.string.promo_products_count, p.productIds.size))
        val start = p.startDay
        val end = p.endDay
        parts.add(
            when {
                !p.active -> getString(R.string.promo_state_off)
                end != null && end < today -> getString(R.string.promo_state_ended)
                start != null && start > today -> getString(R.string.promo_state_starts, DateText.date(start))
                end != null -> getString(R.string.promo_state_until, DateText.date(end))
                else -> getString(R.string.promo_state_on)
            },
        )
        return parts.joinToString(" · ")
    }
}

/** Create or edit a promotion (needs the product-management permission). */
class PromotionEditActivity : ScreenActivity() {

    private var before: PromotionRow? = null
    private val productIds = ArrayList<Long>()
    private val productNames = HashMap<Long, String>()
    private var startDay: Long? = null
    private var endDay: Long? = null

    private lateinit var name: EditText
    private lateinit var kind: Spinner
    private lateinit var buy: EditText
    private lateinit var free: EditText
    private lateinit var price: EditText
    private lateinit var productList: LinearLayout
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var active: Switch

    private val currency get() = graph.settings.store.value.currency

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        setScreen(getString(if (id == 0L) R.string.promo_add else R.string.promo_edit))
        guard(Perm.MANAGE_PRODUCTS)
        launchUi {
            graph.settings.load()
            graph.promotions.load()
            val p = graph.promotions.all().firstOrNull { it.id == id }
            before = p
            if (p != null) {
                productIds.addAll(p.productIds)
                startDay = p.startDay
                endDay = p.endDay
            }
            loadNames()
            build(p)
        }
    }

    private suspend fun loadNames() {
        val ids = productIds.toList()
        val names = graph.db().read { r -> ids.associateWith { ProductDao.get(r, it)?.name ?: "#$it" } }
        productNames.putAll(names)
    }

    private fun build(p: PromotionRow?) {
        val f = Form(this)
        name = f.text(getString(R.string.promo_name), p?.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        f.info(getString(R.string.promo_name_hint))
        val kinds = listOf(getString(R.string.promo_kind_multi), getString(R.string.promo_kind_free))
        kind = f.choice(getString(R.string.promo_kind), kinds, if (p?.kind == PromoKind.BUY_GET_FREE) 1 else 0) { showKind() }
        buy = f.text(getString(R.string.promo_buy_qty), p?.buyQty?.toString() ?: "", InputType.TYPE_CLASS_NUMBER)
        free = f.text(getString(R.string.promo_free_qty), p?.freeQty?.takeIf { it > 0 }?.toString() ?: "1", InputType.TYPE_CLASS_NUMBER)
        price = f.text(
            getString(R.string.promo_group_price),
            p?.takeIf { it.kind == PromoKind.MULTI_PRICE }?.let { MoneyFormat.plain(it.groupPrice, currency.decimals) },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )

        f.section(getString(R.string.promo_products))
        f.info(getString(R.string.promo_products_help))
        productList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.add(productList)
        renderProducts()
        f.button(getString(R.string.promo_add_product)) {
            @Suppress("DEPRECATION")
            startActivityForResult(ProductPickActivity.intent(this, getString(R.string.promo_add_product)), REQ_PICK)
        }

        f.section(getString(R.string.promo_dates))
        startButton = f.button("") { pickDay(true) }
        endButton = f.button("") { pickDay(false) }
        renderDates()
        active = f.switch(getString(R.string.promo_active), p?.active ?: true)

        f.button(getString(R.string.save), primary = true) { save() }
        if (p != null) f.button(getString(R.string.delete)) { delete(p) }
        content.removeAllViews()
        content.addView(f.view)
        showKind()
    }

    private fun showKind() {
        val multi = kind.selectedItemPosition == 0
        price.visible(multi)
        free.visible(!multi)
        // Form labels sit right above their fields.
        (price.parent as? ViewGroup)?.let { parent -> parent.getChildAt(parent.indexOfChild(price) - 1)?.visible(multi) }
        (free.parent as? ViewGroup)?.let { parent -> parent.getChildAt(parent.indexOfChild(free) - 1)?.visible(!multi) }
    }

    private fun renderProducts() {
        productList.removeAllViews()
        val density = resources.displayMetrics.density
        for (id in productIds) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48 * density).toInt()
            }
            val label = TextView(this, null, 0, R.style.Text_Lekas_Body).apply { text = productNames[id] ?: "#$id" }
            val remove = ImageButton(this, null, 0, R.style.Widget_Lekas_IconButton).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = getString(R.string.remove)
                setOnClickListener {
                    productIds.remove(id)
                    renderProducts()
                }
            }
            val size = (48 * density).toInt()
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(remove, LinearLayout.LayoutParams(size, size))
            productList.addView(row)
        }
        if (productIds.isEmpty()) {
            productList.addView(TextView(this, null, 0, R.style.Text_Lekas_Caption).apply { setText(R.string.promo_no_products) })
        }
    }

    private fun renderDates() {
        startButton.text = startDay?.let { getString(R.string.promo_starts, DateText.date(it)) } ?: getString(R.string.promo_starts_any)
        endButton.text = endDay?.let { getString(R.string.promo_ends, DateText.date(it)) } ?: getString(R.string.promo_ends_never)
    }

    private fun pickDay(start: Boolean) {
        val today = Days.epochDay(System.currentTimeMillis(), TimeZone.getDefault())
        val ymd = Days.toYmd((if (start) startDay else endDay) ?: today)
        val d = DatePickerDialog(this, { _, y, m, dd ->
            val day = Days.fromYmd(y * 10_000 + (m + 1) * 100 + dd)
            if (start) startDay = day else endDay = day
            renderDates()
        }, ymd / 10_000, ymd / 100 % 100 - 1, ymd % 100)
        d.setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.promo_no_date)) { _, _ ->
            if (start) startDay = null else endDay = null
            renderDates()
        }
        d.show()
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val id = data?.getLongExtra(ProductPickActivity.EXTRA_PRODUCT_ID, 0L) ?: 0L
        if (id == 0L || id in productIds) return
        productIds.add(id)
        launchUi {
            loadNames()
            renderProducts()
        }
    }

    private fun save() {
        val n = name.text.toString().trim()
        val multi = kind.selectedItemPosition == 0
        val buyQty = buy.text.toString().trim().toIntOrNull()
        val freeQty = if (multi) 0 else free.text.toString().trim().toIntOrNull()
        val groupPrice = if (multi) MoneyFormat.parse(price.text.toString(), currency) else 0L
        val problem = when {
            n.isEmpty() -> name.also { it.error = getString(R.string.promo_error_name) }
            buyQty == null || buyQty < (if (multi) 2 else 1) || buyQty > 1_000 ->
                buy.also { it.error = getString(if (multi) R.string.promo_error_multi_qty else R.string.promo_error_qty) }
            !multi && (freeQty == null || freeQty < 1 || freeQty > 1_000) -> free.also { it.error = getString(R.string.promo_error_qty) }
            multi && (groupPrice == null || groupPrice < 0L) -> price.also { it.error = getString(R.string.promo_error_price) }
            else -> null
        }
        if (problem != null) {
            problem.requestFocus()
            return
        }
        if (productIds.isEmpty()) {
            Dialogs.message(this, null, getString(R.string.promo_error_products))
            return
        }
        val s = startDay
        val e = endDay
        if (s != null && e != null && e < s) {
            Dialogs.message(this, null, getString(R.string.promo_error_dates))
            return
        }
        val row = PromotionRow(
            id = before?.id ?: 0L,
            name = n,
            kind = if (multi) PromoKind.MULTI_PRICE else PromoKind.BUY_GET_FREE,
            buyQty = buyQty ?: 0,
            freeQty = freeQty ?: 0,
            groupPrice = groupPrice ?: 0L,
            productIds = productIds.toList(),
            startDay = s,
            endDay = e,
            active = active.isChecked,
        )
        if (PromotionService.toCore(row) == null) {
            Dialogs.message(this, null, getString(R.string.promo_error_qty))
            return
        }
        launchUi {
            graph.promotions.save(before, row)
            toast(R.string.promo_saved)
            finish()
        }
    }

    private fun delete(p: PromotionRow) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.promo_delete_confirm, p.name), getString(R.string.delete)) {
            launchUi {
                graph.promotions.delete(p.id)
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "promotion_id"
        private const val REQ_PICK = 1

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, PromotionEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
