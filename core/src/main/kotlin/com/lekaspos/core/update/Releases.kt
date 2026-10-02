package com.lekaspos.core.update

/**
 * Which GitHub release, if any, updates the app (D-059). The app reads the releases from GitHub,
 * downloads and installs; what is decided about them is here, so it is tested on the JVM.
 *
 * A release updates the app when it is published (not a draft), is a normal release (a pre-release
 * — a test version — only when the phone asked for test versions), has a version newer than the
 * installed one, and carries an APK over HTTPS with its size and the SHA-256 GitHub computed for it.
 */
object Releases {

    /** Bigger than any LekasPOS APK will be (1.5.2: 1.4 MB): a wrong size never fills the phone. */
    const val MAX_APK_BYTES = 64L * 1024 * 1024

    /** The release asset every public release has (the website's Download link). */
    const val APK_NAME = "LekasPOS.apk"

    class Asset(val name: String, val url: String, val size: Long, val digest: String)

    class Release(val tag: String, val draft: Boolean, val prerelease: Boolean, val body: String, val assets: List<Asset>)

    /**
     * The numbers of a version: "v1.5.2", "1.5.2", "1.5.2-debug" → [1, 5, 2]. Anything after the
     * numbers is ignored; null when the text does not start with one.
     */
    fun version(text: String): List<Int>? {
        val s = text.trim().removePrefix("v").removePrefix("V")
        val parts = ArrayList<Int>(4)
        var i = 0
        while (i < s.length && parts.size < MAX_PARTS) {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start || i - start > 9) break
            parts.add(s.substring(start, i).toInt())
            if (i < s.length && s[i] == '.' && i + 1 < s.length && s[i + 1] in '0'..'9') i++ else break
        }
        return parts.takeIf { it.isNotEmpty() }
    }

    /** Negative when [a] is older than [b]; missing numbers count as 0 ("1.6" is "1.6.0"). */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** True when [candidate] is a newer version than [installed]; false when either is not a version. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val c = version(candidate) ?: return false
        val i = version(installed) ?: return false
        return compare(c, i) > 0
    }

    /**
     * The newest release in [releases] that updates the app at version [installed], or null when
     * none does. [tests]: pre-releases count too.
     */
    fun choose(releases: List<Release>, installed: String, tests: Boolean): Update? {
        val have = version(installed) ?: return null
        var best: Update? = null
        var bestVersion: List<Int>? = null
        for (r in releases) {
            if (r.draft || (r.prerelease && !tests)) continue
            val v = version(r.tag) ?: continue
            if (compare(v, have) <= 0) continue
            if (bestVersion != null && compare(v, bestVersion) <= 0) continue
            val name = v.joinToString(".")
            val apk = apk(r, name) ?: continue
            best = Update(name, apk.url, apk.size, sha256(apk.digest) ?: continue, r.prerelease, r.body.take(MAX_NOTES))
            bestVersion = v
        }
        return best
    }

    /**
     * The release's APK: `LekasPOS-<version>.apk`, else `LekasPOS.apk` — over HTTPS, with a
     * plausible size and GitHub's SHA-256 of it (assets uploaded before mid-2025 have none).
     */
    fun apk(r: Release, version: String): Asset? {
        val usable = r.assets.filter {
            it.url.startsWith("https://") && it.size in 1..MAX_APK_BYTES && sha256(it.digest) != null
        }
        return usable.firstOrNull { it.name == "LekasPOS-$version.apk" } ?: usable.firstOrNull { it.name == APK_NAME }
    }

    /** "sha256:<64 hex>" (GitHub's asset digest) → the lower-case hex, or null. */
    fun sha256(digest: String): String? {
        val hex = digest.trim().takeIf { it.startsWith("sha256:") }?.substring(7)?.lowercase() ?: return null
        return hex.takeIf { h -> h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' } }
    }

    private const val MAX_PARTS = 4

    /** Release notes kept with an update (they are read for "What's new" only). */
    private const val MAX_NOTES = 16 * 1024
}

/**
 * A newer version: [version] ("1.6.0"), its APK at [url] of [size] bytes with [sha256] (hex),
 * [test] when it is a pre-release, and the release's [notes] (Markdown, see [ReleaseNotes]).
 */
data class Update(val version: String, val url: String, val size: Long, val sha256: String, val test: Boolean, val notes: String)

/**
 * "What's new" from a release's notes, as plain lines. The notes are Markdown written for the
 * release page: the list under a heading that starts with "What's new" (English) or "Apa yang
 * baharu" (Bahasa Melayu; the English list when a release has none).
 */
object ReleaseNotes {

    private const val MAX_LINES = 10
    private const val MAX_LINE = 400

    fun whatsNew(notes: String, language: String): List<String> {
        val lines = notes.replace("\r", "").split('\n')
        if (language == "ms") section(lines) { it.startsWith("apa yang baharu") || it.startsWith("apa yang baru") }?.let { return it }
        return section(lines) { it.startsWith("what's new") || it.startsWith("what’s new") || it.startsWith("whats new") }.orEmpty()
    }

    /** The list items under the first heading [isWanted] accepts (lower-case heading text), or null. */
    private fun section(lines: List<String>, isWanted: (String) -> Boolean): List<String>? {
        val start = lines.indexOfFirst { heading(it)?.let(isWanted) == true }
        if (start < 0) return null
        val items = ArrayList<StringBuilder>()
        for (raw in lines.subList(start + 1, lines.size)) {
            if (heading(raw) != null) break
            val line = raw.trim()
            when {
                line.length > 2 && line[0] in "-*+" && line[1] == ' ' -> {
                    if (items.size == MAX_LINES) break
                    items.add(StringBuilder(line.substring(2).trim()))
                }
                line.isNotEmpty() && raw.startsWith(" ") && items.isNotEmpty() -> items.last().append(' ').append(line)
            }
        }
        return items.map { plain(it.toString()).take(MAX_LINE) }.filter { it.isNotEmpty() }
    }

    /** The text of a Markdown heading ("### What's new" → "what's new"), or null for other lines. */
    private fun heading(line: String): String? {
        val t = line.trimStart()
        if (!t.startsWith("#")) return null
        return t.trimStart('#').trim().lowercase()
    }

    /** Markdown marks taken out: bold, code, links (their text stays). */
    fun plain(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '[') {
                val close = text.indexOf("](", i)
                val end = if (close > i) text.indexOf(')', close) else -1
                if (close > i && end > close) {
                    sb.append(text, i + 1, close)
                    i = end + 1
                    continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < text.length && text[i + 1] == c) {
                i += 2
                continue
            }
            if (c != '`') sb.append(c)
            i++
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
