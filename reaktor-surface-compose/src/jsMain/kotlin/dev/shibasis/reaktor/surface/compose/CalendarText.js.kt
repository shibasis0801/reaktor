package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The calendar in English, with today from the browser's clock: the browser build has no picker that needs more yet. */
@Composable
actual fun rememberCalendarText(): CalendarText = remember { EnglishCalendarText(::browserToday) }

private fun browserToday(): Long {
    val now: dynamic = js("new Date()")
    return CalendarDay.utc(now.getFullYear() as Int, (now.getMonth() as Int) + 1, now.getDate() as Int)
}
