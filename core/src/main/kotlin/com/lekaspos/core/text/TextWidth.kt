package com.lekaspos.core.text

/**
 * Column width of text on a fixed-pitch receipt: East Asian wide and fullwidth characters take
 * two columns (as on ESC/POS printers in Chinese mode), non-spacing marks and zero-width
 * characters none, everything else one.
 */
object TextWidth {

    fun of(cp: Int): Int = when {
        cp < 0x20 -> 0
        cp < 0x7F -> 1
        isZeroWidth(cp) -> 0
        isWide(cp) -> 2
        else -> 1
    }

    fun of(s: CharSequence): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            w += of(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    /** Longest prefix of [s] that fits in [width] columns (never splits a surrogate pair). */
    fun take(s: String, width: Int): String {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val cw = of(cp)
            if (w + cw > width) break
            w += cw
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    fun padEnd(s: String, width: Int): String {
        val w = of(s)
        return if (w >= width) s else s + spaces(width - w)
    }

    fun padStart(s: String, width: Int): String {
        val w = of(s)
        return if (w >= width) s else spaces(width - w) + s
    }

    /** [s] centred in [width] columns (extra space goes to the right). */
    fun center(s: String, width: Int): String {
        val w = of(s)
        if (w >= width) return s
        val left = (width - w) / 2
        return spaces(left) + s + spaces(width - w - left)
    }

    /**
     * Wraps [s] into lines of at most [width] columns, breaking at spaces where possible and
     * inside words otherwise (CJK text has no spaces). Runs of spaces collapse to one.
     * Always returns at least one line.
     */
    fun wrap(s: String, width: Int): List<String> {
        require(width > 0) { "width must be > 0" }
        val out = ArrayList<String>(2)
        var line = StringBuilder()
        var lineW = 0
        for (word in s.split(' ')) {
            if (word.isEmpty()) continue
            var rest = word
            var restW = of(rest)
            val sep = if (lineW > 0) 1 else 0
            if (lineW + sep + restW <= width) {
                if (sep == 1) line.append(' ')
                line.append(rest)
                lineW += sep + restW
                continue
            }
            if (lineW > 0) {
                out.add(line.toString())
                line = StringBuilder()
                lineW = 0
            }
            while (restW > width) {
                var head = take(rest, width)
                if (head.isEmpty()) head = rest.substring(0, Character.charCount(rest.codePointAt(0)))
                out.add(head)
                rest = rest.substring(head.length)
                restW = of(rest)
            }
            line.append(rest)
            lineW = restW
        }
        if (lineW > 0 || out.isEmpty()) out.add(line.toString())
        return out
    }

    private fun spaces(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(' ')
        return sb.toString()
    }

    private fun isZeroWidth(cp: Int): Boolean {
        if (cp in 0x200B..0x200F || cp == 0xFEFF || cp in 0x2060..0x2064) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || // Hangul Jamo initials
            cp in 0x2E80..0x303E || // CJK radicals, punctuation
            cp in 0x3041..0x33FF || // kana, CJK symbols
            cp in 0x3400..0x4DBF || // CJK extension A
            cp in 0x4E00..0x9FFF || // CJK unified ideographs
            cp in 0xA000..0xA4CF || // Yi
            cp in 0xAC00..0xD7A3 || // Hangul syllables
            cp in 0xF900..0xFAFF || // CJK compatibility ideographs
            cp in 0xFE30..0xFE4F || // CJK compatibility forms
            cp in 0xFF00..0xFF60 || // fullwidth forms
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x20000..0x3FFFD // CJK extensions B+
}
