package com.lekaspos.hw.scanner

import android.content.Context
import android.os.SystemClock
import com.lekaspos.app.AppGraph
import com.lekaspos.core.scan.ScanBuffer
import com.lekaspos.hw.bt.Bluetooth
import com.lekaspos.hw.bt.SppLink
import com.lekaspos.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A Bluetooth scanner in SPP (serial) mode: codes arrive as text lines on an RFCOMM socket.
 * Keyboard-mode (HID) scanners need none of this — they type into the selling screen.
 * Runs while the selling screen is visible and reconnects with backoff.
 */
class SppScanner(private val app: Context, private val graph: AppGraph) {

    enum class Status { OFF, CONNECTING, CONNECTED, ERROR }

    private val _codes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val codes: SharedFlow<String> = _codes

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "spp-scanner") }.asCoroutineDispatcher()
    private var job: Job? = null

    @Volatile
    private var link: SppLink? = null

    fun start() {
        if (job?.isActive == true) return
        val address = graph.settings.device.value.scannerAddress ?: return
        job = graph.appScope.launch(dispatcher) {
            var backoff = 2_000L
            var failures = 0
            while (isActive) {
                val adapter = Bluetooth.adapter(app)
                if (adapter == null || !Bluetooth.hasPermission(app) || !adapter.isEnabled) {
                    _status.value = Status.ERROR
                    delay(15_000L)
                    continue
                }
                val l = SppLink(adapter, address)
                link = l
                if (!isActive) break // stop() ran before it could see this link
                try {
                    _status.value = Status.CONNECTING
                    l.open() // stop() closes the link, which aborts a connect in progress
                    if (!isActive) throw IOException("stopped")
                    _status.value = Status.CONNECTED
                    backoff = 2_000L
                    failures = 0
                    read(l)
                } catch (e: IOException) {
                    if (isActive) Log.w("SPP scanner: ${e.message}")
                } catch (e: SecurityException) {
                    Log.w("SPP scanner: ${e.message}")
                } finally {
                    l.close()
                    link = null
                }
                if (!isActive) break
                _status.value = Status.ERROR
                delay(backoff)
                // A scanner that is off or asleep: after a few tries, once every 5 minutes. Each try pages
                // for ~15 s (battery, and Bluetooth paging slows the shop's 2.4 GHz Wi-Fi) — every minute
                // all day before (2026-10 review). Opening the selling screen tries again at once.
                backoff = minOf(backoff * 2, if (++failures >= SLOW_AFTER) SLOW_RETRY_MS else 60_000L)
            }
            _status.value = Status.OFF
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Unblocks the reading thread; closing a Bluetooth socket is not for the main thread.
        link?.let { l -> graph.appScope.launch(Dispatchers.IO) { l.close() } }
        _status.value = Status.OFF
    }

    fun restart() {
        stop()
        start()
    }

    /**
     * Delivers each code: at CR or LF, or, for scanners set up without a suffix, once no byte
     * followed for [ScanBuffer.IDLE_MS] (the same rule as for keyboard scanners).
     */
    private fun read(l: SppLink) {
        val input = l.input()
        val buf = ByteArray(256)
        val line = StringBuilder(32)
        while (true) {
            if (line.isNotEmpty() && !moreWithin(input, ScanBuffer.IDLE_MS)) {
                _codes.tryEmit(line.toString())
                line.setLength(0)
            }
            val n = input.read(buf)
            if (n < 0) throw IOException("scanner disconnected")
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                if (b == '\r'.code || b == '\n'.code) {
                    if (line.isNotEmpty()) {
                        _codes.tryEmit(line.toString())
                        line.setLength(0)
                    }
                } else if (b in 0x20..0x7E && line.length < 128) {
                    line.append(b.toChar())
                }
            }
        }
    }

    /** True when a byte arrives within [ms]. Polled: a blocking Bluetooth read cannot time out. */
    private fun moreWithin(input: InputStream, ms: Long): Boolean {
        val until = SystemClock.uptimeMillis() + ms
        while (input.available() == 0) {
            if (SystemClock.uptimeMillis() >= until) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private const val POLL_MS = 20L
        private const val SLOW_AFTER = 5
        private const val SLOW_RETRY_MS = 5L * 60L * 1000L
    }
}
