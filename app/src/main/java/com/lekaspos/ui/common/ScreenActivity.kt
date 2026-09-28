package com.lekaspos.ui.common

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lekaspos.R
import com.lekaspos.app.AppGraph
import com.lekaspos.app.LekasApp
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.ui.Insets
import com.lekaspos.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Base of the secondary screens: a top bar with back arrow, title and actions, a content area,
 * a scope cancelled in onDestroy ([scope]) and one that lives from onStart to onStop
 * ([onStarted]). Activities stay thin: state lives in AppGraph singletons.
 */
abstract class ScreenActivity : Activity(), DialogHost {

    protected val graph: AppGraph get() = LekasApp.graph(this)

    /** Loads and saves started by this screen; cancelled when it is destroyed. */
    protected val scope: CoroutineScope = MainScope()

    private var startedScope: CoroutineScope? = null
    protected lateinit var content: FrameLayout
    private lateinit var actions: LinearLayout
    private lateinit var titleView: TextView
    private val dialogs = DialogTracker()

    override fun track(d: android.app.Dialog) = dialogs.track(d)

    protected fun setScreen(title: CharSequence, layout: Int? = null): View? {
        setContentView(R.layout.screen)
        Insets.apply(findViewById(R.id.root), findViewById(R.id.top_bar))
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        titleView = findViewById(R.id.title)
        titleView.text = title
        content = findViewById(R.id.content)
        actions = findViewById(R.id.actions)
        return layout?.let { layoutInflater.inflate(it, content, true) }
    }

    protected fun setScreenTitle(title: CharSequence) {
        titleView.text = title
    }

    protected fun addAction(icon: Int, description: Int, onClick: () -> Unit): ImageButton {
        val b = layoutInflater.inflate(R.layout.top_action, actions, false) as ImageButton
        b.setImageResource(icon)
        b.contentDescription = getString(description)
        b.setOnClickListener { onClick() }
        actions.addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return b
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        startedScope = s
        onStarted(s)
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        super.onStop()
    }

    /** Collect state flows here; [scope] is cancelled in onStop. */
    protected open fun onStarted(scope: CoroutineScope) {}

    override fun onDestroy() {
        dialogs.dismissAll()
        scope.cancel()
        super.onDestroy()
    }

    protected fun toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    protected fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    /** Runs [block] in [scope]; failures are logged and shown instead of crashing the screen. */
    protected fun launchUi(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Screen action failed", e)
            Dialogs.message(this@ScreenActivity, getString(R.string.error_title), errorText(this@ScreenActivity, e))
        }
    }

    companion object {
        fun errorText(a: Activity, e: Exception): String = when (e) {
            is ActionRefused -> a.getString(
                when (e.reason) {
                    ActionRefused.Reason.NOT_ALLOWED -> R.string.not_allowed
                    ActionRefused.Reason.NOT_FOUND -> R.string.error_not_found
                    ActionRefused.Reason.VOIDED -> R.string.refund_voided
                    ActionRefused.Reason.HAS_REFUNDS -> R.string.refund_has_refunds
                    ActionRefused.Reason.NOT_A_SALE -> R.string.refund_not_sale
                    ActionRefused.Reason.NOTHING_TO_REFUND -> R.string.refund_nothing
                },
            )
            else -> a.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        }
    }
}
