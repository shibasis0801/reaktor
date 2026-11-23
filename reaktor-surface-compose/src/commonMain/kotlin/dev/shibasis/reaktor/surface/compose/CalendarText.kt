package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * The calendar as the device's locale writes it, for a [DatePicker]: which day starts the week, the
 * days' names, dates in the platform's own formats, and the numeric form a date is typed in.
 *
 * A day is counted as its start in UTC, in milliseconds, whatever the device's time zone: the 23rd
 * of November is the instant 2026-11-23T00:00Z everywhere, as Material's picker counts it too.
 * The platform actuals read the same sources Material 3's picker does (ICU and java.time on
 * Android, NSDateFormatter on Apple), so the words match it exactly.
 */
interface CalendarText {
    /** The first day of the week, 1 for Monday to 7 for Sunday. */
    val firstDayOfWeek: Int

    /** Each day's full and narrowest standalone name, Monday first ("Monday" to "M"). */
    val weekdays: List<Pair<String, String>>

    /** The numeric form a date is typed in here: MM/dd/yyyy in the US, dd/MM/yyyy in Britain. */
    val input: DateInputFormat

    /** The start of today, where the device is, as a UTC day. */
    fun today(): Long

    /** [utcDay] written for a skeleton by the platform's best pattern for it in this locale ("yMMMd": "Nov 23, 2026"). */
    fun format(utcDay: Long, skeleton: String): String

    /** [utcDay] written in an exact pattern ("MMddyyyy": "11232026"). */
    fun pattern(utcDay: Long, pattern: String): String

    /** The UTC day [text] names in an exact pattern, or null when it names none. */
    fun parse(text: String, pattern: String): Long?

    /** A whole number in this locale's digits, without grouping ("2026", not "2,026"). */
    fun number(value: Int): String
}

/** The calendar as the device's current locale writes it. */
@Composable
expect fun rememberCalendarText(): CalendarText

/**
 * The numeric form a date is typed in: its pattern with delimiters (`MM/dd/yyyy`) and the delimiter.
 * Someone types only the digits; the delimiters are drawn in as they go.
 */
@Immutable
data class DateInputFormat(val patternWithDelimiters: String, val delimiter: Char) {
    val patternWithoutDelimiters: String = patternWithDelimiters.replace(delimiter.toString(), "")

    companion object {
        /**
         * The typed form of a locale's short date pattern: only day, month and year, each at full
         * width (`d/M/yy` becomes `dd/MM/yyyy`). Material's own reading of the pattern, without
         * regular expressions so it reads the same on every target.
         */
        fun of(localePattern: String): DateInputFormat {
            val kept = localePattern.filter { it in "dMy/-." }
            val widened = buildString {
                var i = 0
                while (i < kept.length) {
                    val c = kept[i]
                    var run = 1
                    while (i + run < kept.length && kept[i + run] == c) run++
                    when (c) {
                        'd' -> repeat((run + 1) / 2) { append("dd") }
                        'M' -> repeat((run + 1) / 2) { append("MM") }
                        'y' -> repeat((run + 3) / 4) { append("yyyy") }
                        else -> repeat(run) { append(c) }
                    }
                    i += run
                }
            }.replace("My", "M/y").removeSuffix(".")
            val delimiter = widened.firstOrNull { it in "/-." } ?: '/'
            return DateInputFormat(widened, delimiter)
        }
    }
}

/**
 * The calendar in English, Sunday first, for a picker with no platform to ask, and for tests.
 * [clock] says which day it is, as a UTC day.
 */
class EnglishCalendarText(private val clock: () -> Long) : CalendarText {
    override val firstDayOfWeek = 7
    override val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").map { it to it.take(1) }
    override val input = DateInputFormat("MM/dd/yyyy", '/')
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    override fun today(): Long = clock()

    override fun format(utcDay: Long, skeleton: String): String {
        val day = CalendarDay.of(utcDay)
        val month = months[day.month - 1]
        return when (skeleton) {
            "yMMMM" -> "$month ${day.year}"
            "yMMMd" -> "${month.take(3)} ${day.day}, ${day.year}"
            else -> "${weekdays[day.dayOfWeek - 1].first}, $month ${day.day}, ${day.year}"
        }
    }

    override fun pattern(utcDay: Long, pattern: String): String {
        val day = CalendarDay.of(utcDay)
        return pattern.replace("yyyy", day.year.toString().padStart(4, '0'))
            .replace("MM", day.month.toString().padStart(2, '0'))
            .replace("dd", day.day.toString().padStart(2, '0'))
    }

    override fun parse(text: String, pattern: String): Long? {
        if (text.length != pattern.length || text.any { !it.isDigit() }) return null
        fun field(letter: Char): Int? = pattern.indexOf(letter).takeIf { it >= 0 }?.let { start ->
            text.substring(start, pattern.lastIndexOf(letter) + 1).toIntOrNull()
        }
        val year = field('y') ?: return null
        val month = field('M')?.takeIf { it in 1..12 } ?: return null
        val day = field('d')?.takeIf { it in 1..CalendarDay.lengthOfMonth(year, month) } ?: return null
        return CalendarDay.utc(year, month, day)
    }

    override fun number(value: Int) = value.toString()
}

/** A day of the calendar: its date and its start in UTC. */
@Immutable
data class CalendarDay(val year: Int, val month: Int, val day: Int, val utcMillis: Long) {
    /** 1 for Monday to 7 for Sunday. */
    val dayOfWeek: Int get() = (utcMillis.floorDiv(DayMillis) + 3).mod(7L).toInt() + 1

    companion object {
        /** The day [utcMillis] falls on, in UTC. */
        fun of(utcMillis: Long): CalendarDay {
            // Howard Hinnant's civil-from-days, on days since 1970-01-01.
            val z = utcMillis.floorDiv(DayMillis) + 719468
            val era = z.floorDiv(146097)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
            return CalendarDay(year, month, day, utc(year, month, day))
        }

        /** The start in UTC of [year]-[month]-[day]. */
        fun utc(year: Int, month: Int, day: Int): Long {
            // Howard Hinnant's days-from-civil.
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = y.floorDiv(400)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return (era * 146097 + doe - 719468) * DayMillis
        }

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** A month of the calendar laid out in weeks that start on a given day. */
@Immutable
data class CalendarMonth(
    val year: Int,
    val month: Int,
    val numberOfDays: Int,
    /** How many cells of the first week come before the 1st. */
    val daysFromStartOfWeekToFirstOfMonth: Int,
    val startUtcMillis: Long,
) {
    /** This month's place among every month of [years], January of the first year being 0. */
    fun indexIn(years: IntRange): Int = (year - years.first) * 12 + month - 1

    /** The start in UTC of day [dayOfMonth] (1-based). */
    fun day(dayOfMonth: Int): Long = startUtcMillis + (dayOfMonth - 1) * DayMillis

    companion object {
        fun of(year: Int, month: Int, firstDayOfWeek: Int): CalendarMonth {
            val start = CalendarDay.utc(year, month, 1)
            val difference = CalendarDay(year, month, 1, start).dayOfWeek - firstDayOfWeek
            return CalendarMonth(year, month, CalendarDay.lengthOfMonth(year, month), if (difference < 0) difference + 7 else difference, start)
        }

        /** The month [utcMillis] falls in. */
        fun of(utcMillis: Long, firstDayOfWeek: Int): CalendarMonth = CalendarDay.of(utcMillis).let { of(it.year, it.month, firstDayOfWeek) }

        /** The month [index] months after January of [years]' first year. */
        fun at(index: Int, years: IntRange, firstDayOfWeek: Int): CalendarMonth = of(years.first + index / 12, index % 12 + 1, firstDayOfWeek)
    }
}

const val DayMillis: Long = 86_400_000L
