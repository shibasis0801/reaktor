package dev.shibasis.reaktor.core.i18n

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName

actual fun dateFormats(locale: String): DateFormats = DarwinDateFormats(locale)

/** `NSDateFormatter` from templates, with the Gregorian calendar and UTC on both sides. */
private class DarwinDateFormats(override val locale: String) : DateFormats {
    private val nsLocale = NSLocale(localeIdentifier = locale)
    private val utc = NSTimeZone.timeZoneWithName("UTC")!!
    private val gregorian = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian).apply {
        timeZone = utc
        this.locale = nsLocale
    }

    private fun nsDate(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): NSDate {
        val components = NSDateComponents().apply {
            this.year = year.toLong()
            this.month = month.toLong()
            this.day = day.toLong()
            this.hour = hour.toLong()
            this.minute = minute.toLong()
        }
        return gregorian.dateFromComponents(components)!!
    }

    private fun format(template: String, date: NSDate): String {
        val formatter = NSDateFormatter().apply {
            locale = nsLocale
            calendar = gregorian
            timeZone = utc
            setLocalizedDateFormatFromTemplate(template)
        }
        return formatter.stringFromDate(date)
    }

    private fun LocalDate.ns() = nsDate(year, month.ordinal + 1, day)

    override fun date(date: LocalDate) = format("yMMMd", date.ns())

    override fun dayMonth(date: LocalDate) = format("MMMMd", date.ns())

    override fun weekdayDayMonth(date: LocalDate) = format("MMMEd", date.ns())

    override fun monthYear(year: Int, month: Int) = format("yMMMM", nsDate(year, month, 1))

    override fun month(month: Int) = format("LLLL", nsDate(2026, month, 1))
        .let { name -> name.take(1).uppercase() + name.drop(1) }

    override fun time(time: LocalTime) = format("jm", nsDate(2026, 1, 1, time.hour, time.minute))

    override fun relativeDay(daysFromToday: Int): String? {
        if (daysFromToday !in -1..1) return null
        // Relative formatting names a day relative to now, in the formatter's zone: give it today
        // in that zone, moved by the offset.
        val formatter = NSDateFormatter().apply {
            locale = nsLocale
            calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = NSDateFormatterNoStyle
            doesRelativeDateFormatting = true
        }
        val plain = NSDateFormatter().apply {
            locale = nsLocale
            calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = NSDateFormatterNoStyle
        }
        val day = NSDate().let { now -> NSDate(timeIntervalSinceReferenceDate = now.timeIntervalSinceReferenceDate + daysFromToday * 86_400.0) }
        val relative = formatter.stringFromDate(day)
        return relative.takeIf { it != plain.stringFromDate(day) }
    }
}
