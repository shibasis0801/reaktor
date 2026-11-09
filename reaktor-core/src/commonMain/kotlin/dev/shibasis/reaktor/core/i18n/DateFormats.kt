package dev.shibasis.reaktor.core.i18n

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Dates and times written the way a locale writes them, by the platform's own formatter (ICU on
 * Android, `NSDateFormatter` on Apple platforms, `Intl.DateTimeFormat` in JS, `java.time` on the
 * JVM), from a pattern skeleton rather than a fixed pattern, so the order of day and month, the
 * separators, the month's name and the 12- or 24-hour clock all come from the locale.
 *
 * Always the Gregorian calendar, whatever the locale's default: dates here are stored and grouped
 * by Gregorian months, and a Saudi phone showing a Hijri date over a Gregorian month's entries
 * would be wrong twice. Digits are the locale's own (Arabic-Indic in `ar-EG`).
 *
 * Values are calendar values with no time zone: the caller has already decided which day it is.
 */
interface DateFormats {
    val locale: String

    /** The date with a short month name and the year: "9 Nov 2026", "Nov 9, 2026", "09.11.2026". */
    fun date(date: LocalDate): String

    /** Day and full month name, no year: "9 November", "November 9", "9. November". */
    fun dayMonth(date: LocalDate): String

    /** Weekday, day and month: "Sun, 9 Nov". For headings in a list of days. */
    fun weekdayDayMonth(date: LocalDate): String

    /** A month and its year: "November 2026". */
    fun monthYear(year: Int, month: Int): String

    /** A month's name on its own, as a title: "November", "noviembre". */
    fun month(month: Int): String

    /** A time of day in the locale's clock: "22:37", "10:37 PM". */
    fun time(time: LocalTime): String

    /**
     * "Today" for 0, "Yesterday" for -1, "Tomorrow" for 1, capitalised to start a line, or null
     * when the platform has no word for it (the JVM's `java.time` has none): show [date] instead.
     */
    fun relativeDay(daysFromToday: Int): String?
}

expect fun dateFormats(locale: String = systemLocale()): DateFormats
