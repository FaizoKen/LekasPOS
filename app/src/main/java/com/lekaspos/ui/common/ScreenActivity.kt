package com.lekaspos.ui.common

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.ui.Insets
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.staff.ApprovalDialog
import com.lekaspos.ui.staff.withApproval
import com.lekaspos.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Base of the secondary screens: a top bar with back arrow, title and actions, a content area,
 * a scope cancelled in onDestroy ([scope]) and one that lives from onStart to onStop
 * ([onStarted]). Activities stay thin: state lives in AppGraph singletons.
 */
abstract class ScreenActivity : Activity(), DialogHost {

    val graph: AppGraph get() = LekasApp.graph(this)

    /** Loads and saves started by this screen; cancelled when it is destroyed. */
    val scope: CoroutineScope = MainScope()

    /** Managers' approvals this screen holds (D-037), released when it closes. */
    private val elevations = ArrayList<Long>(2)
    private var guardPerm = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a rotation the new instance keeps the approvals the old one held.
        savedInstanceState?.getLongArray(STATE_ELEVATIONS)?.forEach { if (graph.permissions.holds(it)) elevations.add(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(STATE_ELEVATIONS, elevations.toLongArray())
    }

    override fun onStart() {
        super.onStart()
        if (graph.staff.state.value.locked) {
            goHome()
            return
        }
        val s = MainScope()
        startedScope = s
        s.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                if (graph.staff.lockIfIdle()) goHome()
            }
        }
        if (guardPerm == 0L || graph.permissions.allowed(guardPerm)) onStarted(s)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        graph.staff.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        graph.staff.touch()
        return super.dispatchKeyEvent(event)
    }

    /** The till is locked: back to the selling screen, which shows the sign-in. */
    private fun goHome() {
        startActivity(Intent(this, SellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /**
     * Runs [block] when the user may do [perm]; otherwise a manager approves it once and this
     * screen keeps the approval until it closes (for screens where one visit = one task).
     */
    fun requireAccess(perm: Long, block: () -> Unit) {
        if (graph.permissions.allowed(perm)) {
            block()
            return
        }
        ApprovalDialog.show(this, graph, scope, perm, onCancel = null) { approval ->
            hold(approval)
            block()
        }
    }

    /**
     * For screens that are useless without [perm] (call in onCreate): asks for a manager's
     * approval at once, or closes the screen. [onStarted] runs only once access is granted.
     */
    protected fun guard(perm: Long) {
        guardPerm = perm
        if (graph.permissions.allowed(perm)) return
        ApprovalDialog.show(this, graph, scope, perm, onCancel = { finish() }) { approval ->
            hold(approval)
            startedScope?.let { onStarted(it) }
        }
    }

    /** A one-time approval for one action (not kept by the screen). */
    fun withApproval(perm: Long, block: (Approval?) -> Unit) = withApproval(graph, scope, perm, block)

    private fun hold(approval: Approval) {
        elevations.add(graph.permissions.elevate(approval))
        graph.appScope.launch { graph.staff.recordApproval(approval) }
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        super.onStop()
    }

    /** Collect state flows here; [scope] is cancelled in onStop. */
    protected open fun onStarted(scope: CoroutineScope) {}

    override fun onDestroy() {
        if (isFinishing) for (t in elevations) graph.permissions.release(t)
        dialogs.dismissAll()
        scope.cancel()
        super.onDestroy()
    }

    fun toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    /** Runs [block] in [scope]; failures are logged and shown instead of crashing the screen. */
    fun launchUi(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
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
        private const val STATE_ELEVATIONS = "lekas.elevations"
        private const val IDLE_CHECK_MS = 15_000L

        fun errorText(a: Activity, e: Exception): String = when (e) {
            is ActionRefused -> a.getString(
                when (e.reason) {
                    ActionRefused.Reason.NOT_ALLOWED -> R.string.not_allowed
                    ActionRefused.Reason.NOT_FOUND -> R.string.error_not_found
                    ActionRefused.Reason.VOIDED -> R.string.refund_voided
                    ActionRefused.Reason.HAS_REFUNDS -> R.string.refund_has_refunds
                    ActionRefused.Reason.NOT_A_SALE -> R.string.refund_not_sale
                    ActionRefused.Reason.NOTHING_TO_REFUND -> R.string.refund_nothing
                    ActionRefused.Reason.NEEDS_SHIFT -> R.string.error_needs_shift
                    ActionRefused.Reason.SHIFT_OPEN -> R.string.error_shift_open
                    ActionRefused.Reason.NEEDS_CUSTOMER -> R.string.error_needs_customer
                    ActionRefused.Reason.OVER_CREDIT_LIMIT -> R.string.error_over_limit
                    ActionRefused.Reason.HAS_BALANCE -> R.string.error_has_balance
                    ActionRefused.Reason.LAST_OWNER -> R.string.error_last_owner
                    ActionRefused.Reason.CREDIT_OFF -> R.string.error_credit_off
                    ActionRefused.Reason.OWNER_PIN_FIRST -> R.string.error_owner_pin_first
                    ActionRefused.Reason.SEED_ROLE -> R.string.error_seed_role
                    ActionRefused.Reason.ROLE_IN_USE -> R.string.error_role_in_use
                    ActionRefused.Reason.WRONG_PIN -> R.string.pin_wrong_plain
                },
            )
            else -> a.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
        }
    }
}
