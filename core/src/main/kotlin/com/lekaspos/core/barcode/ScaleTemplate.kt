package com.lekaspos.core.barcode

/**
 * A configurable in-store scale label layout (references/money.md §7), e.g. `20IIIIIWWWWWC`:
 *  - digits: literal prefix that must match;
 *  - `I`: item code (PLU) looked up in `product_barcode` with kind SCALE_PLU;
 *  - `W`: weight in grams (= milli-kg);
 *  - `P`: price in minor units (price-embedded label);
 *  - `X`: ignored (e.g. a price check digit);
 *  - `C`: GS1 check digit over all preceding characters (must be last).
 */
class ScaleTemplate(val pattern: String) {

    init {
        require(pattern.isNotEmpty()) { "empty template" }
        require(pattern.all { it in '0'..'9' || it in "IWPXC" }) { "bad template char in $pattern" }
        require(pattern.count { it == 'I' } > 0) { "template needs item code digits (I)" }
        require(pattern.contains('W') || pattern.contains('P')) { "template needs W or P digits" }
        val c = pattern.indexOf('C')
        require(c == -1 || c == pattern.length - 1) { "check digit C must be last" }
        require(pattern.count { it == 'C' } <= 1) { "only one check digit" }
    }

    val length: Int get() = pattern.length

    fun parse(code: String): ScaleCode? {
        if (code.length != pattern.length || !code.all { it in '0'..'9' }) return null
        val item = StringBuilder()
        var weight = -1L
        var price = -1L
        for (i in pattern.indices) {
            val p = pattern[i]
            val d = code[i]
            when (p) {
                in '0'..'9' -> if (p != d) return null
                'I' -> item.append(d)
                'W' -> weight = (if (weight < 0) 0L else weight) * 10L + (d - '0')
                'P' -> price = (if (price < 0) 0L else price) * 10L + (d - '0')
                'X' -> Unit
                'C' -> if (Gtin.checkDigit(code.subSequence(0, i)) != d - '0') return null
            }
        }
        return ScaleCode(
            itemCode = item.toString(),
            weightMilli = if (weight >= 0) weight else null,
            priceMinor = if (price >= 0) price else null,
        )
    }

    override fun toString(): String = pattern

    companion object {
        /** Common EAN-13 layouts: prefix 20–29, 5-digit PLU, 5-digit weight or price. */
        fun defaults(): List<ScaleTemplate> = listOf(ScaleTemplate("20IIIIIWWWWWC"), ScaleTemplate("21IIIIIPPPPPC"))

        /** First template that parses [code]. */
        fun parseAny(templates: List<ScaleTemplate>, code: String): ScaleCode? {
            for (t in templates) t.parse(code)?.let { return it }
            return null
        }
    }
}

/** A decoded scale label. Weight is in milli-units of the product's unit (grams → milli-kg). */
data class ScaleCode(val itemCode: String, val weightMilli: Long?, val priceMinor: Long?)
