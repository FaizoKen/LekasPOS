package com.lekaspos.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.lekaspos.BuildConfig
import com.lekaspos.R
import com.lekaspos.app.AppUpdates
import com.lekaspos.app.LekasApp
import com.lekaspos.core.model.Perm
import com.lekaspos.core.time.DateText
import com.lekaspos.core.update.ReleaseNotes
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.staff.withApproval
import com.lekaspos.util.Log
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The app's updates on screen (D-059): the offer ("Update" on the selling screen, or Settings →
 * App updates), the download with its progress, Android 8+'s "install unknown apps" permission, and
 * the hand-over to Android's installer. Updating needs the Settings permission (a manager's PIN
 * otherwise) and no open bill; Android then asks to confirm and keeps all data.
 *
 * Screens that use it forward their results to [onResult] (the permission screen's answer).
 */
object UpdateUi {

    private const val REQ_ALLOW_INSTALLS = 0x5550
    private const val WEBSITE_URL = "https://faizoken.github.io/LekasPOS/#download"
    private const val MB = 1024L * 1024L

    /** "New version: 1.6.0" with what is new and its size: Update now / Later. */
    fun offer(a: Activity, scope: CoroutineScope) {
        val u = LekasApp.graph(a).updates.status.value.update ?: return
        val news = ReleaseNotes.whatsNew(u.notes, language(a))
        val text = buildString {
            if (news.isNotEmpty()) {
                append(a.getString(R.string.update_whats_new))
                for (line in news) append("\n• ").append(line)
                append("\n\n")
            }
            if (u.test) append(a.getString(R.string.update_test_note)).append("\n\n")
            append(a.getString(R.string.update_details, size(a, u.size)))
        }
        AlertDialog.Builder(a)
            .setTitle(a.getString(if (u.test) R.string.update_title_test else R.string.update_title, u.version))
            .setMessage(text)
            .setPositiveButton(R.string.update_now) { _, _ -> start(a, scope) }
            .setNegativeButton(R.string.update_later, null)
            .show()
            .trackedBy(a)
    }

    /** Settings → App updates: this version, the last answer, the two choices, and Check now (or Update now). */
    fun settings(a: Activity, scope: CoroutineScope) {
        val updates = LekasApp.graph(a).updates
        scope.launch {
            guarded(a) {
                val s = updates.load()
                if (!s.selfUpdate) {
                    Dialogs.message(a, a.getString(R.string.update_settings_title), a.getString(R.string.update_sub_play))
                } else {
                    settingsDialog(a, scope, s)
                }
            }
        }
    }

    private fun settingsDialog(a: Activity, scope: CoroutineScope, s: AppUpdates.Status) {
        val updates = LekasApp.graph(a).updates
        val pad = (8 * a.resources.displayMetrics.density).toInt()
        val minTouch = (48 * a.resources.displayMetrics.density).toInt()
        val version = TextView(a).apply {
            text = a.getString(R.string.update_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        val line = TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, pad)
        }
        val auto = CheckBox(a).apply {
            setText(R.string.update_auto)
            isChecked = s.automatic
            minHeight = minTouch
            setOnCheckedChangeListener { _, on -> scope.launch { guarded(a) { updates.setAutomatic(on) } } }
        }
        val tests = CheckBox(a).apply {
            setText(R.string.update_tests)
            isChecked = s.testVersions
            minHeight = minTouch
            // Another list of releases: looked at at once (it waited for the next day's check).
            setOnCheckedChangeListener { _, on ->
                scope.launch {
                    guarded(a) {
                        updates.setTestVersions(on)
                        updates.check()
                    }
                }
            }
        }
        val help = TextView(a).apply {
            setText(R.string.update_settings_help)
            setPadding(0, pad, 0, 0)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(version)
            addView(line)
            addView(auto)
            addView(tests)
            addView(help)
        }
        val d = AlertDialog.Builder(a)
            .setTitle(R.string.update_settings_title)
            .setView(Dialogs.scrolling(Dialogs.padded(a, box)))
            .setPositiveButton(R.string.update_check_now, null)
            .setNegativeButton(R.string.close, null)
            .create()
        fun render(now: AppUpdates.Status) {
            line.text = statusLine(a, now)
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setText(if (offered(now)) R.string.update_now else R.string.update_check_now)
                isEnabled = !now.checking && !now.downloading
            }
        }
        // Follows checks, downloads and the choices while it is open.
        val following = scope.launch(start = CoroutineStart.LAZY) { updates.status.collect { render(it) } }
        d.setOnDismissListener { following.cancel() }
        d.setOnShowListener {
            following.start()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (offered(updates.status.value)) {
                    d.dismiss()
                    offer(a, scope)
                    return@setOnClickListener
                }
                scope.launch {
                    guarded(a) {
                        val after = updates.check()
                        if (offered(after) && d.isShowing) {
                            d.dismiss()
                            offer(a, scope)
                        }
                    }
                }
            }
        }
        d.show()
        d.trackedBy(a)
    }

    /** A newer version that can be installed here (one whose file failed the checks is not offered). */
    private fun offered(s: AppUpdates.Status): Boolean = s.update != null && s.refused == null

    /** The Settings row's line: what is known, and "automatic check off" when it is. */
    fun subtitle(ctx: Context, s: AppUpdates.Status): String? {
        if (!s.loaded) return null
        if (!s.selfUpdate) return ctx.getString(R.string.update_sub_play)
        val u = s.update
        val main = when {
            s.checking -> ctx.getString(R.string.update_sub_checking)
            s.downloading && u != null -> ctx.getString(R.string.update_sub_downloading, u.version, s.progress)
            u != null && s.refused != null -> problemText(ctx, s.refused)
            u != null -> ctx.getString(if (u.test) R.string.update_sub_available_test else R.string.update_sub_available, u.version)
            s.checkedAt > 0L -> ctx.getString(R.string.update_sub_up_to_date, ago(ctx, s.checkedAt))
            else -> ctx.getString(R.string.update_sub_never)
        }
        return if (s.automatic) main else "$main · ${ctx.getString(R.string.update_sub_off)}"
    }

    /** The screen's result (call from onActivityResult): true when it was the install permission's. */
    fun onResult(a: Activity, scope: CoroutineScope, requestCode: Int): Boolean {
        if (requestCode != REQ_ALLOW_INSTALLS) return false
        if (Build.VERSION.SDK_INT >= 26 && a.packageManager.canRequestPackageInstalls()) {
            // Android may have ended the app meanwhile: what was downloaded is read again first.
            scope.launch { guarded(a) { install(a, scope, askPermission = false) } }
        } else {
            Toast.makeText(a, R.string.update_not_allowed, Toast.LENGTH_LONG).show()
        }
        return true
    }

    // ---- the steps ------------------------------------------------------------------------------

    /** "Update now": no open bill, the Settings permission, the file, then Android's installer. */
    private fun start(a: Activity, scope: CoroutineScope) {
        val graph = LekasApp.graph(a)
        if (graph.cart.state.value.cart.items.isNotEmpty()) {
            Dialogs.message(a, null, a.getString(R.string.update_bill_open))
            return
        }
        a.withApproval(graph, scope, Perm.SETTINGS) { _ ->
            scope.launch {
                guarded(a) {
                    if (downloaded(a)) install(a, scope, askPermission = true)
                }
            }
        }
    }

    /** The update's file, downloaded now (with its progress, cancellable) when it is not yet. False: not there. */
    private suspend fun downloaded(a: Activity): Boolean {
        val updates = LekasApp.graph(a).updates
        val s = updates.load()
        val u = s.update ?: return false
        if (s.ready) return true
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(a).apply { text = a.getString(R.string.update_downloading, u.version, size(a, u.size)) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar)
        }
        return coroutineScope {
            val work = async { updates.download() }
            val dialog = AlertDialog.Builder(a)
                .setTitle(R.string.update_downloading_title)
                .setView(Dialogs.padded(a, box))
                .setNegativeButton(R.string.cancel) { _, _ -> work.cancel() }
                .setCancelable(false)
                .show()
                .trackedBy(a)
            val progress = launch { updates.status.collect { bar.progress = it.progress } }
            val after = try {
                work.await()
            } catch (e: CancellationException) {
                ensureActive() // the screen closed: stop here; Cancel was pressed: nothing to say
                null
            } finally {
                progress.cancel()
                if (dialog.isShowing) dialog.dismiss()
            }
            when {
                after == null -> false
                after.ready -> true
                else -> {
                    problem(a, after.problem)
                    false
                }
            }
        }
    }

    /**
     * Hands the file to Android's installer. Android 8+ first needs LekasPOS allowed to install apps
     * ([askPermission]: explained, then its settings screen; the answer comes to [onResult]).
     */
    private suspend fun install(a: Activity, scope: CoroutineScope, askPermission: Boolean) {
        if (askPermission && Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle(R.string.update_allow_title)
                .setMessage(R.string.update_allow_message)
                .setPositiveButton(R.string.update_allow_continue) { _, _ -> openAllowScreen(a, scope) }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .trackedBy(a)
            return
        }
        val graph = LekasApp.graph(a)
        // Checked again: a scan may have started a bill during the PIN, the download or Android's
        // permission screen, and the installer closes the app (2026-10 review).
        if (graph.cart.state.value.cart.items.isNotEmpty()) {
            Dialogs.message(a, null, a.getString(R.string.update_bill_open))
            return
        }
        val updates = graph.updates
        val intent = updates.installIntent()
        if (intent == null) {
            problem(a, updates.status.value.problem)
            return
        }
        try {
            a.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                a.startActivity(updates.viewIntent(intent))
            } catch (e2: ActivityNotFoundException) {
                Log.w("No installer took the update", e2)
                withWebsite(a, a.getString(R.string.update_no_installer))
            }
        }
    }

    @RequiresApi(26)
    private fun openAllowScreen(a: Activity, scope: CoroutineScope) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, REQ_ALLOW_INSTALLS)
        } catch (e: ActivityNotFoundException) {
            // A phone without that screen: Android's installer asks by itself.
            scope.launch { guarded(a) { install(a, scope, askPermission = false) } }
        }
    }

    private fun problem(a: Activity, p: AppUpdates.Problem?) {
        p ?: return
        val text = problemText(a, p)
        if (p == AppUpdates.Problem.WRONG_APP || p == AppUpdates.Problem.NOT_INSTALLABLE) withWebsite(a, text) else Dialogs.message(a, null, text)
    }

    private fun withWebsite(a: Activity, text: String) {
        AlertDialog.Builder(a)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.about_website) { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(a, WEBSITE_URL, Toast.LENGTH_LONG).show()
                }
            }
            .show()
            .trackedBy(a)
    }

    // ---- text -----------------------------------------------------------------------------------

    private fun statusLine(ctx: Context, s: AppUpdates.Status): String {
        val u = s.update
        return when {
            s.checking -> ctx.getString(R.string.update_checking)
            s.problem != null -> problemText(ctx, s.problem)
            u != null && s.refused != null -> problemText(ctx, s.refused)
            u != null -> ctx.getString(if (u.test) R.string.update_sub_available_test else R.string.update_sub_available, u.version)
            s.checkedAt > 0L -> ctx.getString(R.string.update_up_to_date)
            else -> ctx.getString(R.string.update_sub_never)
        }
    }

    private fun problemText(ctx: Context, p: AppUpdates.Problem): String = when (p) {
        AppUpdates.Problem.OFFLINE -> ctx.getString(R.string.update_problem_offline)
        AppUpdates.Problem.SERVER -> ctx.getString(R.string.update_problem_server)
        AppUpdates.Problem.NO_SPACE -> ctx.getString(
            R.string.update_problem_no_space,
            size(ctx, AppUpdates.spaceNeeded(LekasApp.graph(ctx).updates.status.value.update?.size ?: 0L)),
        )
        AppUpdates.Problem.DOWNLOAD -> ctx.getString(R.string.update_problem_download)
        AppUpdates.Problem.DAMAGED -> ctx.getString(R.string.update_problem_damaged)
        AppUpdates.Problem.WRONG_APP -> ctx.getString(R.string.update_problem_wrong_app)
        AppUpdates.Problem.NOT_INSTALLABLE -> ctx.getString(R.string.update_problem_not_installable)
    }

    /** "1.4 MB" (tenths, rounded; at least 0.1). */
    private fun size(ctx: Context, bytes: Long): String {
        val tenths = ((bytes * 10 + MB / 2) / MB).coerceAtLeast(1L)
        return ctx.getString(R.string.update_size_mb, "${tenths / 10}.${tenths % 10}")
    }

    /** "just now", "5 min ago", "3 hours ago", then the date and time (as Settings → Sync). */
    private fun ago(ctx: Context, at: Long): String {
        val mins = (System.currentTimeMillis() - at) / 60_000L
        return when {
            mins < 1L -> ctx.getString(R.string.sync_ago_now)
            mins < 60L -> ctx.resources.getQuantityString(R.plurals.sync_ago_minutes, mins.toInt(), mins.toInt())
            mins < 24L * 60L -> ctx.resources.getQuantityString(R.plurals.sync_ago_hours, (mins / 60L).toInt(), (mins / 60L).toInt())
            else -> DateText.dateTime(at, TimeZone.getDefault())
        }
    }

    /** The screen's language ("ms" or "en"), for the release notes. */
    private fun language(ctx: Context): String {
        val locale = if (Build.VERSION.SDK_INT >= 24) {
            ctx.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            ctx.resources.configuration.locale
        }
        return if (locale?.language == "ms") "ms" else "en"
    }

    /** A failure here is logged and shown; it never closes the screen. */
    private suspend fun guarded(a: Activity, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("App update step failed", e)
            if (!a.isFinishing && !a.isDestroyed) {
                Dialogs.message(a, a.getString(R.string.error_title), a.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
