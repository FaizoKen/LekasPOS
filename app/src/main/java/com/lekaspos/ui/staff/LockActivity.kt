package com.lekaspos.ui.staff

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.data.staff.Staff
import com.lekaspos.domain.StaffSession
import com.lekaspos.ui.Insets
import com.lekaspos.ui.colorOf
import com.lekaspos.ui.common.DialogHost
import com.lekaspos.ui.common.DialogTracker
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.PinPad
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.sideways
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.visible
import com.lekaspos.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign-in with a PIN (D-037): pick your name, type your PIN. Shown over the selling screen
 * whenever PIN login is on and nobody is signed in; Back leaves the app instead of the lock.
 * Too many wrong PINs make this till wait; the owner can reset a forgotten PIN with the
 * recovery code.
 */
class LockActivity : Activity(), DialogHost {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val graph get() = LekasApp.graph(this)
    private val scope = MainScope()
    private val dialogs = DialogTracker()
    private var backCallback: Any? = null

    private lateinit var listPanel: View
    private lateinit var pinPanel: View
    private lateinit var nameView: TextView
    private lateinit var changeButton: Button
    private lateinit var forgotButton: Button
    private lateinit var pinPad: PinPad
    private val adapter = RowAdapter<Staff>(
        bind = { h, s -> h.set(s.name, s.roleName) },
        onClick = { select(it) },
    )
    private var staff: List<Staff> = emptyList()
    private var chosen: Staff? = null
    private var countdown: Job? = null

    override fun track(d: android.app.Dialog) = dialogs.track(d)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            id = R.id.root
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorOf(R.color.bg))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorOf(R.color.brand))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = graph.settings.store.value.name.ifBlank { getString(R.string.app_name) }
            textSize = 20f
            setTextColor(colorOf(R.color.text_on_brand))
        })
        bar.addView(TextView(this).apply {
            setText(R.string.lock_title)
            setTextColor(colorOf(R.color.text_on_brand))
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCol.addView(TextView(this, null, 0, R.style.Text_Lekas_Section).apply {
            setText(R.string.lock_who)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        listCol.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        listPanel = listCol
        body.addView(listCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val pinCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameView = TextView(this, null, 0, R.style.Text_Lekas_Display)
        nameRow.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        changeButton = Button(this, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
            setText(R.string.lock_change_user)
            setOnClickListener {
                // Someone else signs in: a rebuilt screen (turned, screen off) must not go back to the last
                // person's PIN pad, where this person's PIN would count as a wrong one of theirs.
                graph.staff.forgetLastSignedIn()
                showList()
            }
        }
        nameRow.addView(changeButton)
        pinPad = PinPad(this) { submit(it) }
        forgotButton = Button(this, null, 0, R.style.Widget_Lekas_Button_Secondary).apply {
            setText(R.string.lock_forgot)
            setOnClickListener { forgot() }
        }
        val matchWrap = { top: Int -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top } }
        val sideways = sideways()
        if (sideways) {
            // Held sideways: who and the dots on the left, the keys on the right — under each other the
            // keys fell below a phone's short height (D-063).
            pinCol.orientation = LinearLayout.HORIZONTAL
            pinCol.isBaselineAligned = false
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(nameRow)
            left.addView(pinPad.display, matchWrap(dp(8)))
            left.addView(forgotButton, matchWrap(dp(12)))
            pinCol.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pinCol.addView(pinPad.keypad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        } else {
            pinCol.addView(nameRow)
            pinCol.addView(pinPad.view, matchWrap(dp(8)))
            pinCol.addView(forgotButton, matchWrap(dp(12)))
        }
        // Keep the pad a sensible size on tablets and in landscape.
        val center = FrameLayout(this)
        val width = dp(if (sideways) 760 else 420).coerceAtMost(resources.displayMetrics.widthPixels)
        center.addView(pinCol, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(center)
        }
        pinPanel = scroll
        body.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        Insets.apply(root, bar)
        pinPanel.visible(false)
        registerBack()
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    private fun reload() {
        scope.launch {
            try {
                graph.staff.load()
                if (!graph.staff.state.value.locked) {
                    finish()
                    return@launch
                }
                staff = graph.staffAdmin.staff().filter { it.canSignIn }
                adapter.submit(staff)
                // The person chosen before, else the one signed in before the lock (D-063): one tap less after an idle lock.
                val id = chosen?.id ?: graph.staff.lastSignedIn
                val c = staff.firstOrNull { it.id == id }
                when {
                    c != null -> select(c)
                    staff.size == 1 -> select(staff[0])
                    else -> showList()
                }
            } catch (e: CancellationException) {
                throw e // rotated or closed while loading: nothing to show (it crashed on the old screen)
            } catch (e: Exception) {
                Log.e("Loading staff failed", e)
                Dialogs.message(this@LockActivity, getString(R.string.error_title), getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    override fun onDestroy() {
        unregisterBack()
        dialogs.dismissAll()
        scope.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) leave()
            return true
        }
        if (pinPanel.visibility == View.VISIBLE && pinPad.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun select(s: Staff) {
        chosen = s
        nameView.text = s.name
        changeButton.visible(staff.size > 1)
        forgotButton.visible(s.isOwner)
        pinPad.clear()
        // The wait after wrong PINs belongs to one person: another may sign in meanwhile.
        if (countdown?.isActive == true) {
            countdown?.cancel()
            pinPad.setEnabled(true)
        }
        pinPad.setMessage(null)
        listPanel.visible(false)
        pinPanel.visible(true)
        scope.launch {
            val wait = try {
                graph.staff.waitMs(s.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Reading the PIN wait failed", e)
                0L
            }
            if (wait > 0L && chosen?.id == s.id) startCountdown(wait)
        }
    }

    private fun showList() {
        chosen = null
        pinPanel.visible(false)
        listPanel.visible(true)
    }

    private fun submit(pin: String) {
        val s = chosen ?: return
        pinPad.setEnabled(false)
        scope.launch {
            val c = try {
                graph.staff.signIn(s.id, pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Said as it is (a full phone, say): "This person cannot sign in" sent the owner looking
                // for a fault in their PIN (2026-10 review).
                Log.e("Sign-in failed", e)
                pinPad.setEnabled(true)
                pinPad.setMessage(ScreenActivity.errorText(this@LockActivity, e))
                return@launch
            }
            pinPad.setEnabled(true)
            when (c) {
                is StaffSession.Check.Ok -> finish()
                is StaffSession.Check.WrongPin -> {
                    pinPad.setMessage(checkMessage(this@LockActivity, c))
                    if (c.waitMs > 0L) startCountdown(c.waitMs)
                }
                is StaffSession.Check.Wait -> startCountdown(c.ms)
                StaffSession.Check.NotAllowed -> {
                    pinPad.setMessage(checkMessage(this@LockActivity, c))
                    reload()
                }
            }
        }
    }

    private fun startCountdown(ms: Long) {
        countdown?.cancel()
        countdown = scope.launch {
            val until = System.currentTimeMillis() + ms
            pinPad.setEnabled(false)
            while (true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0L) break
                pinPad.setMessage(getString(R.string.pin_wait, waitText(left)))
                delay(500L)
            }
            pinPad.setEnabled(true)
            pinPad.setMessage(null)
        }
    }

    /** Forgotten owner PIN: the recovery code, then a new PIN. */
    private fun forgot() {
        val owner = chosen?.takeIf { it.isOwner } ?: return
        val field = EditText(this).apply {
            hint = getString(R.string.recovery_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.lock_forgot)
            .setMessage(R.string.recovery_enter)
            .setView(Dialogs.padded(this, field))
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .trackedBy(this)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = field.text.toString()
            if (code.isBlank()) return@setOnClickListener
            d.dismiss()
            askNewPin(this, getString(R.string.pin_new_title, owner.name)) { pin ->
                scope.launch {
                    val ok = try {
                        graph.staffAdmin.recover(owner.id, code, pin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Not "That recovery code is not right": the owner thought the code was lost (2026-10 review).
                        Log.e("Recovery failed", e)
                        Dialogs.message(this@LockActivity, null, ScreenActivity.errorText(this@LockActivity, e))
                        return@launch
                    }
                    if (ok) {
                        finish()
                    } else {
                        Dialogs.message(this@LockActivity, null, getString(R.string.recovery_wrong))
                    }
                }
            }
        }
    }

    /** Back never reveals the till: the app goes to the background instead. */
    private fun leave() {
        moveTaskToBack(true)
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) backCallback = Back.register(this) { leave() }
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { Back.unregister(this, it) }
        backCallback = null
    }

    private object Back {
        @androidx.annotation.RequiresApi(33)
        fun register(a: Activity, onBack: () -> Unit): Any {
            val cb = OnBackInvokedCallback { onBack() }
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            return cb
        }

        @androidx.annotation.RequiresApi(33)
        fun unregister(a: Activity, cb: Any) {
            a.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb as OnBackInvokedCallback)
        }
    }
}
