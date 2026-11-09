package dev.shibasis.reaktor.core.i18n

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

actual fun dateFormats(locale: String): DateFormats = JvmDateFormats(locale)

/**
 * `java.time`, which is ISO (Gregorian) by construction. It prints ASCII digits unless given the
 * locale's [DecimalStyle], and has no words for relative days.
 */
private class JvmDateFormats(override val locale: String) : DateFormats {
    private val javaLocale = Locale.forLanguageTag(locale)

    private val digits = DecimalStyle.of(javaLocale)

    private fun DateTimeFormatter.local() = withLocale(javaLocale).withDecimalStyle(digits)

    private fun skeleton(template: String) = DateTimeFormatter.ofLocalizedPattern(template).local()

    private fun LocalDate.java() = java.time.LocalDate.of(year, month.ordinal + 1, day)

    override fun date(date: LocalDate): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).local().format(date.java())

    override fun dayMonth(date: LocalDate): String = skeleton("MMMMd").format(date.java())

    override fun weekdayDayMonth(date: LocalDate): String = skeleton("MMMEd").format(date.java())

    override fun monthYear(year: Int, month: Int): String =
        skeleton("yMMMM").format(java.time.LocalDate.of(year, month, 1))

    override fun month(month: Int): String =
        java.time.Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, javaLocale)
            .replaceFirstChar { it.titlecase(javaLocale) }

    override fun time(time: LocalTime): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).local()
            .format(java.time.LocalTime.of(time.hour, time.minute))

    override fun relativeDay(daysFromToday: Int): String? = null
}
