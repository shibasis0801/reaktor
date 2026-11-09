package dev.shibasis.reaktor.ui.forms

import dev.shibasis.reaktor.core.i18n.Money
import dev.shibasis.reaktor.core.i18n.MoneyParse
import dev.shibasis.reaktor.core.i18n.NumberFormats
import dev.shibasis.reaktor.core.i18n.parseMoney

/** What a money field says when the text is not an amount. English defaults; translate by passing your own. */
data class MoneyMessages(
    val required: String = "Enter an amount.",
    val notANumber: String = "Enter the amount in numbers, like 250 or 1,499.50.",
    val tooManyDecimals: (digits: Int) -> String = { digits ->
        if (digits == 0) "This currency has no fractions. Enter a whole number." else "Use at most $digits digits after the decimal point."
    },
    val tooLarge: String = "That amount is too large.",
    val notPositive: String = "Enter an amount above zero.",
)

/**
 * A reader for an amount of [currency], typed in the conventions of [formats]' locale (and any
 * other way that is not ambiguous, see `parseMoney`). Never rounds: too many decimals is an error.
 */
fun moneyReader(
    currency: () -> String,
    formats: NumberFormats,
    positive: Boolean = true,
    messages: MoneyMessages = MoneyMessages(),
): (String) -> FieldResult<Money> = { text ->
    val code = currency()
    when (val parsed = parseMoney(text, code, formats)) {
        MoneyParse.Blank -> FieldResult.Invalid(messages.required)
        is MoneyParse.Invalid -> FieldResult.Invalid(
            when (parsed.reason) {
                MoneyParse.Reason.NotANumber -> messages.notANumber
                MoneyParse.Reason.TooManyDecimals -> messages.tooManyDecimals(dev.shibasis.reaktor.core.i18n.Currencies.minorDigits(code))
                MoneyParse.Reason.TooLarge -> messages.tooLarge
            },
        )
        is MoneyParse.Parsed ->
            if (positive && (parsed.money.isNegative || parsed.money.isZero)) FieldResult.Invalid(messages.notPositive)
            else FieldResult.Valid(parsed.money)
    }
}
