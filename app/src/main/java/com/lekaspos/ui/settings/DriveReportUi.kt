package com.lekaspos.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.time.DateText
import com.lekaspos.domain.Approval
import com.lekaspos.domain.report.DailyReportUpload
import com.lekaspos.sync.SyncEngine
import com.lekaspos.sync.SyncProviders
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.util.Log
import java.io.IOException
import java.util.TimeZone

/**
 * Settings → Daily sales report to Google Drive (D-065): what it does, turning it on (Google's consent
 * for the files this app makes, `drive.file`), the last upload, "Upload now", signing in again, and
 * turning it off. Needs the Settings permission (a manager's PIN otherwise). Screens that use it
 * forward their results to [onResult] (Google's consent screen).
 */
object DriveReportUi {

    private const val REQ_CONSENT = 0x4452

    /** A manager's approval, kept while Google's consent screen is open. */
    private var pending: Approval? = null

    @Volatile
    private var uploading = false

    /** The settings row's second line. */
    fun subtitle(ctx: Context, s: DailyReportUpload.Status): String {
        val account = s.account ?: return ctx.getString(R.string.drive_report_sub_off)
        if (s.error == SyncEngine.ERROR_SIGN_IN) return ctx.getString(R.string.drive_report_sub_sign_in)
        val last = s.lastOk?.let { DateText.dateTime(it, TimeZone.getDefault()) } ?: ctx.getString(R.string.drive_report_not_yet)
        return ctx.getString(R.string.drive_report_sub_on, account, last)
    }

    fun open(a: ScreenActivity) = a.withApproval(Perm.SETTINGS) { approval ->
        a.launchUi { show(a, a.graph.dailyReport.load(), approval) }
    }

    private fun show(a: ScreenActivity, s: DailyReportUpload.Status, approval: Approval?) {
        val b = AlertDialog.Builder(a).setTitle(R.string.drive_report_title)
        if (!s.on) {
            b.setMessage(R.string.drive_report_help)
                .setPositiveButton(R.string.drive_report_turn_on) { _, _ -> connect(a, approval, null) }
                .setNegativeButton(R.string.cancel, null)
        } else {
            b.setMessage(listOf(statusText(a, s), a.getString(R.string.drive_report_on_help)).joinToString("\n\n"))
            if (s.error == SyncEngine.ERROR_SIGN_IN) {
                b.setPositiveButton(R.string.drive_report_sign_in_again) { _, _ -> connect(a, approval, s.account) }
            } else {
                b.setPositiveButton(R.string.drive_report_upload_now) { _, _ -> uploadNow(a) }
            }
            b.setNeutralButton(R.string.drive_report_turn_off) { _, _ -> confirmOff(a, approval) }
                .setNegativeButton(R.string.close, null)
        }
        b.show().trackedBy(a)
    }

    private fun statusText(ctx: Context, s: DailyReportUpload.Status): String = listOfNotNull(
        ctx.getString(R.string.drive_report_on, s.account),
        s.lastOk?.let { ctx.getString(R.string.drive_report_last, DateText.dateTime(it, TimeZone.getDefault())) }
            ?: ctx.getString(R.string.drive_report_never),
        s.error?.let { problem(ctx, it) },
    ).joinToString("\n")

    /** A failure ([SyncEngine.errorCode]) in words. */
    private fun problem(ctx: Context, code: String): String = when (code) {
        SyncEngine.ERROR_SIGN_IN -> ctx.getString(R.string.drive_report_sign_in)
        SyncEngine.ERROR_OFFLINE -> ctx.getString(R.string.drive_report_offline)
        SyncEngine.ERROR_DRIVE_FULL -> ctx.getString(R.string.drive_report_drive_full)
        SyncEngine.ERROR_DRIVE_BUSY -> ctx.getString(R.string.sync_state_drive_busy)
        else -> ctx.getString(R.string.drive_report_failed, code)
    }

    /**
     * Asks Google for access; the consent screen (the first time) comes back in [onResult]. The shop's
     * account when it syncs ([account] when signing in again): the report goes to the same Drive.
     */
    private fun connect(a: ScreenActivity, approval: Approval?, account: String?) {
        if (!SyncProviders.available(a)) {
            Dialogs.message(a, a.getString(R.string.drive_report_title), a.getString(R.string.drive_report_no_play))
            return
        }
        pending = approval
        a.launchUi {
            val known = account ?: a.graph.sync.status.value.account
            val access = try {
                SyncProviders.connectReports(a, known)
            } catch (e: IOException) {
                Log.w("Connecting to Google for the daily report failed", e)
                Dialogs.message(a, a.getString(R.string.drive_report_title), problem(a, SyncEngine.errorCode(e)))
                return@launchUi
            }
            onAccess(a, access)
        }
    }

    private fun onAccess(a: ScreenActivity, access: SyncProviders.ReportAccess) {
        when (access) {
            is SyncProviders.ReportAccess.Ready -> turnedOn(a, access.account)
            is SyncProviders.ReportAccess.NeedsUser -> try {
                @Suppress("DEPRECATION")
                a.startIntentSenderForResult(access.intent.intentSender, REQ_CONSENT, null, 0, 0, 0)
            } catch (e: IntentSender.SendIntentException) {
                Log.w("Google's consent screen could not be opened", e)
                Dialogs.message(a, a.getString(R.string.drive_report_title), a.getString(R.string.sync_error_connect, e.message ?: ""))
            }
            is SyncProviders.ReportAccess.Unavailable ->
                Dialogs.message(a, a.getString(R.string.drive_report_title), a.getString(R.string.sync_error_connect, access.message))
        }
    }

    /** Google's consent screen answered; false for another screen's result. */
    fun onResult(a: ScreenActivity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_CONSENT) return false
        if (resultCode != Activity.RESULT_OK) {
            pending = null
            a.toast(R.string.sync_not_granted)
            return true
        }
        a.launchUi {
            // Android may have ended the app meanwhile: who is signed in is loaded before anything is changed.
            a.graph.staff.load()
            a.graph.settings.load()
            val access = try {
                SyncProviders.finishReports(a, data)
            } catch (e: IOException) {
                pending = null
                Log.w("Connecting to Google for the daily report failed", e)
                Dialogs.message(a, a.getString(R.string.drive_report_title), problem(a, SyncEngine.errorCode(e)))
                return@launchUi
            }
            onAccess(a, access)
        }
        return true
    }

    /** On, and the first upload at once: the owner sees the files in Drive straight away. */
    private fun turnedOn(a: ScreenActivity, account: String) {
        val approval = pending
        pending = null
        a.launchUi {
            a.graph.dailyReport.turnOn(account, approval)
            uploadNow(a)
        }
    }

    private fun uploadNow(a: ScreenActivity) {
        if (uploading) return
        uploading = true
        a.toast(R.string.drive_report_uploading)
        a.launchUi {
            try {
                // Leaving Settings does not cut the upload off half-way.
                val names = a.outlivingScreen { a.graph.dailyReport.upload(again = true) }
                if (names.isNotEmpty()) a.toast(a.getString(R.string.drive_report_done, names.joinToString(", ")))
            } catch (e: IOException) {
                Log.w("Daily report upload failed", e)
                Dialogs.message(a, a.getString(R.string.drive_report_title), problem(a, SyncEngine.errorCode(e)))
            } finally {
                uploading = false
            }
        }
    }

    private fun confirmOff(a: ScreenActivity, approval: Approval?) {
        Dialogs.confirm(a, a.getString(R.string.drive_report_title), a.getString(R.string.drive_report_off_confirm), a.getString(R.string.drive_report_turn_off)) {
            a.launchUi { a.graph.dailyReport.turnOff(approval) }
        }
    }
}
