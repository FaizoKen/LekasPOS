package com.lekaspos.ui.diag

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.perf.PerfRunner
import com.lekaspos.perf.PerfScale
import com.lekaspos.perf.PerfSuite
import com.lekaspos.ui.colorOf
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Diagnostics and the in-app performance test (references/performance.md §2), so testers can
 * measure real low-end devices with the release build. Test data lives in its own database.
 *
 * Needs the Settings permission like the other back-office screens, and follows the lock: a full
 * run fills the phone's storage with test data and keeps the till busy for a long time, and any
 * cashier could start it from the menu (2026-10 review).
 *
 * Scripted runs: `adb shell am start -n <pkg>/com.lekaspos.ui.diag.DiagnosticsActivity --es autorun QUICK`
 * (the activity is exported only to holders of android.permission.DUMP, i.e. adb shell). The run
 * starts once the screen may run: on a till with staff PINs, after a manager's approval.
 */
class DiagnosticsActivity : ScreenActivity() {

    private val runner: PerfRunner by lazy { graph.perfRunner }

    /** A scripted run asked for by the intent, started in [onStarted] (once). */
    private var autorun: String? = null

    private lateinit var progress: ProgressBar
    private lateinit var state: TextView
    private lateinit var report: TextView
    private lateinit var runQuick: Button
    private lateinit var runFull: Button
    private lateinit var cancel: Button
    private lateinit var delete: Button
    private lateinit var share: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.diag_title), R.layout.activity_diagnostics) ?: return
        progress = v.findViewById(R.id.progress)
        state = v.findViewById(R.id.state)
        report = v.findViewById(R.id.report)
        runQuick = v.findViewById(R.id.run_quick)
        runFull = v.findViewById(R.id.run_full)
        cancel = v.findViewById(R.id.cancel)
        delete = v.findViewById(R.id.delete)
        share = v.findViewById(R.id.share)
        // Nothing works before the permission is checked ([render] enables what fits the state).
        for (b in listOf(runQuick, runFull, delete, share)) b.isEnabled = false

        val info = PerfSuite.deviceInfo(this, "-")
        v.findViewById<TextView>(R.id.device).text = getString(
            R.string.diag_device_info, info.manufacturer, info.model, info.release, info.sdkInt, info.totalRamMb,
            info.memoryClassMb, info.appVersion,
        )

        runQuick.setOnClickListener { runner.start(PerfScale.QUICK) }
        runFull.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.diag_confirm_full_title)
                .setMessage(R.string.diag_confirm_full_message)
                .setPositiveButton(R.string.diag_start) { _, _ -> runner.start(PerfScale.FULL) }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .trackedBy(this)
        }
        cancel.setOnClickListener { runner.cancel() }
        delete.setOnClickListener {
            if (!runner.isRunning) {
                launchUi {
                    runner.deleteData() // a large file: deleted off the main thread
                    toast(R.string.diag_deleted)
                }
            }
        }
        share.setOnClickListener {
            val done = runner.state.value as? PerfRunner.State.Done ?: return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "LekasPOS performance report")
                .putExtra(Intent.EXTRA_TEXT, done.report.toText())
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
        }

        if (savedInstanceState == null) autorun = intent.getStringExtra(EXTRA_AUTORUN)
        guard(Perm.SETTINGS)
    }

    override fun onStarted(scope: CoroutineScope) {
        autorun?.let { name ->
            autorun = null
            PerfScale.values().firstOrNull { it.name == name }?.let { runner.start(it) }
        }
        scope.launch { runner.state.collect { render(it) } }
    }

    private fun render(s: PerfRunner.State) {
        val running = s is PerfRunner.State.Running
        runQuick.isEnabled = !running
        runFull.isEnabled = !running
        delete.isEnabled = !running
        cancel.visibility = if (running) View.VISIBLE else View.GONE
        share.isEnabled = s is PerfRunner.State.Done
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (s) {
            PerfRunner.State.Idle -> {
                state.setText(R.string.diag_idle)
                report.text = ""
            }
            is PerfRunner.State.Running -> {
                if (s.total > 0) {
                    progress.isIndeterminate = false
                    progress.max = s.total
                    progress.progress = s.done
                    state.text = getString(R.string.diag_running_progress, s.step, s.done, s.total)
                } else {
                    progress.isIndeterminate = true
                    state.text = getString(R.string.diag_running, s.step)
                }
            }
            is PerfRunner.State.Done -> {
                val verdict = getString(if (s.report.passed) R.string.diag_passed else R.string.diag_failed)
                state.text = if (s.file != null) verdict + "\n" + getString(R.string.diag_saved_to, s.file.absolutePath) else verdict
                state.setTextColor(colorOf(if (s.report.passed) R.color.ok else R.color.danger))
                report.text = s.report.toText()
            }
            is PerfRunner.State.Failed -> {
                state.text = getString(R.string.diag_error, s.message)
                state.setTextColor(colorOf(R.color.danger))
            }
        }
        if (s !is PerfRunner.State.Done && s !is PerfRunner.State.Failed) state.setTextColor(colorOf(R.color.text_primary))
    }

    companion object {
        const val EXTRA_AUTORUN = "autorun"
    }
}
