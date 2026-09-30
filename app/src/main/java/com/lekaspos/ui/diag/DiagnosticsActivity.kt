package com.lekaspos.ui.diag

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.app.LekasApp
import com.lekaspos.perf.PerfRunner
import com.lekaspos.perf.PerfScale
import com.lekaspos.perf.PerfSuite
import com.lekaspos.ui.Insets
import com.lekaspos.ui.colorOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Diagnostics and the in-app performance test (references/performance.md §2), so testers can
 * measure real low-end devices with the release build. Test data lives in its own database.
 *
 * Scripted runs: `adb shell am start -n <pkg>/com.lekaspos.ui.diag.DiagnosticsActivity --es autorun QUICK`
 * (the activity is exported only to holders of android.permission.DUMP, i.e. adb shell).
 */
class DiagnosticsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val runner: PerfRunner by lazy { LekasApp.graph(this).perfRunner }
    private var startedScope: CoroutineScope? = null

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
        setContentView(R.layout.activity_diagnostics)
        Insets.apply(findViewById(R.id.root), findViewById(R.id.top_bar))
        progress = findViewById(R.id.progress)
        state = findViewById(R.id.state)
        report = findViewById(R.id.report)
        runQuick = findViewById(R.id.run_quick)
        runFull = findViewById(R.id.run_full)
        cancel = findViewById(R.id.cancel)
        delete = findViewById(R.id.delete)
        share = findViewById(R.id.share)

        val info = PerfSuite.deviceInfo(this, "-")
        findViewById<TextView>(R.id.device).text = getString(
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
        }
        cancel.setOnClickListener { runner.cancel() }
        delete.setOnClickListener {
            if (!runner.isRunning) {
                runner.deleteData()
                Toast.makeText(this, R.string.diag_deleted, Toast.LENGTH_SHORT).show()
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

        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_AUTORUN)?.let { name ->
                PerfScale.values().firstOrNull { it.name == name }?.let { runner.start(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val scope = MainScope()
        startedScope = scope
        scope.launch { runner.state.collect { render(it) } }
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        super.onStop()
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
