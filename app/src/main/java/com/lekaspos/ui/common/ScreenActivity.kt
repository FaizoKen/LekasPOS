package com.lekaspos.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.domain.Approval
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.ui.Insets
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.staff.ApprovalDialog
import com.lekaspos.ui.staff.withApproval
import com.lekaspos.util.Log
import com.lekaspos.util.Storage
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

/**
 * Base of the secondary screens: a top bar with back arrow, title and actions, a content area,
 * a scope cancelled in onDestroy ([scope]) and one that lives from onStart to onStop
 * ([onStarted]). Activities stay thin: state lives in AppGraph singletons.
 */
abstract class ScreenActivity : Activity(), DialogHost {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    val graph: AppGraph get() = LekasApp.graph(this)

    /**
     * A job of this screen that fails outside [launchUi] (a list's page read on a damaged or full
     * database, say) is logged and shown; it crashed the app (2026-10 review), like SellActivity's.
     */
    private val failures = CoroutineExceptionHandler { _, e ->
        if (e is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
        if (Storage.isFull(e)) Log.w("Screen job failed: the storage is full", e) else Log.e("Screen job failed", e)
        runOnUiThread {
            if (Dialogs.canShow(this)) {
                val text = if (e is Exception) errorText(this, e) else getString(R.string.error_generic, e.javaClass.simpleName)
                Dialogs.message(this, getString(R.string.error_title), text)
            }
        }
    }

    /** Loads and saves started by this screen; cancelled when it is destroyed. */
    val scope: CoroutineScope = MainScope() + failures

    /** Managers' approvals this screen holds (D-037), released when it closes. */
    private val elevations = ArrayList<Long>(2)
    private var guardPerm = 0L
    private var guardAsked = false
    private var destroying = false

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
        val s = MainScope() + failures
        startedScope = s
        if (graph.staff.screenStarted()) {
            // Idle too long while out of sight: the till locked; the selling screen shows the sign-in.
            goHome()
            return
        }
        // Who is signed in and the store's settings are loaded first. After Android has killed the
        // app in the background it restores only this screen, not the selling screen that used
        // to load them: until they are loaded nothing here may run (2026-10 review: a restored
        // screen ran with every permission and no lock). Immediate: no delay when already loaded.
        s.launch(Dispatchers.Main.immediate) {
            try {
                whenLoaded()
            } catch (e: Exception) {
                // The database cannot be read (full disk, damage): the selling screen says why,
                // instead of this screen taking the app down at every start (2026-10 review).
                Log.e("Opening a screen failed", e)
                goHome()
                return@launch
            }
            if (graph.staff.state.value.locked) {
                goHome()
                return@launch
            }
            launch {
                while (true) {
                    delay(IDLE_CHECK_MS)
                    if (graph.staff.lockIfIdle()) goHome()
                }
            }
            // Locked for any other reason (the signed-in person was removed or lost their PIN on
            // another till, and sync brought it in): this screen kept running (2026-10 review).
            launch {
                graph.staff.state.first { it.locked }
                goHome()
            }
            enter(s)
        }
    }

    /** Runs [onStarted] when the user may use this screen; otherwise asks for a manager's approval once. */
    private fun enter(s: CoroutineScope) {
        if (guardPerm == 0L || graph.permissions.allowed(guardPerm)) {
            onStarted(s)
            return
        }
        if (guardAsked) return // the approval dialog is already open
        guardAsked = true
        ApprovalDialog.show(this, graph, scope, guardPerm, onCancel = { if (!destroying) finish() }) { approval ->
            hold(approval)
            startedScope?.let { onStarted(it) }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (graph.staff.activity()) {
            goHome() // idle too long: this touch was meant for whoever was signed in before
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (graph.staff.activity()) {
            goHome()
            return true
        }
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
        guardPerm = perm // checked in onStart, once the signed-in staff member is known
    }

    /**
     * Starts a system picker (file, folder, picture) for a result. Some phones and POS terminals
     * have none (no Files app, a locked-down build): that closed the app (2026-10 review). Returns
     * false, after saying so, when nothing can open it.
     */
    fun startPicker(intent: Intent, request: Int): Boolean = try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, request)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        Dialogs.message(this, null, getString(R.string.no_picker))
        false
    } catch (e: SecurityException) {
        Log.w("The picker could not be opened", e)
        Dialogs.message(this, null, getString(R.string.no_picker))
        false
    }

    private var pendingExport: (suspend (Appendable) -> Long)? = null

    /**
     * Exports a CSV that [write] produces (returning its row count): shared (WhatsApp, e-mail …)
     * or saved where the user picks (D-041).
     */
    fun exportCsv(fileName: String, write: suspend (Appendable) -> Long) {
        Dialogs.choose(this, getString(R.string.export_title), listOf(getString(R.string.export_share), getString(R.string.export_save))) { i ->
            if (i == 0) {
                launchUi {
                    val file = withContext(Dispatchers.IO) {
                        CsvFiles.shareFile(this@ScreenActivity, fileName).also { f -> CsvFiles.writer(f).use { write(it) } }
                    }
                    CsvFiles.share(this@ScreenActivity, file)
                }
            } else {
                pendingExport = write
                if (!startPicker(CsvFiles.createDocumentIntent(fileName), REQ_EXPORT)) pendingExport = null
            }
        }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val write = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (write == null) {
            // Android ended the app while the file picker was open: the export is gone, and so
            // must be the empty file the picker created.
            toast(R.string.export_lost)
            val resolver = contentResolver
            graph.appScope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            return
        }
        saveExport(uri, write)
    }

    /**
     * Writes an export into the file the user picked. It runs on after this screen closes (a
     * year of receipts takes a while), and a failed export deletes its unfinished file: half a
     * receipt list must never look like a whole one (2026-10 review).
     */
    private fun saveExport(uri: Uri, write: suspend (Appendable) -> Long) {
        val app = applicationContext
        val screen = WeakReference(this)
        graph.appScope.launch(Dispatchers.Main) {
            val n = try {
                withContext(Dispatchers.IO) { CsvFiles.writer(app, uri).use { write(it) } }
            } catch (e: Exception) {
                Log.e("Export failed", e)
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
                val a = screen.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                if (a != null) {
                    Dialogs.message(a, a.getString(R.string.error_title), a.getString(R.string.export_failed, errorText(a, e)))
                } else {
                    Toast.makeText(app, app.getString(R.string.export_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            Toast.makeText(app, app.getString(R.string.export_saved, n), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Waits until who is signed in and the settings are loaded. Results of other screens (file and
     * folder pickers) arrive before [onStart]'s loading has run when Android restarted the app
     * meanwhile: a permission check then saw "nothing allowed", and a picked backup folder was
     * lost (2026-10 review).
     */
    protected suspend fun whenLoaded() {
        graph.staff.load()
        graph.settings.load()
    }

    /** A one-time approval for one action (not kept by the screen). */
    fun withApproval(perm: Long, block: (Approval?) -> Unit) = withApproval(graph, scope, perm, block)

    private fun hold(approval: Approval) {
        elevations.add(graph.permissions.elevate(approval))
        graph.appScope.launch { graph.staff.recordApproval(approval) }
    }

    override fun onResume() {
        super.onResume()
        // A phone that only paused this screen while it was off wakes it with onResume alone.
        if (graph.staff.lockIfIdle()) goHome()
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        graph.staff.screenStopped()
        super.onStop()
    }

    /** Collect state flows here; [scope] is cancelled in onStop. */
    protected open fun onStarted(scope: CoroutineScope) {}

    override fun onDestroy() {
        if (isFinishing) for (t in elevations) graph.permissions.release(t)
        destroying = true // dialogs closed from here on were not cancelled by the user
        dialogs.dismissAll()
        scope.cancel()
        super.onDestroy()
    }

    /** Keeps the phone's screen (and so its CPU) on while a long job of this screen runs. */
    fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
            // A refusal is the rule working (not allowed, over the limit ...), not a bug: no error report (D-057).
            // A picked file that is no good backup, or a full phone: not the app's bug either.
            when {
                e is ActionRefused -> Log.w("Screen action refused: ${e.reason}")
                e is com.lekaspos.data.backup.BackupFiles.Invalid || Storage.isFull(e) -> Log.w("Screen action failed", e)
                else -> Log.e("Screen action failed", e)
            }
            Dialogs.message(this@ScreenActivity, getString(R.string.error_title), errorText(this@ScreenActivity, e))
        }
    }

    companion object {
        private const val STATE_ELEVATIONS = "lekas.elevations"
        private const val IDLE_CHECK_MS = 15_000L
        private const val REQ_EXPORT = 0x4C45

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
                    ActionRefused.Reason.MORE_THAN_OWED -> R.string.error_more_than_owed
                    ActionRefused.Reason.OWNER_ONLY -> R.string.error_owner_only
                },
            )
            else -> if (Storage.isFull(e)) {
                a.getString(R.string.error_storage_full)
            } else {
                a.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
