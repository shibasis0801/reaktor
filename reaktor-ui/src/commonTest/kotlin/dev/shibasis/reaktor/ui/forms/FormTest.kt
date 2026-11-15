package dev.shibasis.reaktor.ui.forms

import dev.shibasis.reaktor.core.i18n.CurrencyDisplay
import dev.shibasis.reaktor.core.i18n.Fractions
import dev.shibasis.reaktor.core.i18n.Money
import dev.shibasis.reaktor.core.i18n.NumberFormats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FormTest {
    private object English : NumberFormats {
        override val locale = "en-IN"
        override val decimalSeparator = '.'
        override val groupingSeparator = ','
        override val zeroDigit = '0'
        override fun money(value: Money, display: CurrencyDisplay, fractions: Fractions) = error("unused")
        override fun decimal(unscaled: Long, scale: Int) = error("unused")
    }

    private fun amount() = FormField("amount", read = moneyReader({ "INR" }, English))
    private fun payee() = FormField("payee", read = Fields.text(required = "Enter who you paid."))

    @Test
    fun nothingIsFlaggedWhileTyping() {
        val field = amount()
        field.set("12.3")
        field.set("12.345")
        assertNull(field.error, "an error appeared mid-typing")
        field.left()
        assertEquals("Use at most 2 digits after the decimal point.", field.error)
    }

    @Test
    fun anErrorClearsAsSoonAsItIsFixed() {
        val field = amount()
        field.set("abc")
        field.left()
        assertTrue(field.error != null)
        field.set("250")
        assertNull(field.error)
        assertEquals(Money(25_000, "INR"), field.value())
    }

    @Test
    fun leavingAnEmptyUntouchedFieldSaysNothing() {
        val field = payee()
        field.left()
        assertNull(field.error)
    }

    @Test
    fun submittingShowsEveryErrorAndPointsAtTheFirst() {
        val payee = payee()
        val amount = amount()
        val form = Form(payee, amount)
        amount.set("0")
        assertSame(payee, form.firstInvalid())
        assertEquals("Enter who you paid.", payee.error)
        assertEquals("Enter an amount above zero.", amount.error)
        payee.set("Swiggy")
        amount.set("1,499.50")
        assertNull(form.firstInvalid())
        assertEquals(Money(149_950, "INR"), amount.value())
    }

    @Test
    fun aDraftComesBackExactlyAsTyped() {
        val payee = payee()
        val amount = amount()
        val form = Form(payee, amount)
        payee.set("Swiggy ")
        amount.set("1,499.5")
        val draft = form.draft()
        val restored = Form(payee(), amount()).apply { restore(draft) }
        assertEquals(mapOf("payee" to "Swiggy ", "amount" to "1,499.5"), restored.draft())
        assertFalse(restored.fields.any { it.error != null })
    }
}
