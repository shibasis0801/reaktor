package dev.shibasis.reaktor.core.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each platform's formatter, through the same assertions. The expected strings are CLDR's; spaces
 * are compared as plain spaces, because platforms disagree on which no-break space a locale uses.
 */
class NumberFormatsTest {
    private fun String.spaces() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun indianGroupingInLakhsAndCrores() {
        val india = numberFormats("en-IN")
        assertEquals("₹1,23,456.78", india.money(Money(12_345_678, "INR")).spaces())
        assertEquals("₹12,34,56,78,901.23", india.money(Money(1_234_567_890_123, "INR")).spaces())
        assertEquals("₹999.00", india.money(Money(99_900, "INR")).spaces())
        assertEquals("1,23,456.78", india.money(Money(12_345_678, "INR"), CurrencyDisplay.None).spaces())
        assertEquals("12,34,567", india.decimal(1_234_567).spaces())
    }

    @Test
    fun westernGrouping() {
        assertEquals("$1,234,567.89", numberFormats("en-US").money(Money(123_456_789, "USD")).spaces())
        assertEquals("1.234,56 €", numberFormats("de-DE").money(Money(123_456, "EUR")).spaces())
        assertEquals("1.234.567,8", numberFormats("de-DE").decimal(12_345_678, 1).spaces())
    }

    @Test
    fun fractionsWhenNeeded() {
        val india = numberFormats("en-IN")
        assertEquals("₹250", india.money(Money(25_000, "INR"), fractions = Fractions.WhenNeeded).spaces())
        assertEquals("₹250.50", india.money(Money(25_050, "INR"), fractions = Fractions.WhenNeeded).spaces())
    }

    @Test
    fun minorDigitsComeFromTheCurrency() {
        val us = numberFormats("en-US")
        assertTrue(us.money(Money(1_234, "JPY")).spaces().endsWith("1,234"), us.money(Money(1_234, "JPY")))
        assertTrue(us.money(Money(1_234_567, "KWD")).spaces().contains("1,234.567"), us.money(Money(1_234_567, "KWD")))
    }

    @Test
    fun separatorsAreTheLocales() {
        assertEquals('.', numberFormats("en-IN").decimalSeparator)
        assertEquals(',', numberFormats("en-IN").groupingSeparator)
        assertEquals(',', numberFormats("de-DE").decimalSeparator)
        assertEquals('.', numberFormats("de-DE").groupingSeparator)
        assertEquals('0', numberFormats("en-US").zeroDigit)
    }

    /**
     * Whatever a platform prints for a locale, the parser reads back as the same amount: native
     * digits, bidi marks, symbols with full stops in them, minus signs in either place.
     */
    @Test
    fun everythingPrintedReadsBack() {
        val locales = listOf("en-IN", "hi-IN", "bn-IN", "en-US", "en-GB", "de-DE", "fr-FR", "de-CH", "ar-EG", "ar-SA", "ja-JP")
        val amounts = listOf(0L, 5L, 25_000L, 12_345_678L, -98_765L, 100_000_000_000L)
        val currencies = listOf("INR", "USD", "EUR", "JPY", "KWD", "SAR")
        for (locale in locales) {
            val formats = numberFormats(locale)
            for (currency in currencies) for (minor in amounts) {
                val money = Money(minor, currency)
                for (display in CurrencyDisplay.entries) for (fractions in Fractions.entries) {
                    val text = formats.money(money, display, fractions)
                    assertEquals(MoneyParse.Parsed(money), parseMoney(text, currency, formats), "$locale $currency $display $fractions: \"$text\"")
                }
            }
        }
    }
}
