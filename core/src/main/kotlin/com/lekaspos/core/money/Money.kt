package com.lekaspos.core.money

/** An amount in minor units of the store currency (MYR: sen). */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    operator fun plus(other: Money): Money = Money(Checked.add(minor, other.minor))
    operator fun minus(other: Money): Money = Money(Checked.sub(minor, other.minor))
    operator fun unaryMinus(): Money = Money(Checked.neg(minor))
    operator fun times(factor: Long): Money = Money(Checked.mul(minor, factor))
    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L
    fun abs(): Money = if (minor < 0L) -this else this

    override fun toString(): String = "Money($minor)"

    companion object {
        val ZERO = Money(0L)
    }
}

/** A quantity in milli-units of the selling unit: 1 piece = 1000, 0.253 kg = 253. */
@JvmInline
value class Qty(val milli: Long) : Comparable<Qty> {
    operator fun plus(other: Qty): Qty = Qty(Checked.add(milli, other.milli))
    operator fun minus(other: Qty): Qty = Qty(Checked.sub(milli, other.milli))
    operator fun unaryMinus(): Qty = Qty(Checked.neg(milli))
    override fun compareTo(other: Qty): Int = milli.compareTo(other.milli)

    val isZero: Boolean get() = milli == 0L

    override fun toString(): String = "Qty($milli)"

    companion object {
        const val SCALE = 1000L
        val ZERO = Qty(0L)
        val ONE = Qty(SCALE)
        fun units(n: Long): Qty = Qty(Checked.mul(n, SCALE))
    }
}
