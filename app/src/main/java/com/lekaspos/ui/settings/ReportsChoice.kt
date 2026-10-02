package com.lekaspos.ui.settings

import android.app.Activity
import android.app.AlertDialog
import com.lekaspos.R
import com.lekaspos.app.ErrorReports
import com.lekaspos.app.LekasApp
import com.lekaspos.ui.common.trackedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The shop's one question about error reports (D-057): asked once on the selling screen (to
 * someone who may change settings) and again from Settings → Error reports. Saved for this phone.
 */
object ReportsChoice {

    /** [onChosen] gets the answer at once; it is saved in the background. */
    fun ask(activity: Activity, onChosen: ((Boolean) -> Unit)? = null) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.error_reports_ask_title)
            .setMessage(R.string.error_reports_ask_message)
            .setPositiveButton(R.string.error_reports_yes) { _, _ -> save(activity, true, onChosen) }
            .setNegativeButton(R.string.error_reports_no) { _, _ -> save(activity, false, onChosen) }
            .setCancelable(false) // an answer either way; asked only once
            .show()
            .trackedBy(activity)
    }

    private fun save(activity: Activity, on: Boolean, onChosen: ((Boolean) -> Unit)?) {
        val app = activity.applicationContext
        LekasApp.graph(app).appScope.launch(Dispatchers.IO) { ErrorReports.setConsent(app, on) } // outlives the screen
        onChosen?.invoke(on)
    }
}
