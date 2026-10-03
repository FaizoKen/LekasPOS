package com.lekaspos.ui.common

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.lekaspos.R

/**
 * Small platform AlertDialog helpers (no AppCompat, D-002). A screen that is closing or closed
 * shows nothing: a job that ended after Back (a Drive timeout, a slow approval list, a shift saved
 * while the cashier left) showed its answer on the closed screen and Android ended the app
 * (BadTokenException, 2026-10 review). The dialog is still made and returned, never shown.
 */
object Dialogs {

    /** False for an Activity that is finishing or destroyed (its window is gone). */
    fun canShow(ctx: Context): Boolean {
        val a = ctx as? android.app.Activity ?: return true
        return !a.isFinishing && !a.isDestroyed
    }

    private fun AlertDialog.Builder.showIfOpen(ctx: Context): AlertDialog = create().also { if (canShow(ctx)) it.show() }

    /**
     * [content] in a scroll container, for a dialog's own view: a number pad or PIN pad is taller
     * than a phone in landscape or a window in split screen, and without it the bottom keys — "0",
     * OK — were cut off and could not be reached (2026-10 review).
     */
    fun scrolling(content: View): View = android.widget.ScrollView(content.context).apply {
        isFillViewport = true
        addView(content)
    }

    fun message(ctx: Context, title: CharSequence?, message: CharSequence): AlertDialog =
        AlertDialog.Builder(ctx).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).showIfOpen(ctx).trackedBy(ctx)

    fun confirm(ctx: Context, title: CharSequence, message: CharSequence?, yes: CharSequence, onYes: () -> Unit): AlertDialog =
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .showIfOpen(ctx)
            .trackedBy(ctx)

    /** A text prompt. [onOk] gets the trimmed text; returning false keeps the dialog open. */
    fun input(
        ctx: Context,
        title: CharSequence,
        hint: CharSequence?,
        initial: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        message: CharSequence? = null,
        neutral: Pair<CharSequence, () -> Unit>? = null,
        onOk: (String) -> Boolean,
    ): AlertDialog {
        val field = EditText(ctx).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            this.inputType = inputType
            setSingleLine(inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0)
        }
        val d = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setView(padded(ctx, field))
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .apply { if (neutral != null) setNeutralButton(neutral.first) { _, _ -> neutral.second() } }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onOk(field.text.toString().trim())) d.dismiss()
            }
        }
        if (canShow(ctx)) d.show()
        d.trackedBy(ctx)
        field.requestFocus()
        return d
    }

    fun choose(ctx: Context, title: CharSequence, items: List<CharSequence>, checked: Int = -1, onPick: (Int) -> Unit): AlertDialog {
        val b = AlertDialog.Builder(ctx).setTitle(title)
        if (checked >= 0) {
            b.setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                d.dismiss()
                onPick(which)
            }
        } else {
            b.setItems(items.toTypedArray()) { _, which -> onPick(which) }
        }
        return b.setNegativeButton(R.string.cancel, null).showIfOpen(ctx).trackedBy(ctx)
    }

    /** Wraps [v] with the standard dialog content padding. */
    fun padded(ctx: Context, v: View): View {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        return FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v)
        }
    }
}
