package com.lekaspos.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalization shared by the FTS index, `*_key` sort columns and search queries
 * (references/database.md §8). Identical output on every API level because it does not depend
 * on SQLite tokenizers:
 *  - Latin accents removed (é → e), everything lowercased (Locale.ROOT);
 *  - letters, digits and combining marks of other scripts (e.g. Tamil vowel signs) kept;
 *  - every other character becomes a separator;
 *  - CJK ideographs, kana and hangul become one token per character, so "牛奶" matches "奶".
 */
object SearchText {

    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length + 8)
        var pendingSpace = false
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x0300..0x036F) continue // Latin combining diacritics: drop
            when {
                isCjk(cp) -> {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.appendCodePoint(cp)
                    pendingSpace = true
                }
                Character.isLetterOrDigit(cp) || isMark(cp) -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    pendingSpace = false
                    appendLower(sb, cp)
                }
                else -> pendingSpace = true
            }
        }
        return sb.toString()
    }

    /** Tokens of the normalized text. */
    fun tokens(input: String): List<String> = normalize(input).split(' ').filter { it.isNotEmpty() }

    /**
     * FTS4 MATCH expression for what the user typed: every token as a prefix, all required
     * ("milo 1k" → "milo* 1k*"). Null when there is nothing to search for. User text can never
     * inject FTS operators: only lowercase letters, digits and marks survive normalization
     * (FTS operators are uppercase or punctuation).
     */
    fun ftsQuery(input: String, maxTokens: Int = MAX_QUERY_TOKENS): String? {
        val t = tokens(input)
        if (t.isEmpty()) return null
        return t.take(maxTokens).joinToString(" ") { "$it*" }
    }

    /** Words of a search that count; more are ignored. */
    const val MAX_QUERY_TOKENS = 6

    /** Sort/prefix key stored in `*_key` columns. */
    fun key(name: String): String = normalize(name)

    /** Smallest string greater than every string starting with [prefix] (for range scans). */
    fun prefixUpperBound(prefix: String): String = prefix + '￿'

    private fun appendLower(sb: StringBuilder, cp: Int) {
        if (cp < 0x80) {
            sb.append(if (cp in 'A'.code..'Z'.code) (cp + 32).toChar() else cp.toChar())
        } else {
            sb.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
        }
    }

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /** True for a token that is a single CJK character (a whole word on its own). */
    fun isCjkChar(token: String): Boolean = token.isNotEmpty() && token.codePointCount(0, token.length) == 1 && isCjk(token.codePointAt(0))

    private fun isCjk(cp: Int): Boolean =
        cp in 0x3040..0x30FF || // hiragana, katakana
            cp in 0x3400..0x4DBF || // CJK extension A
            cp in 0x4E00..0x9FFF || // CJK unified ideographs
            cp in 0xAC00..0xD7AF || // hangul syllables
            cp in 0xF900..0xFAFF || // CJK compatibility ideographs
            cp in 0x20000..0x2FFFF // CJK extensions B+
}
