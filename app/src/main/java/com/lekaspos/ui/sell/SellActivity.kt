package com.lekaspos.ui.sell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.app.LekasApp
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.Schema
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.sale.ReceiptNumbers
import com.lekaspos.data.sale.SaleDao
import com.lekaspos.ui.Insets
import com.lekaspos.ui.diag.DiagnosticsActivity
import com.lekaspos.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Launcher and home screen. Phase 1: opens the database off the main thread, shows its state
 * and reports "fully drawn" for cold-start measurement. Phase 2 turns it into the selling screen.
 */
class SellActivity : Activity() {

    private var startedScope: CoroutineScope? = null
    private var reportedDrawn = false
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sell)
        Insets.apply(findViewById(R.id.root), findViewById(R.id.top_bar))
        status = findViewById(R.id.status)
        findViewById<View>(R.id.open_diagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = MainScope()
        startedScope = scope
        scope.launch {
            try {
                val db = LekasApp.graph(this@SellActivity).db()
                val info = db.read { r ->
                    Info(
                        prefix = Meta.get(r, Meta.RECEIPT_PREFIX) ?: ReceiptNumbers.defaultPrefix(db.deviceNo),
                        products = ProductDao.count(r),
                        sales = SaleDao.count(r),
                    )
                }
                status.text = getString(R.string.sell_status_ready, info.prefix.trimEnd('-'), info.products, info.sales, Schema.VERSION)
            } catch (e: Exception) {
                Log.e("Database open failed", e)
                status.text = getString(R.string.sell_status_error, e.message ?: e.javaClass.simpleName)
            }
            if (!reportedDrawn) {
                reportedDrawn = true
                reportFullyDrawn()
            }
        }
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        super.onStop()
    }

    private class Info(val prefix: String, val products: Long, val sales: Long)
}
