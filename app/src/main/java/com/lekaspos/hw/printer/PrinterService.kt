package com.lekaspos.hw.printer

import android.content.Context
import com.lekaspos.app.AppGraph
import com.lekaspos.core.escpos.MonoImage
import com.lekaspos.core.escpos.ReceiptEncoder
import com.lekaspos.core.model.PrintJobKind
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.core.shift.ShiftReportLayout
import com.lekaspos.core.shift.ShiftText
import com.lekaspos.data.print.PrintJob
import com.lekaspos.data.print.PrintJobDao
import com.lekaspos.data.settings.DeviceSettings
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.hw.bt.Bluetooth
import com.lekaspos.hw.bt.SppLink
import com.lekaspos.util.Log
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The receipt printer (D-026). One dedicated thread takes jobs from the persistent `print_job`
 * queue in order, keeps a Bluetooth SPP connection to the configured printer, reconnects with
 * backoff when it drops, and marks a job done only after its bytes were written. Selling never
 * waits for it: sales commit first, printing follows.
 */
class PrinterService(private val app: Context, private val graph: AppGraph) {

    sealed class Status {
        object NotConfigured : Status()
        object NoBluetooth : Status()
        object NoPermission : Status()
        object BluetoothOff : Status()
        object Idle : Status()
        object Connecting : Status()
        object Printing : Status()
        object Ready : Status()
        data class Offline(val error: String, val retryAt: Long, val gaveUp: Boolean) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "printer") }.asCoroutineDispatcher()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var started = false

    @Volatile
    private var resetRequested = false

    // Owned by the printer thread.
    private var link: SppLink? = null
    private var lastUsed = 0L
    private var lastBytes = 0
    private var failures = 0
    private var logoCache: Pair<Int, MonoImage?>? = null

    /** The job whose bytes went out last: if recording it as done failed, it is not printed a second time. */
    private var sentJobId = -1L

    /** The bytes of the job being retried: while the printer is off a receipt is not drawn again every attempt. */
    private var renderedId = -1L
    private var rendered: ByteArray? = null

    /** The link still connecting, so that [reconnect] to another printer can give it up. */
    @Volatile
    private var connecting: SppLink? = null

    fun start() {
        if (started) return
        started = true
        graph.appScope.launch(dispatcher) {
            try {
                while (true) {
                    try {
                        loop()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Anything else (a failed database write, an odd Bluetooth error, out of memory) ended
                        // the loop for good: the status froze and nothing printed until the app restarted.
                        // Start over after a pause instead (2026-10 review).
                        Log.e("Printer loop failed, restarting", e)
                        settle() // the last job's bytes leave the phone before the link closes
                        closeLink()
                        clearRendered()
                        logoCache = null
                        val retryAt = System.currentTimeMillis() + RESTART_MS
                        _status.value = Status.Offline(e.javaClass.simpleName, retryAt, gaveUp = false)
                        waitForWake(RESTART_MS) // "Retry" or new settings start over at once
                    }
                }
            } finally {
                started = false
            }
        }
    }

    /** Look at the queue now (a job was added, or the user asked to retry). */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /**
     * Try again now (printer or settings changed, or the user asked): the backoff and the logo are
     * reset and a link to another printer is dropped. A live link to the same printer is kept —
     * closing it right after a job (test page, then "open drawer") cut the job off, because bytes
     * still queued in the Bluetooth stack are thrown away on close; a dead one fails on the next
     * write and reconnects by itself.
     */
    fun reconnect() {
        resetRequested = true
        // A connect still trying a printer that is no longer the chosen one is given up at once instead of
        // running through all its attempts first (2026-10 review). Closed off the main thread.
        val c = connecting
        if (c != null && c.address != graph.settings.device.value.printerAddress) {
            graph.appScope.launch(Dispatchers.IO) { c.close() }
        }
        wake()
    }

    private suspend fun loop() {
        graph.settings.load()
        val db = graph.db()
        db.write(reserveIds = 0) { tx -> PrintJobDao.purge(tx, System.currentTimeMillis() - PURGE_AFTER_MS) }
        while (true) {
            val cfg = graph.settings.device.value
            _pending.value = db.read { PrintJobDao.pendingCount(it) }.toInt()
            val address = cfg.printerAddress
            if (resetRequested) {
                resetRequested = false
                logoCache = null
                clearRendered() // settings (paper, mode, logo) may have changed
                failures = 0
            }
            if (link != null && link?.address != address) {
                settle()
                closeLink()
            }
            if (address == null) {
                _status.value = Status.NotConfigured
                waitForWake(null)
                continue
            }
            if (sentJobId != -1L) {
                // Its bytes went out but recording that failed, and the loop restarted: recorded before
                // any other job runs - a drawer pulse in between cleared this, and the receipt printed
                // twice (2026-10 review).
                val sent = sentJobId
                db.write(reserveIds = 0) { tx -> PrintJobDao.markDone(tx, sent, System.currentTimeMillis()) }
                sentJobId = -1L
                continue
            }
            val job = db.read { PrintJobDao.next(it) }
            if (job == null) {
                clearRendered() // e.g. the queue was cleared while a job was waiting for the printer
                val now = System.currentTimeMillis()
                if (link != null && now - lastUsed >= IDLE_CLOSE_MS) closeLink()
                _status.value = if (link != null) Status.Ready else Status.Idle
                waitForWake(if (link != null) IDLE_CLOSE_MS else null)
                continue
            }
            val now = System.currentTimeMillis()
            if (job.kind == PrintJobKind.RECEIPT && now - job.createdAt > RECEIPT_MAX_AGE_MS) {
                // The printer was off: drop the backlog instead of printing old receipts between new sales.
                db.write(reserveIds = 0) { tx -> PrintJobDao.expireReceipts(tx, now - RECEIPT_MAX_AGE_MS, now) }
                continue
            }
            val adapter = Bluetooth.adapter(app)
            when {
                adapter == null -> _status.value = Status.NoBluetooth
                !Bluetooth.hasPermission(app) -> _status.value = Status.NoPermission
                !adapter.isEnabled -> _status.value = Status.BluetoothOff
                else -> {
                    print(job, cfg, adapter, address)
                    continue
                }
            }
            waitForWake(RECHECK_MS)
        }
    }

    private suspend fun print(job: PrintJob, cfg: DeviceSettings, adapter: android.bluetooth.BluetoothAdapter, address: String) {
        val db = graph.db()
        if (job.id == sentJobId) {
            // Its bytes went out, then recording that failed and the loop restarted: never print it twice.
            db.write(reserveIds = 0) { tx -> PrintJobDao.markDone(tx, job.id, System.currentTimeMillis()) }
            sentJobId = -1L
            return
        }
        val bytes = try {
            bytesOf(job, cfg)
        } catch (e: Exception) {
            renderFailed(job, e)
            return
        } catch (e: OutOfMemoryError) {
            // Too big for this phone: every retry would fail the same way and hold up every later job
            // (2026-10 review). It can still be shared as a PDF or printed in text mode.
            logoCache = null
            renderFailed(job, e)
            return
        }
        try {
            if (bytes != null) {
                if (link == null) {
                    _status.value = Status.Connecting
                    val l = SppLink(adapter, address)
                    connecting = l
                    try {
                        l.open()
                    } finally {
                        connecting = null
                    }
                    link = l
                }
                // Connecting can take a while: a job cleared from the queue meanwhile is not printed
                // (it printed after "Clear the queue", 2026-10 review).
                if (!db.read { PrintJobDao.isPending(it, job.id) }) return
                _status.value = Status.Printing
                write(bytes, cfg.dots)
                sentJobId = job.id
                lastUsed = System.currentTimeMillis()
                lastBytes = bytes.size
            }
            failures = 0
            clearRendered()
            db.write(reserveIds = 0) { tx -> PrintJobDao.markDone(tx, job.id, System.currentTimeMillis()) }
            sentJobId = -1L // recorded: job ids are reused after old jobs are purged
        } catch (e: IOException) {
            failed(job, e)
        } catch (e: SecurityException) {
            failed(job, e)
        }
    }

    private suspend fun failed(job: PrintJob, e: Exception) {
        closeLink()
        failures++
        val msg = e.message ?: e.javaClass.simpleName
        Log.w("Printing failed (attempt $failures): $msg")
        graph.db().write(reserveIds = 0) { tx -> PrintJobDao.markAttempt(tx, job.id, msg, System.currentTimeMillis()) }
        val gaveUp = failures >= MAX_AUTO_RETRIES
        val delay = BACKOFF_MS[minOf(failures, BACKOFF_MS.size) - 1]
        _status.value = Status.Offline(msg, System.currentTimeMillis() + delay, gaveUp)
        // After many failures, wait for a new job or the user's "retry" instead of polling.
        waitForWake(if (gaveUp) null else delay)
    }

    /**
     * [render] once per job: retries while the printer is off reuse the bytes (2026-10 review; a long
     * picture receipt was drawn again every few seconds). Drawer pulses are not kept: they expire.
     */
    private suspend fun bytesOf(job: PrintJob, cfg: DeviceSettings): ByteArray? {
        if (job.id == renderedId) return rendered
        clearRendered() // the last job's bytes go before the next job is drawn
        val bytes = render(job, cfg)
        if (bytes != null && job.kind != PrintJobKind.DRAWER) {
            renderedId = job.id
            rendered = bytes
        }
        return bytes
    }

    private fun clearRendered() {
        renderedId = -1L
        rendered = null
    }

    private suspend fun renderFailed(job: PrintJob, e: Throwable) {
        Log.e("Print job ${job.id} cannot be rendered", e)
        clearRendered()
        val msg = e.message ?: e.javaClass.simpleName
        graph.db().write(reserveIds = 0) { tx -> PrintJobDao.markFailed(tx, job.id, msg, System.currentTimeMillis()) }
    }

    /** ESC/POS bytes of a job; null = nothing to send (e.g. a drawer pulse that is too old). */
    private suspend fun render(job: PrintJob, cfg: DeviceSettings): ByteArray? {
        val now = System.currentTimeMillis()
        val store = graph.settings.store.value
        return when (job.kind) {
            PrintJobKind.DRAWER -> {
                if (!cfg.drawerEnabled || now - job.createdAt > DRAWER_MAX_AGE_MS) null else ReceiptEncoder.drawerOnly(cfg.drawer())
            }
            PrintJobKind.TEST -> encode(TestPage.lines(store.name, cfg), cfg, null, 1)
            PrintJobKind.RECEIPT, PrintJobKind.REPRINT -> {
                val saleId = job.refId ?: throw IllegalStateException("receipt job without a sale")
                val tz = TimeZone.getDefault()
                val doc = graph.db().read { ReceiptBuilder.build(it, saleId, job.kind == PrintJobKind.REPRINT, store, tz) }
                    ?: throw IllegalStateException("sale $saleId not found")
                val logo = if (store.printLogo) logo(cfg.dots) else null
                encode(ReceiptBuilder.layout(doc, cfg.cols, store, logo != null, tz), cfg, logo, job.copies)
            }
            PrintJobKind.SHIFT -> {
                val shiftId = job.refId ?: throw IllegalStateException("shift report job without a shift")
                val report = graph.shifts.report(shiftId) ?: throw IllegalStateException("shift $shiftId not found")
                val layout = ShiftReportLayout(store.currency, ShiftText.forLanguage(store.receiptLanguage), TimeZone.getDefault())
                encode(layout.lines(report, cfg.cols), cfg, null, job.copies)
            }
            else -> null
        }
    }

    private fun encode(lines: List<PrintLine>, cfg: DeviceSettings, logo: MonoImage?, copies: Int): ByteArray {
        val profile = cfg.profile()
        val asImage = when (cfg.printMode) {
            DeviceSettings.MODE_IMAGE -> true
            DeviceSettings.MODE_TEXT -> false
            else -> ReceiptBuilder.needsImage(lines, profile.textMode)
        }
        val qrData = lines.firstOrNull { it is PrintLine.Qr }?.let { (it as PrintLine.Qr).data }
        val qr = if (qrData != null && (asImage || !profile.nativeQr)) Images.qr(qrData, cfg.dots / 2) else null
        return if (asImage) {
            ReceiptEncoder.image(ReceiptRenderer(cfg.cols, cfg.dots).mono(lines, logo, qr), profile, copies = copies)
        } else {
            ReceiptEncoder.text(lines, profile, logo, qr, copies = copies)
        }
    }

    private fun logo(dots: Int): MonoImage? {
        val cached = logoCache
        if (cached != null && cached.first == dots) return cached.second
        val img = Images.logo(app, dots)
        logoCache = dots to img
        return img
    }

    /**
     * Writes in small chunks. Large (image) jobs are paced to about [PACE_ROWS_PER_S] dot rows a
     * second, the speed of a cheap 58 mm head, so printers without flow control never overflow
     * their buffer: 1 KB per 8 ms was five times faster than such a head prints, and the dropped
     * raster data printed as garbage.
     */
    private fun write(bytes: ByteArray, dots: Int) {
        val out = link?.output() ?: throw IOException("not connected")
        val paceMs = if (bytes.size > PACE_ABOVE_BYTES) CHUNK_BYTES * 1000L / ((dots / 8) * PACE_ROWS_PER_S) else 0L
        var i = 0
        while (i < bytes.size) {
            val n = minOf(CHUNK_BYTES, bytes.size - i)
            out.write(bytes, i, n)
            out.flush()
            i += n
            if (paceMs > 0L) Thread.sleep(paceMs)
        }
    }

    /** Before closing on purpose: gives the last job time to leave the phone (close drops what is still queued). */
    private suspend fun settle() {
        val until = lastUsed + maxOf(SETTLE_MIN_MS, lastBytes.toLong() / SETTLE_BYTES_PER_MS)
        val wait = until - System.currentTimeMillis()
        if (wait > 0L) delay(minOf(wait, SETTLE_MAX_MS))
    }

    private fun closeLink() {
        link?.close()
        link = null
    }

    private suspend fun waitForWake(timeoutMs: Long?) {
        if (timeoutMs == null) wakeups.receive() else withTimeoutOrNull(timeoutMs) { wakeups.receive() }
    }

    companion object {
        private const val IDLE_CLOSE_MS = 45_000L
        private const val RECHECK_MS = 10_000L
        private const val RESTART_MS = 10_000L
        private const val DRAWER_MAX_AGE_MS = 120_000L
        private const val RECEIPT_MAX_AGE_MS = 10L * 60_000L
        private const val PURGE_AFTER_MS = 7L * 24 * 3600 * 1000
        private const val MAX_AUTO_RETRIES = 10
        private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
        private const val CHUNK_BYTES = 1024
        private const val PACE_ABOVE_BYTES = 4096

        /** About 60 mm/s at 8 dots per mm: 58 mm paper ~42 ms per KB, 80 mm ~28 ms per KB. */
        private const val PACE_ROWS_PER_S = 500
        private const val SETTLE_MIN_MS = 500L
        private const val SETTLE_BYTES_PER_MS = 16
        private const val SETTLE_MAX_MS = 5_000L
    }
}
