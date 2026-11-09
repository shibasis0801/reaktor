package dev.shibasis.reaktor.core.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun arithmeticStaysInMinorUnits() {
        val sum = (1..10).fold(Money.zero("INR")) { total, _ -> total + Money(10, "INR") }
        // Ten times ten paise is exactly one rupee, which ten times 0.1 as a Double is not.
        assertEquals(Money(100, "INR"), sum)
        assertEquals(Money(-250, "INR"), Money(250, "INR") - Money(500, "INR"))
        assertEquals(Money(250, "INR"), Money(-250, "INR").abs())
        assertTrue(Money(1, "USD") > Money(0, "USD"))
    }

    @Test
    fun refusesToMixCurrencies() {
        assertFailsWith<IllegalArgumentException> { Money(1, "INR") + Money(1, "USD") }
        assertFailsWith<IllegalArgumentException> { Money(1, "INR").compareTo(Money(1, "USD")) }
    }

    @Test
    fun refusesToOverflow() {
        assertFailsWith<IllegalStateException> { Money(Long.MAX_VALUE, "INR") + Money(1, "INR") }
        assertFailsWith<IllegalStateException> { Money(Long.MIN_VALUE, "INR") - Money(1, "INR") }
        assertFailsWith<IllegalStateException> { -Money(Long.MIN_VALUE, "INR") }
        assertFailsWith<IllegalStateException> { Money.ofMajor(Long.MAX_VALUE / 10, "INR") }
    }

    @Test
    fun refusesWhatIsNotACode() {
        assertFailsWith<IllegalArgumentException> { Money(1, "inr") }
        assertFailsWith<IllegalArgumentException> { Money(1, "₹") }
    }

    @Test
    fun minorDigitsFollowIso4217() {
        assertEquals(2, Currencies.minorDigits("INR"))
        assertEquals(0, Currencies.minorDigits("JPY"))
        assertEquals(3, Currencies.minorDigits("KWD"))
        assertEquals(Money(50_000, "INR"), Money.ofMajor(500, "INR"))
        assertEquals(Money(500, "JPY"), Money.ofMajor(500, "JPY"))
        assertEquals(Money(500_000, "KWD"), Money.ofMajor(500, "KWD"))
    }

    @Test
    fun decimalTextIsExact() {
        assertEquals("1234.50", decimalText(123450, 2))
        assertEquals("0.05", decimalText(5, 2))
        assertEquals("-0.05", decimalText(-5, 2))
        assertEquals("1234", decimalText(1234, 0))
        assertEquals("-92233720368547758.08", decimalText(Long.MIN_VALUE, 2))
        assertEquals("1234.50", Money(123450, "INR").decimalString())
        assertEquals("1234", Money(1234, "JPY").decimalString())
        assertEquals("-1.234", Money(-1234, "KWD").decimalString())
    }
}
