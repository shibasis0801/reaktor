package dev.shibasis.reaktor.core.i18n

import kotlinx.serialization.Serializable

/**
 * An amount of money: a whole number of the currency's smallest unit, and the currency's ISO 4217
 * code. ₹1,234.50 is `Money(123450, "INR")`; ¥1,234 is `Money(1234, "JPY")`.
 *
 * Never a `Double`. A binary fraction cannot hold 0.10 exactly, so a sum of them drifts, and a
 * ledger that is a paisa out after a year of entries is a ledger nobody trusts. Arithmetic here is
 * on `Long` and refuses to overflow rather than wrap.
 *
 * How many minor units make a major one comes from [Currencies.minorDigits]: two for most, none for
 * the yen, three for the Kuwaiti dinar.
 */
@Serializable
data class Money(val minor: Long, val currency: String) : Comparable<Money> {
    init {
        require(Currencies.isCode(currency)) { "Not an ISO 4217 code: $currency" }
    }

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L

    operator fun plus(other: Money): Money = Money(addExact(minor, sameCurrency(other).minor), currency)

    operator fun minus(other: Money): Money = Money(subtractExact(minor, sameCurrency(other).minor), currency)

    operator fun unaryMinus(): Money {
        check(minor != Long.MIN_VALUE) { "Amount out of range" }
        return Money(-minor, currency)
    }

    fun abs(): Money = if (minor < 0) -this else this

    /**
     * The amount as a plain decimal in major units, `-1234.50`: a full stop, no grouping, no
     * symbol, every minor digit. For files and machines (CSV, JSON); people get [NumberFormats].
     */
    fun decimalString(): String = decimalText(minor, Currencies.minorDigits(currency))

    /** Orders amounts of one currency. Comparing two currencies is a mistake, so it throws. */
    override fun compareTo(other: Money): Int = minor.compareTo(sameCurrency(other).minor)

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Cannot combine $currency with ${other.currency}" }
        return other
    }

    companion object {
        fun zero(currency: String) = Money(0, currency)

        /** A whole number of major units: `Money.ofMajor(500, "INR")` is ₹500.00. */
        fun ofMajor(major: Long, currency: String): Money {
            var minor = major
            repeat(Currencies.minorDigits(currency)) { minor = multiplyExact(minor, 10) }
            return Money(minor, currency)
        }
    }
}

/** The ISO 4217 facts a ledger needs that no platform states the same way. */
object Currencies {
    // ISO 4217 minor units, where they are not 2. Platforms disagree on some of these for display
    // (CLDR shows the forint and the rupiah without decimals); storage follows the standard, and
    // formatting is told the digits explicitly so it shows what is stored.
    private val minorUnits = mapOf(
        "BIF" to 0, "CLP" to 0, "DJF" to 0, "GNF" to 0, "ISK" to 0, "JPY" to 0, "KMF" to 0,
        "KRW" to 0, "PYG" to 0, "RWF" to 0, "UGX" to 0, "UYI" to 0, "VND" to 0, "VUV" to 0,
        "XAF" to 0, "XOF" to 0, "XPF" to 0,
        "BHD" to 3, "IQD" to 3, "JOD" to 3, "KWD" to 3, "LYD" to 3, "OMR" to 3, "TND" to 3,
        "CLF" to 4, "UYW" to 4,
    )

    /** Digits after the decimal point for [code]: 2 unless ISO 4217 says otherwise. */
    fun minorDigits(code: String): Int = minorUnits[code] ?: 2

    /** Three upper-case ASCII letters. Whether the code is in use is the platform's business. */
    fun isCode(code: String): Boolean = code.length == 3 && code.all { it in 'A'..'Z' }

    /** 10^[minorDigits]: the number of minor units in one major unit. */
    fun unit(code: String): Long {
        var unit = 1L
        repeat(minorDigits(code)) { unit *= 10 }
        return unit
    }
}

internal fun addExact(a: Long, b: Long): Long {
    val sum = a + b
    check(((a xor sum) and (b xor sum)) >= 0) { "Amount out of range" }
    return sum
}

internal fun subtractExact(a: Long, b: Long): Long {
    val difference = a - b
    check(((a xor b) and (a xor difference)) >= 0) { "Amount out of range" }
    return difference
}

internal fun multiplyExact(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val product = a * b
    check(product / b == a && !(a == -1L && b == Long.MIN_VALUE) && !(b == -1L && a == Long.MIN_VALUE)) {
        "Amount out of range"
    }
    return product
}
