package com.lekaspos.ui.display

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.app.AppGraph
import com.lekaspos.app.AppLanguage
import com.lekaspos.core.display.CustomerLine
import com.lekaspos.core.display.CustomerView
import com.lekaspos.core.model.TileColor
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.data.product.ProductLookDao
import com.lekaspos.data.settings.StoreSettings
import com.lekaspos.ui.common.TileColors
import com.lekaspos.util.Log
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The customer screen (D-069): a [Presentation] on the second display — a TV or monitor the tablet casts to
 * with Miracast ("Screen mirroring", "Smart View", "Wireless display") or one on a cable — while the till's own
 * screen stays the cashier's. It only draws [CustomerView] from the till's state; [CustomerDisplay] decides
 * when it is shown. Texts are scaled to the display and written in the receipt language (the customer's side).
 */
class CustomerScreen(host: Activity, display: Display, private val graph: AppGraph) : Presentation(host, display) {

    private var scope: CoroutineScope? = null
    private val tz = TimeZone.getDefault()
    private val adapter = LinesAdapter()
    private var res: Resources = context.resources
    private var language = ""
    private var currency: CurrencySpec = CurrencySpec.MYR

    /** The product shown large (its look read once), and the category colours. */
    private var lastProduct = Long.MIN_VALUE
    private var categoryColors: Map<Long, Int>? = null

    private lateinit var shop: TextView
    private lateinit var clock: TextView
    private lateinit var welcome: View
    private lateinit var welcomeShop: TextView
    private lateinit var welcomeText: TextView
    private lateinit var thanks: View
    private lateinit var thanksTitle: TextView
    private lateinit var thanksPaid: TextView
    private lateinit var thanksChange: TextView
    private lateinit var bill: LinearLayout
    private lateinit var lines: RecyclerView
    private lateinit var lastBox: View
    private lateinit var lastInitials: TextView
    private lateinit var lastPicture: ImageView
    private lateinit var lastName: TextView
    private lateinit var lastDetail: TextView
    private lateinit var discount: TextView
    private lateinit var totalLabel: TextView
    private lateinit var total: TextView
    private lateinit var count: TextView

    /** What is on the screen now (tests read it). */
    var shown: CustomerView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.customer_screen)
        val m = context.resources.displayMetrics
        val widthDp = m.widthPixels / m.density
        val heightDp = m.heightPixels / m.density
        // Laid out for 1280 × 720dp: a TV that says it is 960 × 540dp gets three quarters of every size.
        scaleTexts(findViewById(R.id.cs_root), minOf(widthDp / 1280f, heightDp / 720f).coerceIn(0.5f, 2f))
        shop = findViewById(R.id.cs_shop)
        clock = findViewById(R.id.cs_clock)
        welcome = findViewById(R.id.cs_welcome)
        welcomeShop = findViewById(R.id.cs_welcome_shop)
        welcomeText = findViewById(R.id.cs_welcome_text)
        thanks = findViewById(R.id.cs_thanks)
        thanksTitle = findViewById(R.id.cs_thanks_title)
        thanksPaid = findViewById(R.id.cs_thanks_paid)
        thanksChange = findViewById(R.id.cs_thanks_change)
        bill = findViewById(R.id.cs_bill)
        lines = findViewById(R.id.cs_lines)
        lastBox = findViewById(R.id.cs_last_box)
        lastInitials = findViewById(R.id.cs_last_initials)
        lastPicture = findViewById(R.id.cs_last_picture)
        lastName = findViewById(R.id.cs_last_name)
        lastDetail = findViewById(R.id.cs_last_detail)
        discount = findViewById(R.id.cs_discount)
        totalLabel = findViewById(R.id.cs_total_label)
        total = findViewById(R.id.cs_total)
        count = findViewById(R.id.cs_count)
        lines.layoutManager = LinearLayoutManager(context)
        lines.adapter = adapter
        lines.itemAnimator = null
        lastPicture.clipToOutline = true
        // A screen standing upright: the list over the total.
        if (heightDp > widthDp) {
            bill.orientation = LinearLayout.VERTICAL
            lines.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f).apply { marginStart = dp(24); marginEnd = dp(24) }
            findViewById<View>(R.id.cs_side).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
            lastBox.visibility = View.GONE
        } else {
            lastBox.layoutParams = lastBox.layoutParams.also { it.height = (m.heightPixels * 0.3f).toInt() }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun scaleTexts(v: View, scale: Float) {
        if (v is TextView) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, v.textSize * scale)
        if (v is ViewGroup) for (i in 0 until v.childCount) scaleTexts(v.getChildAt(i), scale)
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        scope = s
        // The clock, and the thank-you going back to the welcome after a while.
        val tick = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(TICK_MS)
            }
        }
        s.launch {
            combine(graph.cart.state, graph.staff.state, graph.checkout.last, graph.settings.store, tick) { cart, staff, last, store, now ->
                val view = CustomerView.of(
                    cart.cart.items, cart.priced, cart.lastKey, cart.paying, staff.locked,
                    last?.let { CustomerView.Done(it.total, it.received, it.change, it.at) }, now,
                )
                Triple(view, store, now)
            }.collect { (view, store, now) ->
                try {
                    render(view, store, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("The customer screen could not be drawn", e) // the till sells on whatever the second screen shows
                }
            }
        }
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    private fun text(id: Int, vararg args: Any): String = res.getString(id, *args)

    private fun money(v: Long) = MoneyFormat.format(v, currency)

    private suspend fun render(view: CustomerView, store: StoreSettings, now: Long) {
        if (store.receiptLanguage != language) {
            language = store.receiptLanguage
            res = AppLanguage.inLanguage(context, language).resources
        }
        currency = store.currency
        val name = store.name.ifBlank { text(R.string.app_name) }
        shop.text = name
        clock.text = DateText.time(now, tz)
        welcome.visibility = if (view is CustomerView.Welcome) View.VISIBLE else View.GONE
        thanks.visibility = if (view is CustomerView.Thanks) View.VISIBLE else View.GONE
        bill.visibility = if (view is CustomerView.Bill) View.VISIBLE else View.GONE
        shop.visibility = if (view is CustomerView.Welcome) View.INVISIBLE else View.VISIBLE // the welcome says it large
        shown = view
        when (view) {
            CustomerView.Welcome -> {
                welcomeShop.text = name
                welcomeText.text = text(R.string.cs_welcome)
            }
            is CustomerView.Thanks -> {
                thanksTitle.text = text(R.string.cs_thanks)
                thanksPaid.text = text(R.string.cs_paid, money(view.received))
                thanksChange.text = if (view.change > 0L) text(R.string.cs_change, money(view.change)) else ""
            }
            is CustomerView.Bill -> {
                adapter.submit(view.lines, view.last?.key ?: 0L)
                val at = view.lines.indexOfFirst { it.key == view.last?.key }
                if (at >= 0) lines.scrollToPosition(at)
                discount.visibility = if (view.discount > 0L) View.VISIBLE else View.GONE
                discount.text = text(R.string.cs_discount, money(view.discount))
                totalLabel.text = text(if (view.paying) R.string.cs_total_to_pay else R.string.cs_total)
                total.text = money(view.total)
                count.text = text(R.string.cs_items, view.items)
                showLast(view.last)
            }
        }
    }

    /** The item just added, large: its picture, else its initials on its colour. */
    private suspend fun showLast(line: CustomerLine?) {
        lastName.text = line?.name.orEmpty()
        lastDetail.text = line?.let { detail(it) }.orEmpty()
        val productId = line?.productId ?: Long.MIN_VALUE
        if (productId == lastProduct) return
        lastProduct = productId
        lastPicture.visibility = View.GONE
        lastPicture.setImageDrawable(null)
        lastInitials.text = line?.let { initials(it.name) }.orEmpty()
        val id = line?.productId ?: return paint(TileColor.NONE)
        val (look, colors) = graph.db().read { r ->
            ProductLookDao.get(r, id) to (categoryColors ?: CategoryDao.list(r).associate { it.id to it.color })
        }
        categoryColors = colors
        if (lastProduct != id) return // another item came meanwhile
        paint(TileColor.of(look.color, line.categoryId?.let { colors[it] } ?: TileColor.NONE))
        val imageId = look.imageId ?: return
        val bitmap = graph.pictures.load(imageId) ?: return
        if (lastProduct != id) return
        lastPicture.setImageBitmap(bitmap)
        lastPicture.visibility = View.VISIBLE
    }

    private fun paint(color: Int) {
        lastBox.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(TileColors.argb(color) ?: 0x33FFFFFF)
        }
    }

    /** "RM16.50", or "2 × RM4.20 = RM8.40", "0.535 kg × RM12.90 = RM6.90". */
    private fun detail(l: CustomerLine): String {
        val amount = money(l.amount)
        if (l.fixed || (l.qty == 1000L && !l.weighed)) return amount
        val qty = if (l.weighed) "${MoneyFormat.formatQty(l.qty)} ${l.unit ?: "kg"}" else MoneyFormat.formatQty(l.qty)
        return "$qty × ${money(l.unitPrice)} = $amount"
    }

    private fun initials(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.size == 1 -> words[0].take(2)
            else -> ""
        }.uppercase()
    }

    /** The bill's lines; the one just added on a light yellow. */
    private inner class LinesAdapter : RecyclerView.Adapter<LinesAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.cl_name)
            val detail: TextView = v.findViewById(R.id.cl_detail)
            val amount: TextView = v.findViewById(R.id.cl_amount)
        }

        private var rows: List<CustomerLine> = emptyList()
        private var lastKey = 0L

        @SuppressLint("NotifyDataSetChanged") // a bill of a few lines, redrawn as the cashier scans
        fun submit(next: List<CustomerLine>, last: Long) {
            if (next == rows && last == lastKey) return
            rows = next
            lastKey = last
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.customer_line, parent, false)
            val m = parent.resources.displayMetrics
            scaleTexts(v, minOf(m.widthPixels / m.density / 1280f, m.heightPixels / m.density / 720f).coerceIn(0.5f, 2f))
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val l = rows[position]
            h.name.text = l.name
            val price = money(l.unitPrice)
            h.detail.text = when {
                l.fixed -> ""
                l.weighed -> "${MoneyFormat.formatQty(l.qty)} ${l.unit ?: "kg"} × $price"
                else -> "${MoneyFormat.formatQty(l.qty)} × $price"
            }
            h.detail.visibility = if (l.fixed) View.GONE else View.VISIBLE
            h.amount.text = MoneyFormat.format(l.amount, currency, withSymbol = false)
            h.itemView.setBackgroundColor(if (l.key == lastKey) 0xFFFFF3CF.toInt() else 0)
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}
