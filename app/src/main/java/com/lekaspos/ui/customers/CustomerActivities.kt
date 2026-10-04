package com.lekaspos.ui.customers

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.credit.CreditMath
import com.lekaspos.core.model.CreditKind
import com.lekaspos.core.model.PaymentKind
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.core.time.DateText
import com.lekaspos.data.catalog.PaymentMethodDao
import com.lekaspos.data.customer.Customer
import com.lekaspos.data.customer.CustomerItem
import com.lekaspos.domain.customer.CustomerService
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.onNearEnd
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.AmountDialog
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Customers (D-039): search by name or phone, who owes what. In pick mode (from the selling
 * screen) a tap returns the customer instead of opening it.
 */
class CustomersActivity : ScreenActivity() {

    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private var pick = false

    private val adapter = RowAdapter<CustomerItem>(
        bind = { h, c ->
            val currency = graph.settings.store.value.currency
            h.set(
                c.name, c.phone, if (c.balance != 0L) MoneyFormat.format(c.balance, currency) else null,
                if (c.removed) getString(R.string.customer_removed_tag) else null,
            )
        },
        onClick = { c ->
            if (pick) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, c.id).putExtra(EXTRA_NAME, c.name))
                finish()
            } else {
                startActivity(CustomerActivity.intent(this, c.id))
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        val v = setScreen(getString(if (pick) R.string.customer_pick else R.string.customers_title), R.layout.list_with_search) ?: return
        addAction(R.drawable.ic_add, R.string.customer_add) {
            editCustomer(this, null) { saved ->
                if (pick) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, saved.id).putExtra(EXTRA_NAME, saved.name))
                    finish()
                } else {
                    reload(false)
                }
            }
        }
        search = v.findViewById(R.id.list_search)
        search.setHint(R.string.customer_search_hint)
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
    }

    override fun onStarted(scope: CoroutineScope) {
        reload(debounce = false)
        if (!pick) {
            launchUi {
                val (count, total) = graph.customers.debtors()
                setScreenTitle(
                    if (count > 0L) {
                        getString(R.string.customers_owing, count, MoneyFormat.format(total, graph.settings.store.value.currency))
                    } else {
                        getString(R.string.customers_title)
                    },
                )
            }
        }
    }

    private fun reload(debounce: Boolean) {
        job?.cancel()
        val q = search.text.toString()
        job = launchUi {
            if (debounce) delay(200L)
            // Removed customers still owing come first (to settle), never when picking one for a bill.
            val items = graph.customers.page(q, null, withRemoved = !pick)
            adapter.submit(items)
            end = items.size < PAGE
            empty.setText(if (q.isBlank()) R.string.customers_empty else R.string.customers_none_found)
            empty.visible(items.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        val q = search.text.toString()
        job = launchUi {
            val more = graph.customers.page(q, after)
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    companion object {
        const val EXTRA_ID = "customer_id"
        const val EXTRA_NAME = "customer_name"
        private const val EXTRA_PICK = "pick"
        private const val PAGE = 50

        fun pickIntent(ctx: Context): Intent = Intent(ctx, CustomersActivity::class.java).putExtra(EXTRA_PICK, true)
    }
}

/** One customer: details, balance, statement; take a repayment or adjust the balance. */
class CustomerActivity : ScreenActivity() {

    private var customerId = 0L
    private var customer: Customer? = null
    private var balance = 0L

    /** Deleted (here or on another till): only the balance can be settled (2026-10 review). */
    private var removed = false
    private lateinit var header: TextView
    private lateinit var empty: TextView
    private var job: Job? = null
    private var end = false
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<CustomerService.StatementRow>(
        bind = { h, row ->
            val currency = graph.settings.store.value.currency
            val e = row.entry
            val what = getString(
                when {
                    e.kind == CreditKind.PAYMENT -> R.string.credit_payment
                    e.kind == CreditKind.ADJUST -> R.string.credit_adjust
                    e.amount < 0L -> R.string.credit_reversal
                    else -> R.string.credit_charge
                },
            )
            val sub = listOfNotNull(DateText.dateTime(e.at, tz), e.receiptNo, e.note).joinToString(" · ")
            val amount = (if (e.delta > 0L) "+" else "") + MoneyFormat.format(e.delta, currency)
            h.set(what, sub, amount, getString(R.string.credit_balance_after, MoneyFormat.format(row.balanceAfter, currency)))
        },
        onClick = {},
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customerId = intent.getLongExtra(CustomersActivity.EXTRA_ID, 0L)
        val v = setScreen(getString(R.string.customers_title), R.layout.list_header) ?: return
        header = v.findViewById(R.id.list_header)
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.credit_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.onNearEnd { loadMore() }
        header.setOnClickListener { actions() }
        addAction(R.drawable.ic_more, R.string.sell_menu) { actions() }
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        job?.cancel()
        job = launchUi {
            val loaded = graph.customers.get(customerId) ?: return@launchUi finish()
            customer = loaded.first
            balance = loaded.second
            removed = graph.customers.isRemoved(customerId)
            val c = loaded.first
            val currency = graph.settings.store.value.currency
            setScreenTitle(c.name)
            val lines = ArrayList<String>(4)
            if (removed) lines.add(getString(R.string.customer_removed_line))
            lines.add(getString(R.string.customer_owes, MoneyFormat.format(balance, currency)))
            if (c.creditLimit > 0L) {
                lines.add(getString(R.string.customer_limit_line, MoneyFormat.format(c.creditLimit, currency), MoneyFormat.format(CreditMath.available(balance, c.creditLimit) ?: 0L, currency)))
            }
            listOfNotNull(c.phone, c.email, c.address).takeIf { it.isNotEmpty() }?.let { lines.add(it.joinToString(" · ")) }
            header.text = lines.joinToString("\n")
            val rows = graph.customers.statement(customerId, null, PAGE)
            adapter.submit(rows)
            end = rows.size < PAGE
            empty.visible(rows.isEmpty())
        }
    }

    private fun loadMore() {
        if (end || job?.isActive == true) return
        val after = adapter.items.lastOrNull() ?: return
        job = launchUi {
            val more = graph.customers.statement(customerId, after, PAGE)
            adapter.append(more)
            end = more.size < PAGE
        }
    }

    private fun actions() {
        val c = customer ?: return
        // Adjusting a balance only for whoever may (D-063): for a cashier it led to a manager's PIN.
        val adjust = graph.permissions.allowed(Perm.CREDIT_LIMIT)
        val items = ArrayList<Int>(4)
        items += R.string.credit_receive
        if (adjust) items += R.string.credit_adjust_title
        if (!removed) items += listOf(R.string.customer_edit, R.string.delete) // a removed customer: settle only
        Dialogs.choose(this, c.name, items.map { getString(it) }) { i ->
            when (items[i]) {
                R.string.credit_receive -> receive(c)
                R.string.credit_adjust_title -> adjust(c)
                R.string.customer_edit -> editCustomer(this, c) { reload() }
                R.string.delete -> delete(c)
            }
        }
    }

    private fun receive(c: Customer) {
        withApproval(Perm.CUSTOMERS) { approval ->
            val currency = graph.settings.store.value.currency
            AmountDialog(this, getString(R.string.credit_receive), AmountDialog.Kind.MONEY, currency, initial = balance.coerceAtLeast(0L),
                message = getString(R.string.customer_owes, MoneyFormat.format(balance, currency))) { amount ->
                launchUi {
                    val methods = graph.db().read { PaymentMethodDao.active(it) }.filter { it.kind != PaymentKind.CREDIT }
                    val take = { i: Int ->
                        launchUi {
                            val left = graph.customers.receivePayment(
                                c.id, amount, methods[i], null, approval, getString(R.string.credit_cash_rounding),
                            )
                            toast(getString(R.string.customer_owes, MoneyFormat.format(left, currency)))
                            reload()
                        }
                        Unit
                    }
                    // One way to pay (cash only): no question with one answer (D-063).
                    if (methods.size == 1) take(0) else Dialogs.choose(this@CustomerActivity, getString(R.string.credit_paid_with), methods.map { it.name }, onPick = take)
                }
            }.show()
        }
    }

    private fun adjust(c: Customer) {
        withApproval(Perm.CREDIT_LIMIT) { approval ->
            val currency = graph.settings.store.value.currency
            val ways = listOf(getString(R.string.credit_adjust_more), getString(R.string.credit_adjust_less))
            Dialogs.choose(this, getString(R.string.credit_adjust_title), ways) { way ->
                AmountDialog(this, ways[way], AmountDialog.Kind.MONEY, currency) { amount ->
                    Dialogs.input(this, getString(R.string.credit_adjust_title), getString(R.string.shift_reason_required)) { reason ->
                        if (reason.isEmpty()) return@input false
                        launchUi {
                            graph.customers.adjust(c.id, if (way == 0) amount else -amount, reason, approval)
                            reload()
                        }
                        true
                    }
                }.show()
            }
        }
    }

    private fun delete(c: Customer) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.customer_delete_confirm, c.name), getString(R.string.delete)) {
            withApproval(Perm.CUSTOMERS) { approval ->
                launchUi {
                    graph.customers.delete(c.id, approval)
                    finish()
                }
            }
        }
    }

    companion object {
        private const val PAGE = 50

        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, CustomerActivity::class.java).putExtra(CustomersActivity.EXTRA_ID, id)
    }
}

/** Add ([existing] = null) or edit a customer (needs CUSTOMERS or a manager's approval). */
fun editCustomer(a: ScreenActivity, existing: Customer?, onSaved: (Customer) -> Unit) {
    a.withApproval(Perm.CUSTOMERS) { approval -> showCustomerForm(a, existing, approval, onSaved) }
}

private fun showCustomerForm(a: ScreenActivity, existing: Customer?, approval: com.lekaspos.domain.Approval?, onSaved: (Customer) -> Unit) {
    val currency = a.graph.settings.store.value.currency
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
    val name = field(R.string.customer_name, existing?.name, words)
    val phone = field(R.string.store_phone, existing?.phone, InputType.TYPE_CLASS_PHONE)
    val email = field(R.string.store_email, existing?.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    val address = field(R.string.store_address, existing?.address, words or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    val tin = field(R.string.customer_tin, existing?.tin, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
    // The credit limit only for whoever may set it (D-063): for a cashier the field only led to a PIN.
    val mayLimit = a.graph.permissions.allowed(Perm.CREDIT_LIMIT)
    val limit = if (!mayLimit) {
        null
    } else {
        field(
            R.string.customer_limit, existing?.creditLimit?.takeIf { it > 0L }?.let { MoneyFormat.format(it, currency, withSymbol = false) },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
    }
    val note = field(R.string.inv_note_hint, existing?.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    if (mayLimit) col.addView(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply { setText(R.string.customer_limit_hint) })
    val d = AlertDialog.Builder(a)
        .setTitle(if (existing == null) R.string.customer_add else R.string.customer_edit)
        .setView(ScrollView(a).apply { addView(col) })
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
        .show()
        .trackedBy(a)
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val n = name.text.toString().trim()
        if (n.isEmpty()) {
            name.error = a.getString(R.string.product_error_name)
            return@setOnClickListener
        }
        val limitText = limit?.text?.toString()?.trim()
        val limitValue = when {
            limit == null -> existing?.creditLimit ?: 0L // not shown: kept as it is
            limitText.isNullOrEmpty() -> 0L
            else -> MoneyFormat.parse(limitText, currency)
        }
        if (limitValue == null || limitValue < 0L) {
            limit?.error = a.getString(R.string.customer_limit_error)
            return@setOnClickListener
        }
        fun t(e: EditText) = e.text.toString().trim().ifEmpty { null }
        val c = Customer(existing?.id ?: 0L, n, t(phone), t(email), t(address), t(tin), t(note), limitValue)
        val save = { limitApproval: com.lekaspos.domain.Approval? ->
            d.dismiss()
            a.launchUi { onSaved(a.graph.customers.save(existing, c, approval, limitApproval)) }
            Unit
        }
        // A new or changed credit limit needs CREDIT_LIMIT: the form stays open if the manager says no.
        if (limitValue != (existing?.creditLimit ?: 0L)) a.withApproval(Perm.CREDIT_LIMIT) { save(it) } else save(null)
    }
}

/** Starts the customer picker from [a]; the result arrives in onActivityResult with [requestCode]. */
@Suppress("DEPRECATION")
fun pickCustomer(a: Activity, requestCode: Int) = a.startActivityForResult(CustomersActivity.pickIntent(a), requestCode)
