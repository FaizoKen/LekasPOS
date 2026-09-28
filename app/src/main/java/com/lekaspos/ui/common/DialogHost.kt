package com.lekaspos.ui.common

import android.app.Dialog
import android.content.Context

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

/** Registers [d] with its screen if the screen is a [DialogHost]; returns [d]. */
fun <T : Dialog> T.trackedBy(ctx: Context): T {
    (ctx as? DialogHost)?.track(this)
    return this
}
