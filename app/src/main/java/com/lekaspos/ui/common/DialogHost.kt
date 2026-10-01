package com.lekaspos.ui.common

import android.app.Dialog
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.widget.EditText
import com.lekaspos.app.LekasApp
import java.util.WeakHashMap

/**
 * A screen that closes its open dialogs when it is destroyed (rotation, back), so no window
 * leaks and no dismiss listener is skipped. Dialog helpers register themselves via [track].
 */
interface DialogHost {
    fun track(d: Dialog)
}

class DialogTracker : DialogHost {
    private val dialogs = ArrayList<Dialog>()

    override fun track(d: Dialog) {
        dialogs.removeAll { !it.isShowing }
        dialogs.add(d)
    }

    fun dismissAll() {
        for (d in ArrayList(dialogs)) if (d.isShowing) d.dismiss()
        dialogs.clear()
    }
}

/**
 * Hardware keys in dialogs. A keyboard-wedge barcode scanner "types" its digits and ends with
 * Enter. The first key takes the window out of touch mode, Android then gives keyboard focus to
 * the dialog's first button, and an Enter that nobody consumed presses it — a scan with the
 * payment dialog open completed the sale as exact cash (2026-10 review). So every dialog
 * swallows the keys that press a focused view; keypads also drop scanner-speed digits.
 */
object DialogKeys {
    /** Keys closer together than this come from a scanner, not a person. */
    const val BURST_GAP_MS = 35L
    const val BURST_IDLE_MS = 300L

    /** Keys that press (or move to) whatever view has keyboard focus. */
    fun pressesFocused(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB,
        -> true
        else -> false
    }

    /** Dialogs with their own key handler ([keys]); the others get the default guard in [trackedBy]. */
    internal val handled = WeakHashMap<Dialog, Boolean>()
}

/** Sets this dialog's handler for hardware keys (keypad, PIN pad, scans passed on to the screen). */
fun <T : Dialog> T.keys(handler: (KeyEvent) -> Boolean): T {
    DialogKeys.handled[this] = true
    setOnKeyListener { _, _, e -> handler(e) }
    return this
}

/**
 * Registers [this] with its screen if the screen is a [DialogHost]; returns it. A dialog without
 * its own key handler never lets Enter, Tab or Space press a button (text fields keep them).
 * Taps and keys in the dialog count as activity for the idle auto-lock: a dialog is a window of
 * its own, so the screen never saw them and the till locked under someone typing a name or
 * reading a restore question (2026-10 review).
 */
fun <T : Dialog> T.trackedBy(ctx: Context): T {
    (ctx as? DialogHost)?.track(this)
    if (DialogKeys.handled[this] != true) {
        setOnKeyListener { d, _, e -> DialogKeys.pressesFocused(e.keyCode) && (d as? Dialog)?.currentFocus !is EditText }
    }
    val w = window
    val callback = w?.callback
    if (w != null && callback != null && callback !is ActivityCallback) {
        val staff = LekasApp.graph(ctx).staff
        w.callback = ActivityCallback(callback) { staff.touch() }
    }
    return this
}

/** Passes everything to the dialog ([inner]); touches and keys also call [onUse] first. */
private class ActivityCallback(
    private val inner: Window.Callback,
    private val onUse: () -> Unit,
) : Window.Callback by inner {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onUse()
        return inner.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onUse()
        return inner.dispatchKeyEvent(event)
    }
}
