package com.lekaspos.core.diag

import java.security.MessageDigest

/**
 * Reading stack traces for error reports (D-057): what one bug is (its fingerprint, the same on
 * every till and in every build), its title, and the text cleaned of anything about the shop.
 *
 * Release builds keep the names of the app's own classes and methods (proguard-rules.pro), so the
 * app's frames read the same in every build; line numbers change with every edit and are left out.
 */
object CrashText {

    const val APP_PACKAGE = "com.lekaspos."

    /** One `at` line of a stack trace. */
    data class Frame(val className: String, val method: String) {
        val inApp: Boolean get() = className.startsWith(APP_PACKAGE)

        /** The frame without numbered lambda and synthetic classes, which R8 and Kotlin renumber. */
        val key: String get() = normalClass(className) + "." + method.replace(NUMBERED, "")

        /** "CartSession.commit": the class without its package, a lambda's function instead of invoke. */
        val short: String
            get() {
                val simple = className.substringAfterLast('.')
                val parts = simple.split('$').filter { it.isNotEmpty() && !it.all(Char::isDigit) && !it.startsWith("ExternalSynthetic") }
                val outer = parts.firstOrNull() ?: simple
                val named = parts.drop(1).lastOrNull { it != "Companion" && !it.startsWith("lambda") }
                val m = if (method in GENERATED && named != null) named else method
                return "$outer.$m"
            }
    }

    /** One exception of a trace (the first, then each "Caused by"). */
    data class Thrown(val className: String, val frames: List<Frame>) {
        val simpleName: String get() = className.substringAfterLast('.')
    }

    /** What a report is filed under: [fingerprint] (16 hex) and a [title] for people. */
    data class Key(val fingerprint: String, val title: String)

    private val GENERATED = setOf("invoke", "invokeSuspend", "run", "onClick", "call", "accept", "apply")
    private val AT = Regex("""^\s*at ([\w$.<>-]+)\.([\w$<>-]+)\(.*\)\s*$""")
    private val THROWN = Regex("""^(?:Caused by: )?([a-zA-Z_][\w$]*(?:\.[\w$]+)+)(?::.*)?$""")

    /** The exceptions in [trace] (the text of Throwable.printStackTrace), outermost first. */
    fun parse(trace: String): List<Thrown> {
        val out = ArrayList<Thrown>()
        var name: String? = null
        var frames = ArrayList<Frame>()
        for (raw in trace.lineSequence()) {
            val line = raw.trimEnd()
            val at = AT.find(line)
            if (at != null) {
                if (name != null) frames.add(Frame(at.groupValues[1], at.groupValues[2]))
                continue
            }
            val t = line.trimStart()
            if (t.startsWith("Suppressed: ")) { // not part of the chain: the frames up to the next cause are skipped
                if (name != null) out.add(Thrown(name, frames))
                name = null
                frames = ArrayList()
                continue
            }
            val thrown = if (line.startsWith("\t") || line.startsWith(" ")) null else THROWN.find(t)
            if (thrown != null) {
                if (name != null) out.add(Thrown(name, frames))
                name = thrown.groupValues[1]
                frames = ArrayList()
            }
        }
        if (name != null) out.add(Thrown(name, frames))
        return out
    }

    /** A crash or a thrown error: the exception types and where in the app they happened. */
    fun ofThrowable(trace: String, message: String? = null): Key {
        val chain = parse(trace).take(4)
        val parts = ArrayList<String>()
        if (message != null) parts.add("E:" + template(message))
        for (t in chain) {
            parts.add(t.className)
            val app = t.frames.filter { it.inApp }
            (if (app.isNotEmpty()) app.take(3) else t.frames.take(2)).mapTo(parts) { it.key }
        }
        // The deepest cause names the problem; the first app frame (from the deepest cause out) says where.
        val root = chain.lastOrNull()
        val where = chain.asReversed().asSequence().flatMap { it.frames.asSequence() }.firstOrNull { it.inApp }
        val what = root?.simpleName ?: "Error"
        val title = when {
            message != null -> clip(template(message), 80) + ": " + what
            where != null -> "$what in ${where.short}"
            else -> what
        }
        return Key(fingerprint(parts), title)
    }

    /** A logged error without an exception: the message with its changing parts taken out. */
    fun ofMessage(message: String): Key {
        val t = template(message)
        return Key(fingerprint(listOf("E:$t")), clip(t, 100))
    }

    /** An ANR: where the main thread was stuck ([trace]: Android's ANR trace, all threads). */
    fun ofAnr(trace: String): Key {
        val frames = mainThread(trace)
        val app = frames.filter { it.inApp }
        val used = if (app.isNotEmpty()) app.take(5) else frames.take(3)
        val title = "App not responding" + (app.firstOrNull()?.let { " in ${it.short}" } ?: frames.firstOrNull()?.let { " (${it.short})" } ?: "")
        return Key(fingerprint(listOf("anr") + used.map { it.key }), title)
    }

    /** The main thread's part of an ANR trace (its header line and frames), at most [maxLines] lines. */
    fun mainThreadText(trace: String, maxLines: Int = 60): String {
        val lines = trace.lineSequence().dropWhile { !it.startsWith("\"main\"") }.takeWhile { it.isNotBlank() }.take(maxLines).toList()
        return lines.joinToString("\n")
    }

    private fun mainThread(trace: String): List<Frame> = mainThreadText(trace, 400).lineSequence().mapNotNull { line ->
        AT.find(line)?.let { Frame(it.groupValues[1], it.groupValues[2]) }
    }.toList()

    /** Another kind of problem (a failed check, the app killed): its kind and a fixed name. */
    fun ofName(kind: String, name: String): Key = Key(fingerprint(listOf(kind, name)), name)

    /** 16 hex characters of SHA-256 over [parts]. */
    fun fingerprint(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            md.update(p.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        val sb = StringBuilder(16)
        for (b in md.digest().copyOf(8)) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    /** A message as the code wrote it: cleaned ([scrub]) and every number replaced by "#". */
    fun template(message: String): String = scrub(message.trim()).replace(DIGITS, "#")

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val MAC = Regex("""\b[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\b""")
    private val PATH = Regex("""(content://|file://|/storage/|/sdcard/|/mnt/)[^\s"')]*""")
    private val QUOTED = Regex(""""[^"\n]{1,200}"""")
    private val LONG_NUMBER = Regex("""\d{7,}""")
    private val DIGITS = Regex("""\d+""")

    /**
     * [text] without what could be about the shop or a person: e-mail addresses, Bluetooth
     * addresses, files and folders the user chose, quoted values (what was typed or read) and long
     * numbers (phone numbers, barcodes, IC numbers). Logs never hold PINs, tokens or customer data
     * (util.Log); this catches what an exception's message brings along.
     */
    fun scrub(text: String): String = text
        .replace(EMAIL, "<email>")
        .replace(MAC, "<bt-address>")
        .replace(PATH) { m -> m.groupValues[1] + "…" }
        .replace(QUOTED, "\"…\"")
        .replace(LONG_NUMBER, "<number>")

    /** The class without numbered parts: `Foo$bar$2` → `Foo$bar`, `Foo$$ExternalSyntheticLambda3` → `Foo`. */
    private fun normalClass(name: String): String =
        name.replace(SYNTHETIC, "").replace(OLD_LAMBDA) { "\$lambda" }.replace(NUMBERED, "")

    private val SYNTHETIC = Regex("[$][$]ExternalSynthetic\\w*")
    private val OLD_LAMBDA = Regex("[$]lambda-\\d+")
    private val NUMBERED = Regex("[$]\\d+")

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "…"

    private val HEX = "0123456789abcdef".toCharArray()
}
