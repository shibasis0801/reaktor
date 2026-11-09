package dev.shibasis.reaktor.core.i18n

/** What a typed or printed amount turned out to be. */
sealed interface MoneyParse {
    data class Parsed(val money: Money) : MoneyParse

    /** Nothing but space and currency marks. */
    data object Blank : MoneyParse

    data class Invalid(val reason: Reason) : MoneyParse

    enum class Reason {
        /** No digits, or marks that make no number. */
        NotANumber,

        /** More fraction digits than the currency has: 12.345 rupees. Never rounded away. */
        TooManyDecimals,

        /** Past what a `Long` of minor units holds. */
        TooLarge,
    }
}

/**
 * Reads an amount of [currency] from [text], as a person typed it or a bank printed it, in the
 * conventions of [formats]' locale, and accepts any other way of writing it that is not ambiguous.
 *
 * - Digits from any script count: `١٢٣٫٤٥` and `१२३.४५` are both 123.45.
 * - Currency marks are skipped, including ones with a full stop in them (`Rs.`, `ر.س.`): a mark
 *   counts as part of the number only when it sits between two digits, or leads a fraction.
 * - The locale's decimal mark is the decimal mark. The other mark (`.` against `,`) is a decimal
 *   only where it cannot be grouping: it appears once and is not followed by exactly three digits,
 *   or the last of two different marks. So `12.50` is twelve and a half in German too, `1,500` is
 *   fifteen hundred in English, and `1,234.56` reads the same everywhere.
 * - A minus sign (`-`, `−`) anywhere, or parentheses around the number, make it negative.
 * - More fraction digits than the currency has is an error, unless the extra digits are zeros.
 */
fun parseMoney(text: String, currency: String, formats: NumberFormats): MoneyParse {
    require(Currencies.isCode(currency)) { "Not an ISO 4217 code: $currency" }
    var negative = false
    val chars = text.filterNot { it in INVISIBLE }
    // The number as kept characters: ASCII digits, and separators that sit inside the number.
    val number = StringBuilder()
    for (index in chars.indices) {
        val char = chars[index]
        val digit = char.digitToIntOrNull()
        when {
            digit != null && char.isDigit() -> number.append('0' + digit)
            char == '-' || char == '−' || char == '(' -> negative = true
            char in SEPARATORS || char == formats.decimalSeparator || char == formats.groupingSeparator -> {
                val previous = chars.getOrNull(index - 1)
                val next = chars.getOrNull(index + 1)
                val nextIsDigit = next != null && next.isDigit()
                val previousIsDigit = previous != null && previous.isDigit()
                when {
                    nextIsDigit && previousIsDigit -> number.append(char)
                    // A fraction with nothing before it: ".5". Not after a letter, which makes it
                    // part of a mark ("Rs.5").
                    nextIsDigit && number.isEmpty() && (previous == null || !previous.isLetter()) &&
                        char.isDecimalCandidate(formats) -> number.append(char)
                }
            }
        }
    }
    if (number.isEmpty()) {
        // Only space, currency symbols and punctuation: nothing was entered. Anything else (a
        // letter, a lone mark between nothing) is something that is not a number.
        val blank = chars.all { it.isWhitespace() || it.category == CharCategory.CURRENCY_SYMBOL || it in SEPARATORS }
        return if (blank && !negative) MoneyParse.Blank else MoneyParse.Invalid(MoneyParse.Reason.NotANumber)
    }

    val decimalAt = decimalIndex(number, formats)
    val whole = StringBuilder()
    val fraction = StringBuilder()
    number.forEachIndexed { index, char ->
        when {
            !char.isDigit() -> Unit
            decimalAt != null && index > decimalAt -> fraction.append(char)
            else -> whole.append(char)
        }
    }
    val digits = Currencies.minorDigits(currency)
    val significant = fraction.trimEnd('0')
    if (significant.length > digits) return MoneyParse.Invalid(MoneyParse.Reason.TooManyDecimals)
    val minorText = whole.toString().trimStart('0') + significant.padEnd(digits, '0')
    if (minorText.length > 18) return MoneyParse.Invalid(MoneyParse.Reason.TooLarge)
    val minor = minorText.ifEmpty { "0" }.toLong()
    return MoneyParse.Parsed(Money(if (negative) -minor else minor, currency))
}

/** Where the decimal mark is in [number] (digits and inner separators), or null for a whole number. */
private fun decimalIndex(number: CharSequence, formats: NumberFormats): Int? {
    val separators = number.indices.filter { !number[it].isDigit() }
    if (separators.isEmpty()) return null
    val last = separators.last()
    val mark = number[last]
    if (!mark.isDecimalCandidate(formats)) return null
    val sameMark = separators.count { number[it] == mark }
    if (mark == formats.decimalSeparator) return if (sameMark == 1) last else null
    if (sameMark > 1) return null
    // A different mark before this one: the last of two kinds is the decimal ("1.234,56").
    if (separators.any { number[it] != mark }) return last
    // The locale's other mark, alone: grouping if exactly three digits follow it ("1,500" in
    // English, "1.500" in German), a decimal otherwise ("12.50" in German, "1,5" in English).
    val after = number.length - last - 1
    return if (after == 3) null else last
}

private fun Char.isDecimalCandidate(formats: NumberFormats): Boolean =
    this == '.' || this == ',' || this == '٫' || this == formats.decimalSeparator

// Marks that may sit between digits: full stop, comma, the Arabic decimal and thousands marks,
// apostrophes (Swiss grouping) and the spaces locales group with.
private val SEPARATORS = setOf('.', ',', '٫', '٬', '\'', '’', ' ', ' ', ' ', ' ')

// Direction and joining marks some locales wrap numbers in; they carry no value.
private val INVISIBLE = setOf('‎', '‏', '؜', '​', '⁠', '﻿')
