package com.lekaspos.perf

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.lekaspos.app.ErrorReports
import com.lekaspos.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-scoped owner of a perf run so it survives rotation and leaving the Diagnostics screen.
 * Results go to the screen, to logcat (tag LekasPerf) and to
 * Android/data/<package>/files/perf/perf-<time>.json|.txt.
 */
class PerfRunner(private val context: Context, private val scope: CoroutineScope) {

    sealed class State {
        object Idle : State()
        data class Running(val step: String, val done: Int, val total: Int) : State()
        data class Done(val report: PerfReport, val file: File?) : State()
        data class Failed(val message: String) : State()
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    /** A run, or the deletion of its data, is in progress (main thread). */
    val isRunning: Boolean get() = job?.isActive == true || deleting

    private var deleting = false

    fun start(scale: PerfScale) {
        if (isRunning) return
        cancelRequested = false
        mutableState.value = State.Running("starting", 0, 0)
        job = scope.launch(Dispatchers.IO) {
            // Keep the CPU running if the screen goes off (power button): without a partial wake
            // lock the device suspends and the run stalls. Bounded so it can never leak.
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LekasPOS:perf").apply { setReferenceCounted(false) }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                val t0 = SystemClock.elapsedRealtime()
                val db = PerfDataGenerator(context, scale).generate(
                    progress = { phase, done, total -> publish(State.Running(phase, done, total)) },
                    cancelled = { cancelRequested },
                )
                val generationMs = SystemClock.elapsedRealtime() - t0
                val report = db.use {
                    PerfSuite(context, it, scale).run(
                        generationMs,
                        progress = { step -> publish(State.Running(step, 0, 0)) },
                        cancelled = { cancelRequested },
                    )
                }
                val file = save(report)
                for (line in report.toText().lines()) android.util.Log.i(LOG_TAG, line)
                android.util.Log.i(LOG_TAG, "DONE ${if (report.passed) "PASS" else "FAIL"} ${file?.absolutePath ?: "-"}")
                // Too slow on this phone: worth knowing (an error report, when the shop allows them — D-057).
                if (!report.passed) ErrorReports.check("Performance test failed (${scale.name})", report.toText())
                mutableState.value = State.Done(report, file)
            } catch (e: PerfDataGenerator.Cancelled) {
                mutableState.value = State.Idle
            } catch (t: Throwable) {
                Log.e("Performance test failed", t)
                android.util.Log.i(LOG_TAG, "DONE ERROR $t")
                mutableState.value = State.Failed(t.toString())
            } finally {
                // The test data (up to ~400 MB at FULL) is made again for every run: it stayed on the
                // shop's phone after the report, out of sight (2026-10 review).
                runCatching { PerfDataGenerator.delete(context) }
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private var lastPublish = 0L

    /** At most ~3 progress updates per second (phase changes always go through) — keeps the UI thread quiet. */
    private fun publish(state: State.Running) {
        val now = SystemClock.elapsedRealtime()
        val current = mutableState.value
        val phaseChanged = current !is State.Running || current.step != state.step
        if (phaseChanged || now - lastPublish >= 300L || state.done == state.total) {
            lastPublish = now
            mutableState.value = state
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    /**
     * Deletes the generated test database (hundreds of MB after a full run: off the main thread,
     * 2026-10 review). Must not be called while a run is active.
     */
    suspend fun deleteData(): Boolean {
        // Checked and marked here, on the main thread: a run started while the files go would lose them.
        check(!isRunning) { "test is running" }
        deleting = true
        mutableState.value = State.Idle
        return try {
            withContext(Dispatchers.IO) { PerfDataGenerator.delete(context) }
        } finally {
            deleting = false
        }
    }

    private fun save(report: PerfReport): File? = try {
        val dir = context.getExternalFilesDir("perf") ?: File(context.filesDir, "perf")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.startedAt))
        File(dir, "perf-$stamp.txt").writeText(report.toText())
        File(dir, "perf-$stamp.json").also { it.writeText(report.toJson()) }
    } catch (e: Exception) {
        Log.w("Could not save the perf report", e)
        null
    }

    companion object {
        const val LOG_TAG = "LekasPerf"
        private const val WAKE_LOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L
    }
}
