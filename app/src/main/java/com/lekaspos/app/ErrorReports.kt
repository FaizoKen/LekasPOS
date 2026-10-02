package com.lekaspos.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.annotation.RequiresApi
import com.google.android.gms.security.ProviderInstaller
import com.lekaspos.BuildConfig
import com.lekaspos.core.diag.CrashText
import com.lekaspos.core.diag.ErrorReport
import com.lekaspos.data.db.Schema
import com.lekaspos.util.ErrorLog
import com.lekaspos.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Locale

/**
 * Error reports to the LekasPOS developer (D-057, references/architecture.md §9).
 *
 * Crashes, errors the app logs (`Log.e`), and — on Android 11+ — freezes (ANR) and the app being
 * ended by Android are kept as small report files in `files/reports` (one per bug, repeats counted,
 * at most [MAX_PENDING], 14 days). They are sent to the relay (relay/worker.js), which files them
 * on GitHub, only when the shop said yes (asked once; Settings → Error reports) — or one report the
 * user sends by hand from Diagnostics. Sending runs in a WorkManager job when the phone is online,
 * at most [MAX_PER_DAY] reports a day and one per bug a day; never on the main thread, never in a
 * sale's way. A crash is also tried once right away (bounded), so a crash at every start is heard.
 *
 * A report holds what went wrong, the app version and the phone model, and a random id for this
 * install — never sales, products, customers, staff, PINs or tokens; free text goes through
 * [CrashText.scrub]. Only release-signed builds send on their own (test builds would only add noise).
 */
object ErrorReports {

    /** The relay. Changing it needs an app update: keep it working. */
    const val URL = "https://lekaspos-reports.faizoken.workers.dev/v1/report"

    /** relay/wrangler.toml REPORT_KEY. In every APK, so not a secret: it only keeps stray requests out. */
    private const val KEY = "8b0c2e4f6f096ab8b1cb5d90"

    const val UNASKED = 0
    const val ON = 1
    const val OFF = 2

    enum class Outcome { DONE, RETRY, LATER }

    private const val PREFS = "lekas_reports"
    private const val K_CONSENT = "consent"
    private const val K_INSTALL = "install"
    private const val K_SENT = "sent"
    private const val K_EXITS = "exits_seen"
    private const val K_RUN_APP = "run_app"
    private const val K_RUN_BUILD = "run_build"

    private const val DIR = "reports"
    private const val EXT = ".json"
    private const val MANUAL = "manual-"
    private const val MAX_PENDING = 20
    private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
    private const val MAX_PER_DAY = 10
    private const val DAY_MS = 24L * 3600 * 1000
    private const val CRASH_SEND_MS = 2_500L
    private const val TRIGGER_GAP_MS = 30_000L
    private const val TIMEOUT_MS = 15_000
    private const val MAX_ANR_TRACE = 512 * 1024
    private const val MB = 1024L * 1024L

    /** [post]: the relay's name did not resolve. */
    internal const val NO_HOST = -2

    /** The application context (never an activity's). */
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var app: Context? = null

    /** Set at the selling screen's start ([atStart]): from then on a new report starts the send job. */
    @Volatile
    private var ready = false

    @Volatile
    private var lastTrigger = 0L

    @Volatile
    private var securityChecked = false

    /** Tests only: let a debug build send on its own. */
    @Volatile
    internal var sendFromTestBuilds = false

    /** Every change to the report files. */
    private val lock = Any()

    /** At app start, after ErrorLog.init: no disk work (cold-start budget). */
    fun init(context: Context) {
        app = context.applicationContext
        ErrorLog.onError = { message, t, thread, before -> logged(message, t, thread, before) }
        ErrorLog.onCrash = { thread, e, before -> crashed(thread, e, before) }
    }

    /** [UNASKED], [ON] or [OFF]. Blocking (the first call reads a small file). */
    fun consent(context: Context): Int = prefs(context).getInt(K_CONSENT, UNASKED)

    /**
     * The shop's answer. Off: what is waiting is deleted (except a report sent by hand). On: a new
     * random install id, and what is waiting goes out. Blocking.
     */
    fun setConsent(context: Context, on: Boolean) {
        val ctx = context.applicationContext
        val edit = prefs(ctx).edit().putInt(K_CONSENT, if (on) ON else OFF)
        if (on) edit.putString(K_INSTALL, newId())
        edit.apply()
        if (on) {
            trigger(ctx, force = true)
        } else {
            synchronized(lock) { pending(ctx).filter { !it.name.startsWith(MANUAL) }.forEach { it.delete() } }
        }
    }

    /**
     * After the selling screen is usable, in the background: old reports dropped, Android's record of
     * how the app last ended read (Android 11+), and the send job started if anything is waiting.
     */
    fun atStart(context: Context) {
        val ctx = context.applicationContext
        if (!ready) { // once per process: earlier runs ended before it started
            try {
                prune(ctx)
                if (Build.VERSION.SDK_INT >= 30) checkExits(ctx)
                prefs(ctx).edit().putString(K_RUN_APP, BuildConfig.VERSION_NAME).putInt(K_RUN_BUILD, BuildConfig.VERSION_CODE).apply()
            } catch (e: Exception) {
                Log.w("Error reports: the start check failed", e)
            }
            ready = true
        }
        trigger(ctx, force = true)
    }

    /** A check that failed (the performance test): reported like an error, with [details]. Blocking. */
    fun check(name: String, details: String) {
        val ctx = app ?: return
        guarded {
            val at = System.currentTimeMillis()
            queue(ctx, build(ctx, ErrorReport.CHECK, CrashText.ofName(ErrorReport.CHECK, name), at, message = name, trace = details))
            trigger(ctx)
        }
    }

    /**
     * A report the user sends from Diagnostics, with their [note] and (if they want a reply) [contact]
     * — always sent, the press is the consent. True when it went out now; otherwise the job sends it
     * once the phone is online. Blocking.
     */
    fun sendByHand(context: Context, note: String, contact: String): Boolean {
        val ctx = context.applicationContext
        val at = System.currentTimeMillis()
        val key = CrashText.Key(CrashText.fingerprint(listOf(ErrorReport.MANUAL, at.toString(), newId())), "A report from a shop")
        val r = build(ctx, ErrorReport.MANUAL, key, at, log = ErrorLog.tail(ErrorReport.MAX_LOG))
            .copy(note = note.trim(), contact = contact.trim())
            .clipped()
        val f = File(dir(ctx), "$MANUAL$at$EXT")
        synchronized(lock) { write(f, r) }
        securityProvider(ctx)
        val sent = send(ctx, f, manual = true) == Sent.YES
        if (!sent) trigger(ctx, force = true)
        return sent
    }

    /** Sends what may go now (the job, [Work.sendReports]). Blocking: network. */
    fun sendPending(context: Context): Outcome {
        val ctx = context.applicationContext
        val auto = autoAllowed(ctx)
        securityProvider(ctx)
        var outcome = Outcome.DONE
        for (f in pending(ctx)) {
            val manual = f.name.startsWith(MANUAL)
            if (!manual && !auto) continue
            when (send(ctx, f, manual)) {
                Sent.YES, Sent.DROPPED -> Unit
                Sent.HELD -> if (outcome == Outcome.DONE) outcome = Outcome.LATER
                Sent.FAILED -> return Outcome.RETRY // offline or the relay is down: all of it later
            }
        }
        return outcome
    }

    /** Reports held back by the daily limits: again in a few hours. */
    fun later(context: Context) {
        try {
            Work.sendReports(context.applicationContext, laterHours = 6)
        } catch (e: Exception) {
            android.util.Log.w(Log.TAG, "Error reports: the later job was not scheduled", e)
        }
    }

    /** Tests: the relay's answer to a test report (checked, never filed); -1 when unreachable. Blocking: network. */
    internal fun relayStatus(context: Context): Int {
        val ctx = context.applicationContext
        val r = build(ctx, ErrorReport.ERROR, CrashText.ofName("test", "Connection test"), System.currentTimeMillis())
        securityProvider(ctx)
        return post(r, install(ctx), test = true)
    }

    /** Tests: the reports waiting on this phone. Blocking. */
    internal fun waiting(context: Context): List<ErrorReport> =
        synchronized(lock) { pending(context.applicationContext).mapNotNull { read(it) } }

    // ---- capture -------------------------------------------------------------------------------

    /** A logged error (ErrorLog's own thread). [before]: the error log's lines before it. */
    private fun logged(message: String, t: Throwable?, thread: String, before: String) {
        val ctx = app ?: return
        guarded {
            val at = System.currentTimeMillis()
            val trace = t?.let { traceOf(it) }.orEmpty()
            val key = if (t != null) CrashText.ofThrowable(trace, message) else CrashText.ofMessage(message)
            queue(ctx, build(ctx, ErrorReport.ERROR, key, at, thread, message, trace, before))
            trigger(ctx)
        }
    }

    /** A crash, on the crashing thread just before the process ends: kept now, and tried once right away. */
    private fun crashed(thread: Thread, e: Throwable, before: String) {
        val ctx = app ?: return
        guarded {
            val trace = traceOf(e)
            val r = build(ctx, ErrorReport.CRASH, CrashText.ofThrowable(trace), System.currentTimeMillis(), thread.name, "", trace, before)
            queue(ctx, r)
            if (!autoAllowed(ctx)) return@guarded
            val f = File(dir(ctx), r.fingerprint + EXT)
            val sender = Thread({ guarded { send(ctx, f, manual = false) } }, "error-report")
            sender.isDaemon = true
            sender.start()
            sender.join(CRASH_SEND_MS)
        }
    }

    /** Android's record of how the app's earlier runs ended: freezes and being ended by Android. */
    @RequiresApi(30)
    private fun checkExits(ctx: Context) {
        val p = prefs(ctx)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(null, 0, 16)
        val seen = p.getLong(K_EXITS, 0L)
        val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
        if (seen == 0L) { // the first start with error reports: nothing from before them
            p.edit().putLong(K_EXITS, maxOf(newest, 1L)).apply()
            return
        }
        // They happened in the runs since the last start's check: the app version of that start.
        val runApp = p.getString(K_RUN_APP, null) ?: BuildConfig.VERSION_NAME
        val runBuild = p.getInt(K_RUN_BUILD, BuildConfig.VERSION_CODE)
        for (x in exits) {
            if (x.timestamp <= seen) continue
            exitReport(ctx, x)?.let { queue(ctx, it.copy(app = runApp, build = runBuild)) }
        }
        if (newest > seen) p.edit().putLong(K_EXITS, newest).apply()
    }

    @RequiresApi(30)
    private fun exitReport(ctx: Context, x: ApplicationExitInfo): ErrorReport? {
        val desc = CrashText.scrub(x.description.orEmpty())
        val inUse = x.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return when (x.reason) {
            ApplicationExitInfo.REASON_ANR -> {
                val all = try {
                    x.traceInputStream?.use { readCapped(it, MAX_ANR_TRACE) }.orEmpty()
                } catch (e: Exception) {
                    ""
                }
                val main = CrashText.scrub(CrashText.mainThreadText(all))
                build(ctx, ErrorReport.ANR, CrashText.ofAnr(all), x.timestamp, "main", desc, main)
            }
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                build(ctx, ErrorReport.NATIVE, CrashText.ofName(ErrorReport.NATIVE, "Native crash"), x.timestamp, message = desc)
            ApplicationExitInfo.REASON_LOW_MEMORY -> if (!inUse) null else build(
                ctx, ErrorReport.KILLED, CrashText.ofName(ErrorReport.KILLED, "Ended for low memory while in use"), x.timestamp, message = desc,
            )
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> build(
                ctx, ErrorReport.KILLED, CrashText.ofName(ErrorReport.KILLED, "Ended for using too many resources"), x.timestamp, message = desc,
            )
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> build(
                ctx, ErrorReport.KILLED, CrashText.ofName(ErrorReport.KILLED, "Could not start"), x.timestamp, message = desc,
            )
            else -> null // crashes: caught by the crash handler; the rest is normal (swiped away, updated, ...)
        }
    }

    /** A report with this phone's facts. Free text is cleaned here. */
    private fun build(
        ctx: Context,
        kind: String,
        key: CrashText.Key,
        at: Long,
        thread: String = "",
        message: String = "",
        trace: String = "",
        log: String = "",
    ): ErrorReport = ErrorReport(
        kind = kind,
        fingerprint = key.fingerprint,
        title = key.title,
        at = at,
        app = BuildConfig.VERSION_NAME,
        build = BuildConfig.VERSION_CODE,
        signing = BuildConfig.SIGNING_KEY,
        android = Build.VERSION.RELEASE.orEmpty(),
        sdk = Build.VERSION.SDK_INT,
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        ramMb = quietly(0L) {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            mi.totalMem / MB
        },
        freeMb = quietly(0L) { StatFs(ctx.filesDir.path).let { it.availableBlocksLong * it.blockSizeLong } / MB },
        dbMb = quietly(0L) { ctx.getDatabasePath(Schema.FILE_NAME).length() / MB },
        lang = quietly("") { AppLanguage.get(ctx).ifEmpty { Locale.getDefault().language } },
        thread = thread,
        message = CrashText.scrub(message),
        trace = CrashText.scrub(trace),
        log = CrashText.scrub(log),
    ).clipped()

    // ---- the queue -----------------------------------------------------------------------------

    /** Kept as `<fingerprint>.json`; the same bug again counts in the waiting report. */
    private fun queue(ctx: Context, r: ErrorReport) = synchronized(lock) {
        val d = dir(ctx)
        val f = File(d, r.fingerprint + EXT)
        val old = if (f.exists()) read(f) else null
        if (old == null && (d.list()?.size ?: 0) >= MAX_PENDING) return@synchronized // enough waiting
        write(f, old?.again(r.at) ?: r)
    }

    private enum class Sent { YES, HELD, DROPPED, FAILED }

    private fun send(ctx: Context, f: File, manual: Boolean): Sent {
        val r = synchronized(lock) { if (f.exists()) read(f) else null } ?: return Sent.DROPPED.also { synchronized(lock) { f.delete() } }
        if (!manual && !mayGo(ctx, r.fingerprint)) return Sent.HELD
        val code = post(r, install(ctx), test = false)
        return when {
            code in 200..299 -> {
                synchronized(lock) {
                    // Repeats that came in while it was sent wait for the next report.
                    val now = if (f.exists()) read(f) else null
                    if (now != null && now.count > r.count) write(f, now.copy(count = now.count - r.count, firstAt = r.at)) else f.delete()
                }
                if (!manual) noteSent(ctx, r.fingerprint)
                Sent.YES
            }
            code == 400 || code == 401 || code == 404 || code == 413 -> {
                Log.w("Error report refused by the relay ($code)")
                synchronized(lock) { f.delete() } // it would never be taken
                Sent.DROPPED
            }
            else -> Sent.FAILED
        }
    }

    /** Within the limits: [MAX_PER_DAY] reports in 24 h, the same bug once in 24 h. */
    private fun mayGo(ctx: Context, fp: String): Boolean {
        val sent = sentRecently(ctx)
        return sent.size < MAX_PER_DAY && sent.none { it.first == fp }
    }

    private fun noteSent(ctx: Context, fp: String) {
        val list = sentRecently(ctx) + (fp to System.currentTimeMillis())
        prefs(ctx).edit().putString(K_SENT, list.joinToString(";") { "${it.first}:${it.second}" }).apply()
    }

    private fun sentRecently(ctx: Context): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        return prefs(ctx).getString(K_SENT, "").orEmpty().split(';').mapNotNull { e ->
            val fp = e.substringBefore(':', "")
            val at = e.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
            if (fp.isEmpty() || now - at !in 0 until DAY_MS) null else fp to at
        }
    }

    private fun prune(ctx: Context) = synchronized(lock) {
        val now = System.currentTimeMillis()
        for (f in pending(ctx)) if (now - f.lastModified() > MAX_AGE_MS) f.delete()
        dir(ctx).listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.name.endsWith(EXT) }?.sortedBy { it.lastModified() }.orEmpty()

    /** Starts the send job when something may go (at most every 30 s; never before [atStart]). */
    private fun trigger(ctx: Context, force: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTrigger < TRIGGER_GAP_MS) return
        lastTrigger = now
        try {
            val files = pending(ctx)
            if (files.isEmpty() || (!autoAllowed(ctx) && files.none { it.name.startsWith(MANUAL) })) return
            Work.sendReports(ctx)
        } catch (e: Exception) {
            // Not Log.e: that would be a report about reports.
            android.util.Log.w(Log.TAG, "Error reports: the send job was not scheduled", e)
        } catch (e: LinkageError) {
            android.util.Log.w(Log.TAG, "Error reports: the send job was not scheduled", e)
        }
    }

    private fun autoAllowed(ctx: Context): Boolean =
        consent(ctx) == ON && (sendFromTestBuilds || (BuildConfig.SIGNING_KEY == "release" && !BuildConfig.DEBUG))

    // ---- files and the relay -------------------------------------------------------------------

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).also { it.mkdirs() }

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun install(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString(K_INSTALL, null) ?: newId().also { p.edit().putString(K_INSTALL, it).apply() }
    }

    private fun newId(): String {
        val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }

    /** Up-to-date TLS on old Android when Play services are there (as for Drive), once per process. */
    private fun securityProvider(ctx: Context) {
        if (securityChecked) return
        securityChecked = true
        try {
            ProviderInstaller.installIfNeeded(ctx)
        } catch (e: Exception) {
            // No Play services: the phone's own TLS is tried.
        } catch (e: LinkageError) {
            // the same
        }
    }

    /** The relay's HTTP status; [NO_HOST] when its name is unknown (offline, or no DNS), -1 when it could not be reached. */
    private fun post(r: ErrorReport, install: String, test: Boolean): Int {
        val body = ByteArrayOutputStream().also { out -> JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { toJson(it, r, install) } }
            .toByteArray()
        var c: HttpURLConnection? = null
        return try {
            c = URL(URL).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Lekas-Key", KEY)
            if (test) c.setRequestProperty("X-Lekas-Test", "1")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            try {
                (if (code >= 400) c.errorStream else c.inputStream)?.close()
            } catch (e: Exception) {
                // the status is what counts
            }
            code
        } catch (e: java.net.UnknownHostException) {
            NO_HOST
        } catch (e: Exception) {
            -1
        } finally {
            c?.disconnect()
        }
    }

    /** The relay's format (relay/worker.js `clean`); the pending files use it too (without the install id). */
    private fun toJson(w: JsonWriter, r: ErrorReport, install: String?) {
        w.beginObject()
        w.name("v").value(1)
        w.name("kind").value(r.kind)
        w.name("fp").value(r.fingerprint)
        if (install != null) w.name("install").value(install)
        w.name("title").value(r.title)
        w.name("at").value(r.at)
        w.name("first_at").value(r.firstAt)
        w.name("count").value(r.count.toLong())
        w.name("app").value(r.app)
        w.name("build").value(r.build.toLong())
        w.name("signing").value(r.signing)
        w.name("android").value(r.android)
        w.name("sdk").value(r.sdk.toLong())
        w.name("device").value(r.device)
        w.name("ram_mb").value(r.ramMb)
        w.name("free_mb").value(r.freeMb)
        w.name("db_mb").value(r.dbMb)
        w.name("lang").value(r.lang)
        w.name("thread").value(r.thread)
        w.name("message").value(r.message)
        w.name("trace").value(r.trace)
        w.name("log").value(r.log)
        if (r.note.isNotEmpty()) w.name("note").value(r.note)
        if (r.contact.isNotEmpty()) w.name("contact").value(r.contact)
        w.endObject()
    }

    private fun write(f: File, r: ErrorReport) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        JsonWriter(OutputStreamWriter(tmp.outputStream(), Charsets.UTF_8)).use { toJson(it, r, null) }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** A pending report, or null when the file is unreadable (it is then deleted by the caller). */
    private fun read(f: File): ErrorReport? = try {
        val v = HashMap<String, Any>()
        JsonReader(f.reader(Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                when (r.peek()) {
                    JsonToken.STRING -> v[name] = r.nextString()
                    JsonToken.NUMBER -> v[name] = r.nextLong()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        fun s(k: String) = v[k] as? String ?: ""
        fun n(k: String) = v[k] as? Long ?: 0L
        ErrorReport(
            kind = s("kind"), fingerprint = s("fp"), title = s("title"), at = n("at"), firstAt = n("first_at"),
            count = n("count").toInt().coerceAtLeast(1), app = s("app"), build = n("build").toInt(), signing = s("signing"),
            android = s("android"), sdk = n("sdk").toInt(), device = s("device"), ramMb = n("ram_mb"), freeMb = n("free_mb"),
            dbMb = n("db_mb"), lang = s("lang"), thread = s("thread"), message = s("message"), trace = s("trace"),
            log = s("log"), note = s("note"), contact = s("contact"),
        ).takeIf { it.kind.isNotEmpty() && it.fingerprint.length == 16 }
    } catch (e: Exception) {
        null
    }

    private fun traceOf(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun readCapped(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private inline fun <T> quietly(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        fallback
    }

    /** Reports help; they must never fail what they report (a full disk, say). */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            android.util.Log.w(Log.TAG, "Error reports: not kept", e)
        }
    }
}
