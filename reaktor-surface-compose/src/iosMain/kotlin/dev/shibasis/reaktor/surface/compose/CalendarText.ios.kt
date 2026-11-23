package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName

/**
 * The calendar in the first preferred language, as Material 3's picker reads it on Apple
 * platforms: NSDateFormatter templates for dates, the current calendar's first weekday.
 */
@Composable
actual fun rememberCalendarText(): CalendarText {
    val language = NSLocale.preferredLanguages.first() as String
    return remember(language) { DarwinCalendarText(NSLocale(language)) }
}

private class DarwinCalendarText(private val locale: NSLocale) : CalendarText {
    private val utc = NSTimeZone.timeZoneWithName("UTC")!!

    private fun formatter(localised: Boolean = true) = NSDateFormatter().apply {
        timeZone = utc
        if (localised) locale = this@DarwinCalendarText.locale
    }

    override val firstDayOfWeek: Int
        get() = (NSCalendar.currentCalendar.firstWeekday.toInt() - 1).takeIf { it > 0 } ?: 7

    override val weekdays: List<Pair<String, String>> = formatter().let { f ->
        val sundayFirst = f.standaloneWeekdaySymbols.map { it as String }.zip(f.veryShortStandaloneWeekdaySymbols.map { it as String })
        sundayFirst.drop(1) + sundayFirst.first()
    }

    override val input: DateInputFormat = DateInputFormat.of(
        NSDateFormatter().apply {
            locale = this@DarwinCalendarText.locale
            dateStyle = NSDateFormatterShortStyle
        }.dateFormat,
    )

    private val skeletons = mutableMapOf<String, NSDateFormatter>()
    private val patterns = mutableMapOf<String, NSDateFormatter>()
    private val readers = mutableMapOf<String, NSDateFormatter>()
    private val numbers = NSNumberFormatter().apply {
        locale = this@DarwinCalendarText.locale
        usesGroupingSeparator = false
    }

    override fun today(): Long {
        val parts = NSCalendar.currentCalendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, NSDate())
        return CalendarDay.utc(parts.year.toInt(), parts.month.toInt(), parts.day.toInt())
    }

    override fun format(utcDay: Long, skeleton: String): String =
        skeletons.getOrPut(skeleton) { formatter().apply { setLocalizedDateFormatFromTemplate(skeleton) } }.stringFromDate(date(utcDay))

    override fun pattern(utcDay: Long, pattern: String): String =
        patterns.getOrPut(pattern) { formatter().apply { dateFormat = pattern } }.stringFromDate(date(utcDay))

    override fun parse(text: String, pattern: String): Long? {
        val read = readers.getOrPut(pattern) { formatter(localised = false).apply { dateFormat = pattern } }.dateFromString(text) ?: return null
        return CalendarDay.of((read.timeIntervalSince1970 * 1000).toLong()).utcMillis
    }

    override fun number(value: Int): String = numbers.stringFromNumber(NSNumber(int = value)) ?: value.toString()

    private fun date(utcDay: Long) = NSDate.dateWithTimeIntervalSince1970(utcDay / 1000.0)
}
