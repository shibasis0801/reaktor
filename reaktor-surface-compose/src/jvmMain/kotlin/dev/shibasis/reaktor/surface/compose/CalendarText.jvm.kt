package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.chrono.Chronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The calendar in the JVM's default locale. The JDK has no skeleton formatter, so the three
 * skeletons a picker asks for map to the nearest localised styles; everything else is java.time.
 */
@Composable
actual fun rememberCalendarText(): CalendarText {
    val locale = Locale.getDefault()
    return remember(locale) { JvmCalendarText(locale) }
}

private class JvmCalendarText(private val locale: Locale) : CalendarText {
    override val firstDayOfWeek: Int = WeekFields.of(locale).firstDayOfWeek.value

    override val weekdays: List<Pair<String, String>> = DayOfWeek.entries.map {
        it.getDisplayName(TextStyle.FULL_STANDALONE, locale) to it.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
    }

    override val input: DateInputFormat = DateInputFormat.of(
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, Chronology.ofLocale(locale), locale),
    )

    private val numbers = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }

    override fun today(): Long = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    override fun format(utcDay: Long, skeleton: String): String {
        val formatter = when (skeleton) {
            "yMMMM" -> DateTimeFormatter.ofPattern("LLLL yyyy", locale)
            "yMMMd" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        }
        return day(utcDay).format(formatter)
    }

    override fun pattern(utcDay: Long, pattern: String): String = day(utcDay).format(DateTimeFormatter.ofPattern(pattern, locale))

    override fun parse(text: String, pattern: String): Long? = try {
        LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, locale)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    override fun number(value: Int): String = numbers.format(value.toLong())

    private fun day(utcDay: Long) = Instant.ofEpochMilli(utcDay).atZone(ZoneOffset.UTC).toLocalDate()
}
