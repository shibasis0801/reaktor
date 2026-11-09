package dev.shibasis.reaktor.core.i18n

actual fun numberFormats(locale: String): NumberFormats = IntlNumberFormats(locale)

actual fun systemLocale(): String =
    (js("new Intl.NumberFormat().resolvedOptions().locale") as? String) ?: "en"

/**
 * `Intl.NumberFormat`, in a browser, a Worker or Node. Amounts are handed over as decimal strings,
 * which current engines format exactly; an older engine reads them as a `Number`, which is still
 * exact for any amount below 2^53 minor units.
 */
private class IntlNumberFormats(override val locale: String) : NumberFormats {
    private val sample = parts(intl(locale, currencyOptions("USD", 2)), "1234567.5")

    override val decimalSeparator: Char = sample.firstOfType("decimal") ?: '.'
    override val groupingSeparator: Char = sample.firstOfType("group") ?: ','
    override val zeroDigit: Char =
        (intl(locale, numberOptions(0)).format("0") as String).firstOrNull() ?: '0'

    override fun money(value: Money, display: CurrencyDisplay, fractions: Fractions): String {
        val digits = fractionDigits(value, fractions)
        val options = when (display) {
            CurrencyDisplay.Symbol -> currencyOptions(value.currency, digits)
            CurrencyDisplay.None -> numberOptions(digits)
        }
        return intl(locale, options).format(decimalText(value.minor, Currencies.minorDigits(value.currency))) as String
    }

    override fun decimal(unscaled: Long, scale: Int): String =
        intl(locale, numberOptions(scale.coerceAtLeast(0))).format(decimalText(unscaled, scale)) as String
}

private fun intl(locale: String, options: dynamic): dynamic {
    val tag = locale
    return js("new Intl.NumberFormat(tag, options)")
}

private fun currencyOptions(currency: String, digits: Int): dynamic {
    val options: dynamic = js("({})")
    options.style = "currency"
    options.currency = currency
    options.currencyDisplay = "symbol"
    options.minimumFractionDigits = digits
    options.maximumFractionDigits = digits
    return options
}

private fun numberOptions(digits: Int): dynamic {
    val options: dynamic = js("({})")
    options.style = "decimal"
    options.useGrouping = true
    options.minimumFractionDigits = digits
    options.maximumFractionDigits = digits
    return options
}

private class Part(val type: String, val value: String)

private fun parts(format: dynamic, value: String): List<Part> {
    val array = format.formatToParts(value)
    val length = array.length as Int
    return (0 until length).map { Part(array[it].type as String, array[it].value as String) }
}

private fun List<Part>.firstOfType(type: String): Char? = firstOrNull { it.type == type }?.value?.firstOrNull()
