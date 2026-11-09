package dev.shibasis.reaktor.core.i18n

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

actual fun dateFormats(locale: String): DateFormats = IntlDateFormats(locale)

/** `Intl.DateTimeFormat` with the Gregorian calendar, on UTC dates so no zone moves the day. */
private class IntlDateFormats(override val locale: String) : DateFormats {
    private fun utc(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): dynamic {
        val m = month - 1
        return js("new Date(Date.UTC(year, m, day, hour, minute))")
    }

    private fun format(date: dynamic, configure: (dynamic) -> Unit): String {
        val options: dynamic = js("({})")
        options.timeZone = "UTC"
        options.calendar = "gregory"
        configure(options)
        val tag = locale
        val formatter = js("new Intl.DateTimeFormat(tag, options)")
        return formatter.format(date) as String
    }

    private fun LocalDate.utc() = utc(year, month.ordinal + 1, day)

    override fun date(date: LocalDate) = format(date.utc()) { it.dateStyle = "medium" }

    override fun dayMonth(date: LocalDate) = format(date.utc()) { it.day = "numeric"; it.month = "long" }

    override fun weekdayDayMonth(date: LocalDate) =
        format(date.utc()) { it.weekday = "short"; it.day = "numeric"; it.month = "short" }

    override fun monthYear(year: Int, month: Int) = format(utc(year, month, 1)) { it.month = "long"; it.year = "numeric" }

    override fun month(month: Int) = format(utc(2026, month, 1)) { it.month = "long" }
        .let { name -> name.take(1).uppercase() + name.drop(1) }

    override fun time(time: LocalTime) = format(utc(2026, 1, 1, time.hour, time.minute)) {
        it.hour = "numeric"; it.minute = "2-digit"
    }

    override fun relativeDay(daysFromToday: Int): String? {
        if (daysFromToday !in -1..1) return null
        val tag = locale
        val options: dynamic = js("({ numeric: 'auto' })")
        val formatter = js("new Intl.RelativeTimeFormat(tag, options)")
        val text = formatter.format(daysFromToday, "day") as String
        return text.take(1).uppercase() + text.drop(1)
    }
}
