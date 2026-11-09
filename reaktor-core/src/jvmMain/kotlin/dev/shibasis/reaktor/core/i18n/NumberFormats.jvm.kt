package dev.shibasis.reaktor.core.i18n

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

actual fun numberFormats(locale: String): NumberFormats = JvmNumberFormats(locale)

actual fun systemLocale(): String = Locale.getDefault().toLanguageTag()

/**
 * The JDK's `java.text`, with one repair. `DecimalFormat` keeps a single grouping size, the last
 * one in its pattern, so CLDR's `#,##,##0.00` for Indian English prints `100,000` where the
 * locale writes `1,00,000`. The JDK does not expose the pattern it was built from, so the locales
 * whose CLDR data groups by three and then by two are listed here and regrouped after formatting.
 * Android, Apple platforms and browsers read that from their own data and need no list.
 */
private class JvmNumberFormats(override val locale: String) : NumberFormats {
    private val javaLocale = Locale.forLanguageTag(locale)
    private val symbols = DecimalFormatSymbols.getInstance(javaLocale)
    private val indianGrouping = javaLocale.usesIndianGrouping()

    override val decimalSeparator: Char = symbols.monetaryDecimalSeparator
    override val groupingSeparator: Char = symbols.groupingSeparator
    override val zeroDigit: Char = symbols.zeroDigit

    override fun money(value: Money, display: CurrencyDisplay, fractions: Fractions): String {
        val digits = fractionDigits(value, fractions)
        val format = when (display) {
            CurrencyDisplay.Symbol -> NumberFormat.getCurrencyInstance(javaLocale)
            CurrencyDisplay.None -> NumberFormat.getNumberInstance(javaLocale)
        } as DecimalFormat
        if (display == CurrencyDisplay.Symbol) {
            val known = runCatching { Currency.getInstance(value.currency) }.getOrNull()
            if (known != null) {
                format.currency = known
            } else {
                // A code the JDK does not know still prints, with the code as its symbol.
                format.decimalFormatSymbols = (format.decimalFormatSymbols.clone() as DecimalFormatSymbols)
                    .apply { currencySymbol = value.currency }
            }
        }
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        val amount = BigDecimal(decimalText(value.minor, Currencies.minorDigits(value.currency)))
        return format(format, amount)
    }

    override fun decimal(unscaled: Long, scale: Int): String {
        val format = NumberFormat.getNumberInstance(javaLocale) as DecimalFormat
        format.minimumFractionDigits = scale.coerceAtLeast(0)
        format.maximumFractionDigits = scale.coerceAtLeast(0)
        return format(format, BigDecimal(decimalText(unscaled, scale)))
    }

    private fun format(format: DecimalFormat, amount: BigDecimal): String {
        if (!indianGrouping || !format.isGroupingUsed) return format.format(amount)
        format.isGroupingUsed = false
        return regroup(format.format(amount))
    }

    /** Groups the first run of digits as 3 then 2s: 1234567 → 12,34,567. */
    private fun regroup(text: String): String {
        val isDigit = { c: Char -> c in zeroDigit..(zeroDigit + 9) }
        val start = text.indexOfFirst(isDigit)
        if (start < 0) return text
        var end = start
        while (end < text.length && isDigit(text[end])) end++
        val whole = text.substring(start, end)
        if (whole.length <= 3) return text
        val grouped = StringBuilder(whole.takeLast(3))
        var rest = whole.dropLast(3)
        while (rest.isNotEmpty()) {
            grouped.insert(0, groupingSeparator).insert(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        return text.substring(0, start) + grouped + text.substring(end)
    }
}

// CLDR locales whose decimal pattern is #,##,##0: the languages of India, Bangladesh and Nepal,
// and English in India.
private val indianLanguages = setOf("as", "bn", "gu", "hi", "kn", "ml", "mr", "ne", "or", "pa", "ta", "te")

private fun Locale.usesIndianGrouping(): Boolean =
    language in indianLanguages || (language == "en" && country == "IN") ||
        (language == "ur" && country == "IN")
