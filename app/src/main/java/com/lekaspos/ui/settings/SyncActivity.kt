package com.lekaspos.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
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
import java.lang.ref.WeakReference
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → Sync (references/sync.md §10): turn sync on with the store's Google account, see
 * how it is doing, sync now, sign in again, list the store's tills, turn it off. Turning on runs
 * in the app scope, so leaving the screen does not stop the first (possibly long) sync.
 */
class SyncActivity : ScreenActivity() {

    private var tillName: EditText? = null
    private val tz = TimeZone.getDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setScreen(getString(R.string.sync_title))
        guard(Perm.SETTINGS)
    }

    override fun onStarted(scope: CoroutineScope) {
        scope.launch {
            graph.sync.refreshStatus()
            graph.sync.status.collect { build(it) }
        }
    }

    private fun build(s: SyncEngine.Status) {
        val typed = tillName?.text?.toString()
        val f = Form(this)
        if (!s.enabled) {
            f.info(getString(R.string.sync_help))
            if (!SyncProviders.available(this)) {
                f.info(getString(R.string.sync_no_play))
            } else {
                tillName = f.text(getString(R.string.sync_till_name), typed ?: s.deviceName ?: Build.MODEL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                f.info(getString(R.string.sync_same_account))
                if (s.running) {
                    f.info(state(s))
                } else {
                    f.button(getString(R.string.sync_turn_on), primary = true) { turnOn() }
                }
            }
        } else {
            tillName = null
            f.section(getString(R.string.sync_section_status))
            f.info(state(s))
            f.row(getString(R.string.sync_account), s.account ?: "-")
            f.row(getString(R.string.sync_this_till), s.deviceName ?: "-")
            f.row(getString(R.string.sync_last), s.lastSuccessAt?.let { DateText.dateTime(it, tz) } ?: getString(R.string.sync_never))
            f.row(getString(R.string.sync_pending), s.pending.toString())
            if (s.devices > 0) f.row(getString(R.string.sync_other_tills), s.devices.toString())
            if (s.needsSignIn) f.button(getString(R.string.sync_sign_in), primary = true) { signIn() }
            if (!s.running) f.button(getString(R.string.sync_now), primary = !s.needsSignIn) { syncNow() }
            f.button(getString(R.string.sync_show_tills)) { showTills() }
            f.button(getString(R.string.sync_turn_off)) { turnOff() }
            f.info(getString(R.string.sync_help_short))
        }
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun state(s: SyncEngine.Status): String = when {
        s.running && s.phase == SyncEngine.PHASE_PREPARE -> getString(R.string.sync_state_preparing, s.done)
        s.running -> getString(R.string.sync_state_running)
        s.needsSignIn -> getString(R.string.sync_state_sign_in)
        s.lastError != null -> getString(R.string.sync_state_error, s.lastError)
        s.lastSuccessAt != null -> getString(R.string.sync_state_ok)
        else -> getString(R.string.sync_state_never)
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
        launchUi {
            toast(R.string.sync_connecting)
            onConnect(SyncProviders.connect(this@SyncActivity, account))
        }
    }

    private fun onConnect(c: SyncProviders.Connect) {
        when (c) {
            is SyncProviders.Connect.Ready -> connected(c.provider, c.account)
            is SyncProviders.Connect.NeedsUser -> {
                @Suppress("DEPRECATION")
                startIntentSenderForResult(c.intent.intentSender, REQ_AUTH, null, 0, 0, 0)
            }
            is SyncProviders.Connect.Unavailable -> Dialogs.message(this, getString(R.string.sync_title), getString(R.string.sync_error_connect, c.message))
        }
    }

    private fun connected(provider: SyncProvider, account: String?) {
        val name = pendingName
        val known = graph.sync.status.value.account
        if (name == null && account != null && known != null && !account.equals(known, ignoreCase = true)) {
            // Another account has another (empty) app folder: this till would leave the store.
            Dialogs.message(this, getString(R.string.sync_title), getString(R.string.sync_other_account, account, known))
            return
        }
        val graph = graph
        val app = application
        val screen = WeakReference(this) // the first sync may outlive this screen
        graph.appScope.launch {
            val error = try {
                if (name != null) graph.sync.enable(provider, name, account) else graph.sync.sync(provider)
                null
            } catch (e: SyncEngine.Problem) {
                when (e.reason) {
                    SyncEngine.Problem.Reason.DEVICE_CLASH -> app.getString(R.string.sync_error_clash)
                    else -> app.getString(R.string.sync_state_error, e.message)
                }
            } catch (e: Exception) {
                app.getString(R.string.sync_state_error, e.message ?: e.javaClass.simpleName)
            }
            if (name != null) {
                graph.sync.refreshStatus()
                if (error == null) com.lekaspos.app.Work.schedule(app)
            }
            if (error != null) {
                screen.get()?.let { a -> a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) Dialogs.message(a, a.getString(R.string.sync_title), error) } }
            }
        }
    }

    private fun syncNow() {
        launchUi {
            val provider = graph.sync.provider() ?: return@launchUi
            val graph = graph
            graph.appScope.launch {
                try {
                    graph.sync.sync(provider)
                } catch (e: Exception) {
                    // shown in the status line
                }
            }
        }
    }

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
            launchUi { graph.sync.disable() }
        }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUTH) return
        if (resultCode != RESULT_OK) {
            toast(R.string.sync_not_granted)
            return
        }
        launchUi { onConnect(SyncProviders.finish(this@SyncActivity, data)) }
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
    }
}
