package dev.shibasis.reaktor.surface.compose

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.util.TimeZone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.chrono.Chronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/** The calendar in the configuration's first locale, as Material 3's picker reads it: ICU for skeletons, java.time for the rest. */
@Composable
actual fun rememberCalendarText(): CalendarText {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { AndroidCalendarText(locale) }
}

private class AndroidCalendarText(private val locale: Locale) : CalendarText {
    override val firstDayOfWeek: Int = WeekFields.of(locale).firstDayOfWeek.value

    override val weekdays: List<Pair<String, String>> = DayOfWeek.entries.map {
        it.getDisplayName(TextStyle.FULL_STANDALONE, locale) to it.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
    }

    override val input: DateInputFormat = DateInputFormat.of(
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, Chronology.ofLocale(locale), locale),
    )

    private val skeletons = mutableMapOf<String, DateFormat>()
    private val patterns = mutableMapOf<String, DateTimeFormatter>()
    private val numbers = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }

    override fun today(): Long = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    override fun format(utcDay: Long, skeleton: String): String = skeletons.getOrPut(skeleton) {
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
            timeZone = TimeZone.GMT_ZONE
        }
    }.format(Date(utcDay))

    override fun pattern(utcDay: Long, pattern: String): String =
        Instant.ofEpochMilli(utcDay).atZone(ZoneOffset.UTC).toLocalDate().format(formatter(pattern))

    override fun parse(text: String, pattern: String): Long? = try {
        LocalDate.parse(text, formatter(pattern)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    override fun number(value: Int): String = numbers.format(value.toLong())

    private fun formatter(pattern: String) = patterns.getOrPut(pattern) {
        DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale))
    }
}
