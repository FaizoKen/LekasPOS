package com.lekaspos.ui.sales

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.data.sale.SaleRow
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.TapOnce
import com.lekaspos.ui.sell.visible
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope

/** One sale or refund: the receipt as printed, plus print copy, share, refund and void. */
class SaleDetailActivity : ScreenActivity() {

    private var saleId = 0L
    private lateinit var receipt: TextView
    private lateinit var refunds: TextView
    private lateinit var print: Button
    private lateinit var share: Button
    private lateinit var refund: Button
    private lateinit var void: Button
    private lateinit var refundVoidRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saleId = intent.getLongExtra(EXTRA_SALE_ID, 0L)
        val v = setScreen(getString(R.string.sale_detail_title), R.layout.activity_sale_detail) ?: return
        receipt = v.findViewById(R.id.receipt)
        refunds = v.findViewById(R.id.refunds)
        print = v.findViewById(R.id.btn_print)
        share = v.findViewById(R.id.btn_share)
        refund = v.findViewById(R.id.btn_refund)
        void = v.findViewById(R.id.btn_void)
        refundVoidRow = v.findViewById(R.id.refund_void_row)
        // One guard per button: Share right after Print is meant.
        val printOnce = TapOnce()
        val shareOnce = TapOnce()
        print.setOnClickListener {
            printOnce.run {
                withApproval(Perm.REPRINT) { approval ->
                    launchUi {
                        graph.sales.print(saleId, copy = true, approval = approval)
                        toast(R.string.sale_print_queued)
                    }
                }
            }
        }
        share.setOnClickListener {
            // A receipt from the history is a copy, whether printed or shared: same permission, same audit entry.
            shareOnce.run {
                withApproval(Perm.REPRINT) { approval ->
                    launchUi {
                        graph.sales.recordShare(saleId, approval)
                        ReceiptShare.chooseAndShare(this@SaleDetailActivity, saleId, copy = true)
                    }
                }
            }
        }
        refund.setOnClickListener { startActivity(RefundActivity.newIntent(this, saleId)) }
        void.setOnClickListener { confirmVoid() }
    }

    override fun onStarted(scope: CoroutineScope) = load()

    private fun load() {
        launchUi {
            val store = graph.settings.store.value
            val tz = TimeZone.getDefault()
            val data = graph.db().read { r ->
                Triple(ReceiptBuilder.build(r, saleId, copy = false, store, tz), SaleQueries.header(r, saleId), SaleQueries.refundsOf(r, saleId))
            }
            val doc = data.first ?: return@launchUi finish()
            val header = data.second ?: return@launchUi finish()
            setScreenTitle(getString(R.string.sale_detail_title_no, doc.receiptNo))
            receipt.text = ReceiptShare.preview(ReceiptBuilder.layout(doc, COLS, store, logo = false, tz))
            refunds.text = refundText(data.third)
            refunds.visible(data.third.isNotEmpty())
            val hasPrinter = graph.settings.device.value.hasPrinter
            // Only what the person signed in may do (D-063): a refund typed in full before a PIN was
            // asked at the very end was the worst dead end a cashier met.
            val p = graph.permissions
            print.visible(hasPrinter && p.shown(Perm.REPRINT))
            share.visible(p.shown(Perm.REPRINT))
            val canRefund = header.kind == SaleKind.SALE && !header.voided && p.shown(Perm.REFUND)
            val canVoid = !header.voided && p.shown(Perm.VOID)
            refund.visible(canRefund)
            void.visible(canVoid)
            val gap = if (canRefund) (6 * resources.displayMetrics.density).toInt() else 0
            void.layoutParams = (void.layoutParams as LinearLayout.LayoutParams).apply { marginStart = gap }
            refundVoidRow.visible(canRefund || canVoid)
        }
    }

    private fun refundText(rows: List<SaleRow>): String {
        val currency = graph.settings.store.value.currency
        return getString(R.string.sale_refunds) + "\n" + rows.joinToString("\n") {
            val state = if (it.status == com.lekaspos.core.model.SaleStatus.VOIDED) " (${getString(R.string.sale_voided)})" else ""
            "${it.receiptNo}  ${MoneyFormat.format(it.total, currency)}$state"
        }
    }

    private fun confirmVoid() {
        Dialogs.input(
            this, getString(R.string.sale_void_title), getString(R.string.reason_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            message = getString(R.string.sale_void_message),
        ) { reason ->
            if (reason.isEmpty()) {
                toast(R.string.reason_required)
                return@input false
            }
            withApproval(Perm.VOID) { approval ->
                launchUi {
                    graph.sales.void(saleId, reason, approval)
                    toast(R.string.sale_voided_done)
                    load()
                }
            }
            true
        }
    }

    companion object {
        private const val EXTRA_SALE_ID = "sale_id"
        private const val COLS = 32

        fun newIntent(ctx: Context, saleId: Long): Intent = Intent(ctx, SaleDetailActivity::class.java).putExtra(EXTRA_SALE_ID, saleId)
    }
}
