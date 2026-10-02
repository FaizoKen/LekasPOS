package com.lekaspos.util

import android.content.Context
import com.lekaspos.BuildConfig
import com.lekaspos.core.time.DateText
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The local error log (references/architecture.md §9): warnings, errors and the app's crashes,
 * appended to `files/logs/errors.log` (at most [MAX_BYTES], one older file kept) so a shop can send
 * what went wrong from Diagnostics. There is no crash reporting and no server: before this, a
 * release build's failures left no trace anyone could send (2026-10 review). Like every log line,
 * it holds no PINs, tokens or customer data (util.Log).
 */
object ErrorLog {

    private const val FILE = "errors.log"
    private const val OLDER = "errors.1.log"
    private const val MAX_BYTES = 256L * 1024L
    private const val MAX_TRACE_LINES = 40

    /** `files/logs`, from the app's data folder name: no Context kept, no disk touched at start. */
    @Volatile
    private var dir: File? = null

    /** Appends happen on their own thread: a warning logged on the main thread never touches the disk there. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "error-log").apply { isDaemon = true } }

    /** At app start; no disk work here (cold-start budget). Crashes are logged, then handled as before. */
    fun init(context: Context) {
        dir = File(File(context.applicationInfo.dataDir, "files"), "logs")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                write(entry("CRASH", "uncaught on ${thread.name}", e)) // the process is ending: written here and now
            } catch (t: Throwable) {
                // nothing more can be done
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun append(level: String, message: String, t: Throwable?) {
        if (dir == null) return
        val text = entry(level, message, t)
        try {
            writer.execute { write(text) }
        } catch (e: RejectedExecutionException) {
            // shutting down
        }
    }

    /** The log files, oldest first (empty when nothing was logged). Blocking. */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return listOf(File(d, OLDER), File(d, FILE)).filter { it.exists() && it.length() > 0L }
    }

    @Synchronized
    private fun write(text: String) {
        val d = dir ?: return
        try {
            d.mkdirs()
            val f = File(d, FILE)
            if (f.length() > MAX_BYTES) {
                File(d, OLDER).delete()
                f.renameTo(File(d, OLDER))
            }
            FileOutputStream(f, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            // The log helps; it must never fail what logged (a full disk, say).
        }
    }

    private fun entry(level: String, message: String, t: Throwable?): String {
        val sb = StringBuilder(128)
        sb.append(DateText.dateTime(System.currentTimeMillis(), TimeZone.getDefault())).append(' ')
            .append(level).append(" [").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")] ")
            .append(message).append('\n')
        if (t != null) {
            val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().lineSequence()
            for ((i, line) in trace.withIndex()) {
                if (i >= MAX_TRACE_LINES) {
                    sb.append("\t…\n")
                    break
                }
                if (line.isNotEmpty()) sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }
}
