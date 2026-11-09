package dev.shibasis.reaktor.core.i18n

/**
 * Numbers and money written the way a locale writes them, by the platform's own formatter: ICU on
 * Android, `NSNumberFormatter` on Apple platforms, `Intl.NumberFormat` in a browser or Node, and the
 * JDK's `java.text` on the desktop.
 *
 * The platform is used rather than a table shipped here because it already knows every locale:
 * Indian grouping (₹1,23,456.78), the euro after the number in German (1.234,56 €), Arabic-Indic
 * digits, where the minus goes. What it is not trusted with is the number itself: amounts reach it
 * as exact decimals built from [Money.minor], never through a `Double`.
 *
 * Locales are BCP 47 tags: `en-IN`, `de-DE`, `ar-EG`.
 */
interface NumberFormats {
    val locale: String

    /** The mark between whole and fraction in an amount of money: `.` in `en-IN`, `,` in `de-DE`. */
    val decimalSeparator: Char

    /** The mark between groups of digits in an amount of money. Often a no-break space. */
    val groupingSeparator: Char

    /** The digit zero in this locale's own digits; `'0'` unless it writes in another script. */
    val zeroDigit: Char

    /**
     * [value] with its currency, in this locale. [fractions] chooses whether ₹250 is written
     * `₹250.00` (a ledger column, where decimal points line up) or `₹250` (a sentence).
     */
    fun money(
        value: Money,
        display: CurrencyDisplay = CurrencyDisplay.Symbol,
        fractions: Fractions = Fractions.Always,
    ): String

    /** [unscaled] × 10^-[scale], grouped for this locale, with exactly [scale] fraction digits. */
    fun decimal(unscaled: Long, scale: Int = 0): String
}

enum class CurrencyDisplay {
    /** The locale's symbol for the currency: ₹, $, US$, €. */
    Symbol,

    /** No currency mark at all: the number as an amount of that currency (digits, grouping). */
    None,
}

enum class Fractions {
    /** Every minor digit, always: `250.00`. */
    Always,

    /** Minor digits only when they are not all zero: `250`, `250.50`. */
    WhenNeeded,
}

/** The formats for [locale], or for the device's locale when none is given. */
expect fun numberFormats(locale: String = systemLocale()): NumberFormats

/** The device's preferred locale as a BCP 47 tag. */
expect fun systemLocale(): String

/** The decimal text of `unscaled × 10^-scale`, exactly: `decimalText(-123450, 2)` is `-1234.50`. */
internal fun decimalText(unscaled: Long, scale: Int): String {
    val negative = unscaled < 0
    // Long.MIN_VALUE has no positive twin; its digits are spelled out by hand.
    val digits = if (unscaled == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(unscaled).toString()
    val text = if (scale <= 0) {
        digits
    } else {
        val padded = digits.padStart(scale + 1, '0')
        padded.substring(0, padded.length - scale) + "." + padded.substring(padded.length - scale)
    }
    return if (negative) "-$text" else text
}

/** How many fraction digits [value] needs under [fractions]. */
internal fun fractionDigits(value: Money, fractions: Fractions): Int {
    val digits = Currencies.minorDigits(value.currency)
    if (fractions == Fractions.Always || digits == 0) return digits
    return if (value.minor % Currencies.unit(value.currency) == 0L) 0 else digits
}
