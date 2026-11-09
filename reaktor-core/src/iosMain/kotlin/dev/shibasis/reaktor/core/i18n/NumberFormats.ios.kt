package dev.shibasis.reaktor.core.i18n

import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

actual fun numberFormats(locale: String): NumberFormats = DarwinNumberFormats(locale)

actual fun systemLocale(): String =
    // "en_IN" or "en_IN@calendar=gregorian" → "en-IN".
    NSLocale.currentLocale.localeIdentifier.substringBefore('@').replace('_', '-')

/** `NSNumberFormatter`, which reads the system's CLDR data: lakh grouping, native digits. */
private class DarwinNumberFormats(override val locale: String) : NumberFormats {
    private val nsLocale = NSLocale(localeIdentifier = locale)
    private val currencyStyle = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterCurrencyStyle
        this.locale = nsLocale
    }

    override val decimalSeparator: Char = currencyStyle.currencyDecimalSeparator.firstOrNull() ?: '.'
    override val groupingSeparator: Char = currencyStyle.currencyGroupingSeparator.firstOrNull() ?: ','
    override val zeroDigit: Char =
        NSNumberFormatter().apply { this.locale = nsLocale }.stringFromNumber(NSDecimalNumber(string = "0"))
            ?.firstOrNull() ?: '0'

    override fun money(value: Money, display: CurrencyDisplay, fractions: Fractions): String {
        val digits = fractionDigits(value, fractions)
        val formatter = NSNumberFormatter().apply {
            this.locale = nsLocale
            when (display) {
                CurrencyDisplay.Symbol -> {
                    numberStyle = NSNumberFormatterCurrencyStyle
                    currencyCode = value.currency
                }
                CurrencyDisplay.None -> {
                    numberStyle = NSNumberFormatterDecimalStyle
                    usesGroupingSeparator = true
                }
            }
            minimumFractionDigits = digits.toULong()
            maximumFractionDigits = digits.toULong()
        }
        val amount = NSDecimalNumber(string = decimalText(value.minor, Currencies.minorDigits(value.currency)))
        return formatter.stringFromNumber(amount) ?: decimalText(value.minor, digits)
    }

    override fun decimal(unscaled: Long, scale: Int): String {
        val formatter = NSNumberFormatter().apply {
            this.locale = nsLocale
            numberStyle = NSNumberFormatterDecimalStyle
            usesGroupingSeparator = true
            minimumFractionDigits = scale.coerceAtLeast(0).toULong()
            maximumFractionDigits = scale.coerceAtLeast(0).toULong()
        }
        return formatter.stringFromNumber(NSDecimalNumber(string = decimalText(unscaled, scale)))
            ?: decimalText(unscaled, scale)
    }
}
