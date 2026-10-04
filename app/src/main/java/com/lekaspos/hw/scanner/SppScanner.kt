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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Bluetooth scanner in SPP (serial) mode: codes arrive as text lines on an RFCOMM socket.
 * Keyboard-mode (HID) scanners need none of this — they type into the selling screen.
 * Runs while a screen that takes scans is in front ([hold]) and reconnects with backoff.
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
            var connectedAt = 0L
            var quickUntil = 0L
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
                    connectedAt = SystemClock.elapsedRealtime()
                    backoff = 2_000L
                    failures = 0
                    read(l)
                } catch (e: IOException) {
                    if (isActive) Log.w("SPP scanner: ${e.message}")
                } catch (e: SecurityException) {
                    Log.w("SPP scanner: ${e.message}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // An odd Bluetooth stack error ended this loop for good: "connecting" until the
                    // selling screen restarted (2026-10 review). Tried again like any lost link.
                    Log.w("SPP scanner failed", e)
                } finally {
                    l.close()
                    link = null
                }
                if (!isActive) break
                _status.value = Status.ERROR
                val now = SystemClock.elapsedRealtime()
                // A scanner that was connected went to sleep or was switched off for a moment: it is
                // likely woken for the next customer soon. It is tried every few seconds for a while
                // first; it waited up to 5 minutes before (2026-10 review).
                if (connectedAt != 0L && now - connectedAt >= UP_MS) {
                    quickUntil = now + QUICK_FOR_MS
                    failures = 0
                    backoff = 2_000L
                }
                if (connectedAt != 0L) droppedAt = now
                connectedAt = 0L
                val quick = now < quickUntil
                withTimeoutOrNull(if (quick) QUICK_MS else backoff) { pokes.receive() } // or the till is used: at once
                // A scanner that is off or asleep: after a few tries, once every 5 minutes. Each try pages
                // for ~15 s (battery, and Bluetooth paging slows the shop's 2.4 GHz Wi-Fi) — every minute
                // all day before (2026-10 review). Opening the selling screen tries again at once.
                if (!quick) backoff = minOf(backoff * 2, if (++failures >= SLOW_AFTER) SLOW_RETRY_MS else 60_000L)
            }
            _status.value = Status.OFF
        }
    }

    /** Screens in front that take its codes (main thread): connected while there is one. */
    private val holders = HashSet<Any>()

    /**
     * [holder], a screen that takes scans, came to the front. A screen opened over the selling screen
     * starts before that one stops: with plain start and stop, a count or a delivery found the scanner
     * switched off under it (2026-10 review). Main thread.
     */
    fun hold(holder: Any) {
        holders.add(holder)
        start()
    }

    /** [holder] left the front; the scanner stops once no screen wants it. Main thread. */
    fun release(holder: Any) {
        if (holders.remove(holder) && holders.isEmpty()) stop()
    }

    private val pokes = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var pokedAt = 0L

    /** When the link last dropped after being up (elapsed realtime), 0 = not since start. */
    @Volatile
    private var droppedAt = 0L

    /**
     * The till is being used (a touch or a key on the selling screen): a scanner that dropped out in
     * the last [POKE_WINDOW_MS] (asleep, likely woken for the next customer) is tried again now instead
     * of after the wait (up to 5 minutes), at most every [POKE_EVERY_MS]. Not one that has been gone
     * longer (off for the day, or switched to keyboard mode): each try pages for ~15 s, and poked by
     * every touch the phone paged almost without a break (2026-10 review).
     */
    fun poke() {
        if (_status.value != Status.ERROR) return
        val now = SystemClock.elapsedRealtime()
        val dropped = droppedAt
        if (dropped == 0L || now - dropped > POKE_WINDOW_MS || now - pokedAt < POKE_EVERY_MS) return
        pokedAt = now
        pokes.trySend(Unit)
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

        /** Connected at least this long, then lost: tried every [QUICK_MS] for [QUICK_FOR_MS]. */
        private const val UP_MS = 30_000L
        private const val QUICK_MS = 5_000L
        private const val QUICK_FOR_MS = 2L * 60L * 1000L
        private const val POKE_EVERY_MS = 60_000L
        private const val POKE_WINDOW_MS = 30L * 60L * 1000L
    }
}
