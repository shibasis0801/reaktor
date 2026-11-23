package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DatePickerTest {
    private val today = CalendarDay.utc(2026, 11, 23)
    private val calendar = EnglishCalendarText { today }

    @Test
    fun daysRoundTripThroughTheCalendar() {
        for ((y, m, d) in listOf(Triple(1900, 1, 1), Triple(1970, 1, 1), Triple(2000, 2, 29), Triple(2026, 11, 23), Triple(2100, 12, 31))) {
            val day = CalendarDay.of(CalendarDay.utc(y, m, d))
            assertEquals(Triple(y, m, d), Triple(day.year, day.month, day.day))
        }
        // 1970-01-01 was a Thursday; 2026-11-23 is a Monday.
        assertEquals(4, CalendarDay.of(0).dayOfWeek)
        assertEquals(1, CalendarDay.of(today).dayOfWeek)
    }

    @Test
    fun aMonthStartsWhereItsWeekPutsIt() {
        // November 2026 begins on a Sunday: the first cell of a Sunday-first week, the seventh of a Monday-first one.
        assertEquals(0, CalendarMonth.of(2026, 11, firstDayOfWeek = 7).daysFromStartOfWeekToFirstOfMonth)
        assertEquals(6, CalendarMonth.of(2026, 11, firstDayOfWeek = 1).daysFromStartOfWeekToFirstOfMonth)
        assertEquals(29, CalendarMonth.of(2028, 2, 7).numberOfDays)
        assertEquals(CalendarMonth.of(2026, 11, 7), CalendarMonth.at(CalendarMonth.of(2026, 11, 7).indexIn(DatePickerYears), DatePickerYears, 7))
    }

    @Test
    fun theTypedFormIsReadFromTheLocalesShortPattern() {
        assertEquals(DateInputFormat("MM/dd/yyyy", '/'), DateInputFormat.of("M/d/yy"))
        assertEquals(DateInputFormat("dd/MM/yyyy", '/'), DateInputFormat.of("dd/MM/y"))
        assertEquals(DateInputFormat("dd.MM.yyyy", '.'), DateInputFormat.of("dd.MM.yy"))
        assertEquals(DateInputFormat("yyyy-MM-dd", '-'), DateInputFormat.of("y-MM-dd"))
        assertEquals("ddMMyyyy", DateInputFormat.of("d/M/yy").copy(patternWithDelimiters = "dd/MM/yyyy").patternWithoutDelimiters)
    }

    @Test
    fun aChosenDayOutsideTheYearsIsNoChoice() {
        val state = DatePickerState(calendar, CalendarDay.utc(2026, 11, 22), years = 2000..2030)
        assertEquals(CalendarDay.utc(2026, 11, 1), state.displayedMonthMillis)
        state.selectedDateMillis = CalendarDay.utc(2031, 1, 1)
        assertNull(state.selectedDateMillis)
        // A time within a day counts as that day.
        state.selectedDateMillis = CalendarDay.utc(2026, 3, 4) + 5 * 3_600_000L
        assertEquals(CalendarDay.utc(2026, 3, 4), state.selectedDateMillis)
    }

    @Test
    fun switchingEntryBringsTheChosenMonthIntoView() {
        val state = DatePickerState(calendar, CalendarDay.utc(2026, 3, 4), initialDisplayedMonthMillis = CalendarDay.utc(2026, 11, 1))
        state.entry = DateEntry.Typed
        assertEquals(CalendarDay.utc(2026, 3, 1), state.displayedMonthMillis)
    }

    @Test
    fun typedDigitsSayWhatTheyName() {
        val past = object : SelectableDates {
            override fun isSelectableDate(utcDay: Long) = utcDay <= today
        }
        val state = DatePickerState(calendar, years = 1900..2100, selectable = past)
        assertEquals(TypedDate.Incomplete, state.read("1122"))
        assertEquals(TypedDate.Unreadable("MM/DD/YYYY"), state.read("13402026"))
        assertEquals(TypedDate.OutsideYears(1900..2100), state.read("01012200"))
        assertEquals(TypedDate.NotAllowed(CalendarDay.utc(2026, 11, 24)), state.read("11242026"))
        assertEquals(TypedDate.Chosen(CalendarDay.utc(2026, 11, 22)), state.read("11222026"))
    }

    @Test
    fun theBarePickerChoosesADayAndRefusesOneItMayNot() = runComposeUiTest {
        val state = DatePickerState(calendar, initialDisplayedMonthMillis = today, selectable = object : SelectableDates {
            override fun isSelectableDate(utcDay: Long) = utcDay <= today
        })
        setContent { DatePicker(state) }
        onNodeWithText("November 2026").assertExists()
        onNodeWithText("12").performClick()
        assertEquals(CalendarDay.utc(2026, 11, 12), state.selectedDateMillis)
        onNodeWithText("24").assertIsNotEnabled()
    }

    @Test
    fun theThemesAppearanceDrawsThePicker() = runComposeUiTest {
        val drawn = mutableListOf<String>()
        val looks = Appearances(
            Appearance.DatePicker provides object : DatePickerAppearance {
                @Composable
                override fun Content(properties: DatePickerProperties, state: DatePickerState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
                    drawn += properties.calendar.format(state.displayedMonthMillis, "yMMMM")
                }
            },
        )
        setContent {
            CompositionLocalProvider(LocalAppearances provides looks) { DatePicker(DatePickerState(calendar)) }
        }
        waitForIdle()
        assertTrue(drawn.isNotEmpty())
        assertEquals("November 2026", drawn.last())
    }
}
