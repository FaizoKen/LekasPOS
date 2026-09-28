package com.lekaspos.ui.sales

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.SaleKind
import com.lekaspos.core.money.MoneyFormat
import com.lekaspos.data.sale.SaleQueries
import com.lekaspos.data.sale.SaleRow
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.ScreenActivity
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
        print.setOnClickListener {
            launchUi {
                graph.sales.print(saleId, copy = true)
                toast(R.string.sale_print_queued)
            }
        }
        share.setOnClickListener { ReceiptShare.chooseAndShare(this, saleId) }
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
            print.visible(hasPrinter)
            refund.visible(header.kind == SaleKind.SALE && !header.voided)
            void.visible(!header.voided)
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
            launchUi {
                graph.sales.void(saleId, reason)
                toast(R.string.sale_voided_done)
                load()
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
