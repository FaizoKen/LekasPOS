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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
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
    private var failures = 0
    private var logoCache: Pair<Int, MonoImage?>? = null

    fun start() {
        if (started) return
        started = true
        graph.appScope.launch(dispatcher) {
            try {
                loop()
            } catch (e: Exception) {
                Log.e("Printer loop stopped", e)
                started = false
            }
        }
    }

    /** Look at the queue now (a job was added, or the user asked to retry). */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /** Drop the connection (printer or settings changed) and try again. */
    fun reconnect() {
        resetRequested = true
        logoCache = null
        failures = 0
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
            if (resetRequested || (link != null && link?.address != address)) {
                closeLink()
                resetRequested = false
            }
            if (address == null) {
                _status.value = Status.NotConfigured
                waitForWake(null)
                continue
            }
            val job = db.read { PrintJobDao.next(it) }
            if (job == null) {
                val now = System.currentTimeMillis()
                if (link != null && now - lastUsed >= IDLE_CLOSE_MS) closeLink()
                _status.value = if (link != null) Status.Ready else Status.Idle
                waitForWake(if (link != null) IDLE_CLOSE_MS else null)
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
        val bytes = try {
            render(job, cfg)
        } catch (e: Exception) {
            Log.e("Print job ${job.id} cannot be rendered", e)
            db.write(reserveIds = 0) { tx -> PrintJobDao.markFailed(tx, job.id, e.message ?: e.javaClass.simpleName, System.currentTimeMillis()) }
            return
        }
        try {
            if (bytes != null) {
                if (link == null) {
                    _status.value = Status.Connecting
                    val l = SppLink(adapter, address)
                    l.open()
                    link = l
                }
                _status.value = Status.Printing
                write(bytes)
                lastUsed = System.currentTimeMillis()
            }
            failures = 0
            db.write(reserveIds = 0) { tx -> PrintJobDao.markDone(tx, job.id, System.currentTimeMillis()) }
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

    /** Writes in small chunks; large (image) jobs are paced so cheap printers' buffers keep up. */
    private fun write(bytes: ByteArray) {
        val out = link?.output() ?: throw IOException("not connected")
        val paced = bytes.size > PACE_ABOVE_BYTES
        var i = 0
        while (i < bytes.size) {
            val n = minOf(CHUNK_BYTES, bytes.size - i)
            out.write(bytes, i, n)
            out.flush()
            i += n
            if (paced) Thread.sleep(PACE_MS)
        }
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
        private const val DRAWER_MAX_AGE_MS = 120_000L
        private const val PURGE_AFTER_MS = 7L * 24 * 3600 * 1000
        private const val MAX_AUTO_RETRIES = 10
        private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
        private const val CHUNK_BYTES = 1024
        private const val PACE_ABOVE_BYTES = 4096
        private const val PACE_MS = 8L
    }
}
