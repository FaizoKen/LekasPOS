package com.lekaspos.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.lekaspos.R
import com.lekaspos.util.Log

/**
 * A keyboard-mode barcode scanner (Bluetooth or USB) counts as a physical keyboard, and Android then
 * hides the on-screen keyboard unless "Use on-screen keyboard" is on (it is off by default): tapping
 * a name or search field showed no keyboard, and it came and went as the scanner slept and woke
 * (2026-10 review). The first time a text field gets the focus while that is so, this says why and
 * opens the setting. An app cannot show the keyboard itself.
 */
object KeyboardTip {

    /** Said once per run of the app. */
    @Volatile
    private var shown = false

    /** Watches [a]'s text fields (call in onCreate after the content view is set). */
    fun watch(a: Activity) {
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            // Tapped (touch mode), not focused by the scanner's own keys.
            if (now is EditText && now.isInTouchMode && !shown && hidesKeyboard(a)) {
                shown = true
                show(a)
            }
        }
    }

    /** A physical keyboard is attached and the on-screen one stays hidden while it is. */
    private fun hidesKeyboard(ctx: Context): Boolean {
        val c = ctx.resources.configuration
        if (c.keyboard != Configuration.KEYBOARD_QWERTY || c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_NO) return false
        // "Use on-screen keyboard": on, the keyboard shows anyway. A phone that will not tell (newer
        // Android may refuse hidden settings) gets the tip: once, and harmless.
        return try {
            Settings.Secure.getInt(ctx.contentResolver, SHOW_IME_WITH_HARD_KEYBOARD, 0) == 0
        } catch (e: SecurityException) {
            true
        }
    }

    private fun show(a: Activity) {
        if (!Dialogs.canShow(a)) return
        AlertDialog.Builder(a)
            .setTitle(R.string.keyboard_tip_title)
            .setMessage(R.string.keyboard_tip)
            .setPositiveButton(R.string.keyboard_tip_settings) { _, _ -> openSettings(a) }
            .setNegativeButton(R.string.ok, null)
            .show()
            .trackedBy(a)
    }

    private fun openSettings(a: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                a.startActivity(Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS))
            } else {
                // Android 5–6: the keyboard picker has the "hardware keyboard" switch.
                (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("No keyboard settings screen", e)
            try {
                a.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e2: android.content.ActivityNotFoundException) {
                Log.w("No input settings screen", e2)
            }
        }
    }

    /** Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD (hidden in the SDK; readable by apps). */
    private const val SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"
}
