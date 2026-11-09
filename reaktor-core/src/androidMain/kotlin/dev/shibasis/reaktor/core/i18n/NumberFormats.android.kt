package dev.shibasis.reaktor.core.i18n

import android.icu.text.DecimalFormatSymbols
import android.icu.text.NumberFormat
import android.icu.util.Currency
import android.icu.util.ULocale
import java.math.BigDecimal
import java.util.Locale

actual fun numberFormats(locale: String): NumberFormats = AndroidNumberFormats(locale)

actual fun systemLocale(): String = Locale.getDefault().toLanguageTag()

/** ICU, which Android ships (API 24+) with full CLDR data: lakh grouping, native digits, bidi marks. */
private class AndroidNumberFormats(override val locale: String) : NumberFormats {
    private val uLocale = ULocale.forLanguageTag(locale)
    private val symbols = DecimalFormatSymbols.getInstance(uLocale)

    override val decimalSeparator: Char = symbols.monetaryDecimalSeparator
    override val groupingSeparator: Char = symbols.monetaryGroupingSeparator
    override val zeroDigit: Char = symbols.zeroDigit

    override fun money(value: Money, display: CurrencyDisplay, fractions: Fractions): String {
        val digits = fractionDigits(value, fractions)
        val format = when (display) {
            CurrencyDisplay.Symbol -> NumberFormat.getCurrencyInstance(uLocale)
            CurrencyDisplay.None -> NumberFormat.getNumberInstance(uLocale)
        }
        if (display == CurrencyDisplay.Symbol) format.currency = Currency.getInstance(value.currency)
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        return format.format(BigDecimal(decimalText(value.minor, Currencies.minorDigits(value.currency))))
    }

    override fun decimal(unscaled: Long, scale: Int): String {
        val format = NumberFormat.getNumberInstance(uLocale)
        format.minimumFractionDigits = scale.coerceAtLeast(0)
        format.maximumFractionDigits = scale.coerceAtLeast(0)
        return format.format(BigDecimal(decimalText(unscaled, scale)))
    }
}
