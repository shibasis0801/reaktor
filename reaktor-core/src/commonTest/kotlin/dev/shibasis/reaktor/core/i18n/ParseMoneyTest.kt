package dev.shibasis.reaktor.core.i18n

import dev.shibasis.reaktor.core.i18n.MoneyParse.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

/** The parser alone, against fixed separators, so it is the same test on every platform. */
class ParseMoneyTest {
    private class Marks(override val decimalSeparator: Char, override val groupingSeparator: Char) : NumberFormats {
        override val locale = "test"
        override val zeroDigit = '0'
        override fun money(value: Money, display: CurrencyDisplay, fractions: Fractions) = error("unused")
        override fun decimal(unscaled: Long, scale: Int) = error("unused")
    }

    private val english = Marks('.', ',')
    private val german = Marks(',', '.')
    private val french = Marks(',', ' ')
    private val swiss = Marks('.', '’')
    private val arabic = Marks('٫', '٬')

    private fun parsed(minor: Long, currency: String = "INR") = MoneyParse.Parsed(Money(minor, currency))

    @Test
    fun readsWhatBanksPrint() {
        assertEquals(parsed(12_345_678), parseMoney("1,23,456.78", "INR", english))
        assertEquals(parsed(50_000), parseMoney("₹ 500", "INR", english))
        assertEquals(parsed(25_000), parseMoney("Rs.250", "INR", english))
        assertEquals(parsed(149_900), parseMoney("Rs. 1,499.00", "INR", english))
        assertEquals(parsed(25_000), parseMoney("INR 250.00", "INR", english))
        assertEquals(parsed(25_000), parseMoney("250.0", "INR", english))
    }

    @Test
    fun readsEachLocalesOwnMarks() {
        assertEquals(parsed(123_456, "EUR"), parseMoney("1.234,56 €", "EUR", german))
        assertEquals(parsed(123_456, "EUR"), parseMoney("1 234,56 €", "EUR", french))
        assertEquals(parsed(123_456, "CHF"), parseMoney("CHF 1’234.56", "CHF", swiss))
        assertEquals(parsed(12_345), parseMoney("١٢٣٫٤٥", "INR", arabic))
        assertEquals(parsed(12_345), parseMoney("‏١٢٣٫٤٥ ₹", "INR", arabic))
        assertEquals(parsed(12_345), parseMoney("१२३.४५", "INR", english))
    }

    @Test
    fun acceptsTheOtherMarkWhereItCannotBeGrouping() {
        assertEquals(parsed(1_250, "EUR"), parseMoney("12.50", "EUR", german))
        assertEquals(parsed(150_000, "EUR"), parseMoney("1.500", "EUR", german))
        assertEquals(parsed(150), parseMoney("1,5", "INR", english))
        assertEquals(parsed(150_000), parseMoney("1,500", "INR", english))
        assertEquals(parsed(123_456, "EUR"), parseMoney("1,234.56", "EUR", german))
        assertEquals(parsed(123_456_700, "EUR"), parseMoney("1.234.567", "EUR", german))
        assertEquals(parsed(50), parseMoney(".5", "INR", english))
    }

    @Test
    fun signs() {
        assertEquals(parsed(-25_000), parseMoney("-250", "INR", english))
        assertEquals(parsed(-25_000), parseMoney("−₹250", "INR", english))
        assertEquals(parsed(-25_000), parseMoney("(250.00)", "INR", english))
    }

    @Test
    fun wholeNumberCurrencies() {
        assertEquals(parsed(1_234, "JPY"), parseMoney("¥1,234", "JPY", english))
        assertEquals(MoneyParse.Invalid(Reason.TooManyDecimals), parseMoney("1,234.5", "JPY", english))
        assertEquals(parsed(1_234_567, "KWD"), parseMoney("1,234.567", "KWD", english))
    }

    @Test
    fun neverRoundsOrGuesses() {
        assertEquals(MoneyParse.Invalid(Reason.TooManyDecimals), parseMoney("12.345", "INR", english))
        assertEquals(parsed(1_250), parseMoney("12.500", "INR", english))
        assertEquals(MoneyParse.Invalid(Reason.TooLarge), parseMoney("99999999999999999999", "INR", english))
        assertEquals(MoneyParse.Invalid(Reason.NotANumber), parseMoney("abc", "INR", english))
        assertEquals(MoneyParse.Invalid(Reason.NotANumber), parseMoney("-", "INR", english))
    }

    @Test
    fun blank() {
        assertEquals(MoneyParse.Blank, parseMoney("", "INR", english))
        assertEquals(MoneyParse.Blank, parseMoney("  ", "INR", english))
        assertEquals(MoneyParse.Blank, parseMoney("₹ ", "INR", english))
    }
}
