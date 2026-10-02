package com.lekaspos.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.time.DateText
import com.lekaspos.data.db.Meta
import com.lekaspos.sync.SyncEngine
import com.lekaspos.sync.SyncProvider
import com.lekaspos.sync.SyncProviders
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.visible
import java.lang.ref.WeakReference
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Sync (references/sync.md §10): turn sync on with the store's Google account, see
 * how it is doing, sync now, sign in again, list the store's tills, turn it off. Turning on runs
 * in the app scope, so leaving the screen does not stop the first (possibly long) sync.
 */
class SyncActivity : ScreenActivity() {

    private var tillName: EditText? = null
    private val tz = TimeZone.getDefault()

    /** The layout the form was built for ([render]); null = not built yet. */
    private var builtFor: String? = null
    private var stateText: TextView? = null
    private var turnOnButton: Button? = null
    private var accountValue: TextView? = null
    private var tillValue: TextView? = null
    private var lastValue: TextView? = null
    private var pendingValue: TextView? = null
    private var devicesValue: TextView? = null
    private var syncNowButton: Button? = null

    /** Google's answer waiting until this screen may run (see [onActivityResult]). */
    private var pendingAuth: Pair<Int, Intent?>? = null
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen(getString(R.string.sync_title))
        guard(Perm.SETTINGS)
    }

    override fun onStarted(scope: CoroutineScope) {
        entered = true
        pendingAuth?.let { (code, data) ->
            pendingAuth = null
            onAuthResult(code, data)
        }
        scope.launch {
            graph.sync.refreshStatus()
            val s = graph.sync.status.value
            if (s.enabled && !s.running && s.pending > 0L) graph.autoSync.now() // changes waiting: send them while the owner looks
            graph.sync.status.collect { render(it) }
        }
        scope.launch {
            // "2 min ago" keeps counting while the screen is open.
            while (true) {
                delay(AGO_REFRESH_MS)
                val s = graph.sync.status.value
                if (s.enabled) render(s)
            }
        }
    }

    override fun onStop() {
        entered = false
        super.onStop()
    }

    /**
     * Shows [s]. The form is built again only when its layout changes (sync turned on or off, a
     * sign-in needed); otherwise its texts change in place. A long first sync reports progress
     * many times a second, and building the whole screen each time lost taps on its buttons and
     * scrolled it back to the top (2026-10 review).
     */
    private fun render(s: SyncEngine.Status) {
        keepScreenOn(s.running) // the first sync of a large shop takes minutes: the phone must not sleep (2026-10 review)
        val layout = when {
            !s.enabled -> LAYOUT_OFF
            s.needsSignIn -> LAYOUT_SIGN_IN
            else -> LAYOUT_ON
        }
        if (layout != builtFor) build(s, layout)
        update(s)
    }

    private fun build(s: SyncEngine.Status, layout: String) {
        val typed = tillName?.text?.toString()
        tillName = null
        stateText = null
        turnOnButton = null
        accountValue = null
        tillValue = null
        lastValue = null
        pendingValue = null
        devicesValue = null
        syncNowButton = null
        val f = Form(this)
        if (!s.enabled) {
            f.info(getString(R.string.sync_help))
            if (!SyncProviders.available(this)) {
                f.info(getString(R.string.sync_no_play))
            } else {
                tillName = f.text(getString(R.string.sync_till_name), typed ?: s.deviceName ?: Build.MODEL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                f.info(getString(R.string.sync_same_account))
                stateText = f.info("")
                turnOnButton = f.button(getString(R.string.sync_turn_on), primary = true) { turnOn() }
            }
        } else {
            f.section(getString(R.string.sync_section_status))
            stateText = f.info("")
            accountValue = f.valueRow(getString(R.string.sync_account), "")
            tillValue = f.valueRow(getString(R.string.sync_this_till), "")
            lastValue = f.valueRow(getString(R.string.sync_last), "")
            pendingValue = f.valueRow(getString(R.string.sync_pending), "")
            devicesValue = f.valueRow(getString(R.string.sync_other_tills), "")
            if (s.needsSignIn) f.button(getString(R.string.sync_sign_in), primary = true) { signIn() }
            syncNowButton = f.button(getString(R.string.sync_now), primary = !s.needsSignIn) { syncNow() }
            f.button(getString(R.string.sync_show_tills)) { showTills() }
            f.button(getString(R.string.sync_turn_off)) { turnOff() }
            f.info(getString(R.string.sync_help_short))
        }
        builtFor = layout
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun update(s: SyncEngine.Status) {
        if (!s.enabled) {
            // While turning on: what it is doing instead of the button.
            stateText?.let {
                it.text = if (s.running) state(s) else ""
                it.visible(s.running)
            }
            turnOnButton?.visible(!s.running)
            return
        }
        stateText?.text = state(s)
        accountValue?.text = s.account ?: "-"
        tillValue?.text = s.deviceName ?: "-"
        lastValue?.text = s.lastSuccessAt?.let { ago(it) } ?: getString(R.string.sync_never)
        pendingValue?.text = s.pending.toString()
        devicesValue?.let {
            it.text = s.devices.toString()
            (it.parent as? View)?.visible(s.devices > 0)
        }
        syncNowButton?.visible(!s.running)
    }

    /** What sync is doing right now, step by step, so it never looks stuck (D-053). */
    private fun state(s: SyncEngine.Status): String = when {
        s.running -> when (s.phase) {
            SyncEngine.PHASE_CONNECT -> getString(R.string.sync_connecting)
            SyncEngine.PHASE_PREPARE -> getString(R.string.sync_state_preparing, s.done)
            SyncEngine.PHASE_SEND -> getString(R.string.sync_state_sending, s.done + 1L, s.total).takeIf { s.total > 0L && s.done < s.total }
                ?: getString(R.string.sync_state_checking)
            SyncEngine.PHASE_RECEIVE -> getString(R.string.sync_state_receiving, s.done + 1L, s.total).takeIf { s.total > 0L && s.done < s.total }
                ?: getString(R.string.sync_state_checking)
            SyncEngine.PHASE_FINISH -> getString(R.string.sync_state_finishing)
            else -> getString(R.string.sync_state_running)
        }
        s.needsSignIn -> getString(R.string.sync_state_sign_in)
        s.lastError == SyncEngine.ERROR_OFFLINE -> getString(R.string.sync_state_offline)
        s.lastError == SyncEngine.ERROR_CORRUPT -> getString(R.string.sync_state_corrupt)
        s.lastError != null -> getString(R.string.sync_state_error, s.lastError)
        // A wrong date puts this till's changes out of order with the others' (2026-10 review).
        s.clockOff -> getString(R.string.sync_clock_wrong)
        s.pending > 0L -> resources.getQuantityString(R.plurals.sync_state_waiting, s.pending.toInt(), s.pending.toInt())
        s.lastSuccessAt != null -> getString(R.string.sync_state_ok)
        else -> getString(R.string.sync_state_never)
    }

    /** "just now", "5 min ago", "3 hours ago", then the date and time. */
    private fun ago(at: Long): String {
        val mins = (System.currentTimeMillis() - at) / 60_000L
        return when {
            mins < 1L -> getString(R.string.sync_ago_now)
            mins < 60L -> resources.getQuantityString(R.plurals.sync_ago_minutes, mins.toInt(), mins.toInt())
            mins < 24L * 60L -> resources.getQuantityString(R.plurals.sync_ago_hours, (mins / 60L).toInt(), (mins / 60L).toInt())
            else -> DateText.dateTime(at, tz)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun turnOn() {
        val field = tillName ?: return
        val name = field.text.toString().trim()
        if (name.isEmpty()) {
            field.error = getString(R.string.sync_till_name_needed)
            field.requestFocus()
            return
        }
        pendingName = name
        connect(null)
    }

    private fun signIn() {
        pendingName = null
        connect(graph.sync.status.value.account)
    }

    /** Asks Google for access; the consent screen (first time, revoked access) comes back in [onActivityResult]. */
    private fun connect(account: String?) {
        graph.sync.starting() // "Connecting to Google…" at once, and no second tap on the button
        launchUi {
            try {
                onConnect(SyncProviders.connect(this@SyncActivity, account))
            } catch (e: Exception) {
                graph.sync.notStarted()
                throw e
            }
        }
    }

    private fun onConnect(c: SyncProviders.Connect) {
        when (c) {
            is SyncProviders.Connect.Ready -> connected(c.provider, c.account)
            is SyncProviders.Connect.NeedsUser -> {
                @Suppress("DEPRECATION")
                startIntentSenderForResult(c.intent.intentSender, REQ_AUTH, null, 0, 0, 0)
            }
            is SyncProviders.Connect.Unavailable -> {
                graph.sync.notStarted()
                Dialogs.message(this, getString(R.string.sync_title), getString(R.string.sync_error_connect, c.message))
            }
        }
    }

    private fun connected(provider: SyncProvider, account: String?) {
        val name = pendingName
        val known = graph.sync.status.value.account
        if (name == null && account != null && known != null && !account.equals(known, ignoreCase = true)) {
            // Another account has another (empty) app folder: this till would leave the store.
            graph.sync.notStarted()
            Dialogs.message(this, getString(R.string.sync_title), getString(R.string.sync_other_account, account, known))
            return
        }
        val graph = graph
        val app = application
        val screen = WeakReference(this) // the first sync may outlive this screen
        graph.appScope.launch {
            val failure: Exception? = try {
                if (name != null) graph.sync.enable(provider, name, account) else graph.sync.sync(provider)
                null
            } catch (e: Exception) {
                e
            }
            if (name != null) {
                graph.sync.refreshStatus()
                if (failure == null) com.lekaspos.app.Work.schedule(app)
            }
            if (failure != null) {
                screen.get()?.let { a ->
                    a.runOnUiThread {
                        if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                        // Worded with the screen, in the language it shows: the app's own context keeps the
                        // language the app started in (2026-10 review).
                        val error = failureText(a, failure)
                        if (failure is SyncEngine.Problem && failure.reason == SyncEngine.Problem.Reason.OLD_COPY) {
                            // The new till number is taken at the next start (also without this restart).
                            Dialogs.confirm(a, a.getString(R.string.sync_title), error, a.getString(R.string.backup_restart_now)) { graph.backups.restart(a) }
                        } else {
                            Dialogs.message(a, a.getString(R.string.sync_title), error)
                        }
                    }
                }
            }
        }
    }

    /** At once: the status turns to "Connecting to Google…" before anything else happens. */
    private fun syncNow() = graph.autoSync.now()

    private fun showTills() {
        launchUi {
            val provider = graph.sync.provider() ?: return@launchUi
            val store = graph.db().read { Meta.get(it, Meta.STORE_UUID).orEmpty() }
            val me = graph.db().deviceNo
            val cards = graph.sync.cards(provider, store).sortedByDescending { it.lastSeen }
            val text = if (cards.isEmpty()) {
                getString(R.string.sync_tills_empty)
            } else {
                cards.joinToString("\n\n") { c ->
                    val name = c.name.ifBlank { "#${c.dev}" } + if (c.dev == me) " " + getString(R.string.sync_till_me) else ""
                    getString(R.string.sync_till_row, name, DateText.dateTime(c.lastSeen, tz), c.app)
                }
            }
            Dialogs.message(this@SyncActivity, getString(R.string.sync_show_tills), text)
        }
    }

    private fun turnOff() {
        Dialogs.confirm(this, getString(R.string.sync_turn_off), getString(R.string.sync_turn_off_confirm), getString(R.string.sync_turn_off)) {
            // In the app scope: it waits for a running round, and leaving this screen meanwhile must not
            // cancel it (the till kept syncing although "Turn off" was confirmed, 2026-10 review).
            launchUi { graph.appScope.async { graph.sync.disable() }.await() }
        }
    }

    /**
     * Google's consent screen answered. When Android ended the app meanwhile, the answer arrives
     * before the signed-in staff member is loaded and before a manager approved this screen again:
     * it waits for [onStarted] (2026-10 review).
     */
    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUTH) return
        if (entered) onAuthResult(resultCode, data) else pendingAuth = resultCode to data
    }

    private fun onAuthResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            graph.sync.notStarted()
            toast(R.string.sync_not_granted)
            return
        }
        launchUi {
            try {
                onConnect(SyncProviders.finish(this@SyncActivity, data))
            } catch (e: Exception) {
                graph.sync.notStarted()
                throw e
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAME, pendingName)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pendingName = savedInstanceState.getString(STATE_NAME)
    }

    /** The till name being turned on with (kept across the consent screen). */
    private var pendingName: String? = null

    private companion object {
        const val REQ_AUTH = 0x5359
        const val STATE_NAME = "lekas.sync.name"
        const val AGO_REFRESH_MS = 30_000L
        const val LAYOUT_OFF = "off"
        const val LAYOUT_ON = "on"
        const val LAYOUT_SIGN_IN = "sign-in"

        /** Here, not in the screen: the first sync's coroutine must not hold on to the screen. */
        fun failureText(ctx: Context, e: Exception): String = when ((e as? SyncEngine.Problem)?.reason) {
            SyncEngine.Problem.Reason.DEVICE_CLASH -> ctx.getString(R.string.sync_error_clash)
            SyncEngine.Problem.Reason.OLD_COPY -> ctx.getString(R.string.sync_old_copy)
            else -> when (SyncEngine.errorCode(e)) {
                SyncEngine.ERROR_OFFLINE -> ctx.getString(R.string.sync_state_offline)
                SyncEngine.ERROR_CORRUPT -> ctx.getString(R.string.sync_state_corrupt)
                SyncEngine.ERROR_SIGN_IN -> ctx.getString(R.string.sync_state_sign_in)
                else -> ctx.getString(R.string.sync_state_error, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
