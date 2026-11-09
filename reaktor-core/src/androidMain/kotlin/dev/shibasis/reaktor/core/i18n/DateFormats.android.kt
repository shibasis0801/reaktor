package dev.shibasis.reaktor.core.i18n

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Calendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

actual fun dateFormats(locale: String): DateFormats = AndroidDateFormats(locale)

/** ICU, with the Gregorian calendar forced and UTC on both sides so no zone moves the day. */
private class AndroidDateFormats(override val locale: String) : DateFormats {
    private val uLocale = ULocale.forLanguageTag(locale).setKeywordValue("calendar", "gregorian")
    private val utc = TimeZone.getTimeZone("UTC")

    private fun format(skeleton: String, calendar: Calendar): String {
        val format = DateFormat.getInstanceForSkeleton(skeleton, uLocale)
        format.timeZone = utc
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        return format.format(calendar.time)
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Calendar =
        GregorianCalendar(utc, uLocale).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }

    private fun LocalDate.calendar() = calendar(year, month.ordinal + 1, day)

    override fun date(date: LocalDate) = format("yMMMd", date.calendar())

    override fun dayMonth(date: LocalDate) = format("MMMMd", date.calendar())

    override fun weekdayDayMonth(date: LocalDate) = format("MMMEd", date.calendar())

    override fun monthYear(year: Int, month: Int) = format("yMMMM", calendar(year, month, 1))

    override fun month(month: Int) = format("LLLL", calendar(2026, month, 1))
        .let { name -> name.take(1).uppercase(java.util.Locale.forLanguageTag(locale)) + name.drop(1) }

    override fun time(time: LocalTime) = format("jm", calendar(2026, 1, 1, time.hour, time.minute))

    override fun relativeDay(daysFromToday: Int): String? {
        val direction = when (daysFromToday) {
            0 -> RelativeDateTimeFormatter.Direction.THIS
            -1 -> RelativeDateTimeFormatter.Direction.LAST
            1 -> RelativeDateTimeFormatter.Direction.NEXT
            else -> return null
        }
        val formatter = RelativeDateTimeFormatter.getInstance(
            uLocale, null, RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
        )
        return formatter.format(direction, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
    }
}
