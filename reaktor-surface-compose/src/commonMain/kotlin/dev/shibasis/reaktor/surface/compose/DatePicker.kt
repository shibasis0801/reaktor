package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

/**
 * Choosing one day: a month's calendar to tap, or the date typed as digits. The [state] holds the
 * chosen day, the month on show and which way of entering it is showing; the appearance
 * ([Appearance.DatePicker]) draws the whole picker from it, in the device's calendar
 * ([DatePickerState.calendar]).
 */
@Composable
fun DatePicker(
    state: DatePickerState,
    modifier: Modifier = Modifier,
    appearance: DatePickerAppearance = LocalAppearances.current[Appearance.DatePicker],
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) DrawnDatePicker(state, modifier, appearance)
    else studio.Element(
        SurfaceCall(
            "DatePicker", modifier,
            listOf(
                SurfaceField.text("selected", state.selectedDateMillis?.let { state.calendar.format(it, "yMMMd") }, editable = false),
                SurfaceField.choice("entry", state.entry.name, DateEntry.entries.map { it.name }),
            ),
            appearance, Appearance.DatePicker,
        ),
    ) { edits ->
        edits.value("entry")?.let { chosen -> DateEntry.entries.firstOrNull { it.name == chosen } }?.let { if (it != state.entry) state.entry = it }
        DrawnDatePicker(state, edits.modifier, edits.appearance(appearance))
    }
}

@Composable
private fun DrawnDatePicker(state: DatePickerState, modifier: Modifier, appearance: DatePickerAppearance) =
    Box(modifier, propagateMinConstraints = true) {
        appearance.Content(DatePickerProperties(state.calendar), state, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)
    }

/** The calendar a date picker's appearance writes its words in. */
@Immutable
data class DatePickerProperties(val calendar: CalendarText)

typealias DatePickerAppearance = ComposeAppearance<DatePickerProperties, DatePickerState, Unit>

/** The two ways of entering the day: tapping it in a month's calendar, or typing its digits. */
enum class DateEntry { Calendar, Typed }

/** Which days a picker lets someone choose. Every day, unless told otherwise. */
@Stable
interface SelectableDates {
    fun isSelectableDate(utcDay: Long): Boolean = true

    fun isSelectableYear(year: Int): Boolean = true
}

/** Every day of every year. */
object AllDates : SelectableDates

/** The years a picker offers when not told: 1900 to 2100, as Material's does. */
val DatePickerYears: IntRange = 1900..2100

/**
 * A date picker's state: the chosen day (a UTC day, or null), the month on show, the way of entering
 * the day that is showing, the [years] on offer and which days can be chosen.
 *
 * A chosen day outside [years] is no choice at all. Switching [entry] brings the chosen day's month
 * into view. The month on show must lie within [years].
 */
@Stable
class DatePickerState(
    val calendar: CalendarText,
    initialSelectedDateMillis: Long? = null,
    initialDisplayedMonthMillis: Long? = initialSelectedDateMillis,
    val years: IntRange = DatePickerYears,
    initialEntry: DateEntry = DateEntry.Calendar,
    selectable: SelectableDates = AllDates,
) {
    var selectable: SelectableDates by mutableStateOf(selectable)

    private var chosen by mutableStateOf(initialSelectedDateMillis?.let(::withinYears))

    /** The chosen day, as its start in UTC, or null when none is. */
    var selectedDateMillis: Long?
        get() = chosen
        set(value) {
            chosen = value?.let(::withinYears)
        }

    private var month by mutableStateOf(
        if (initialDisplayedMonthMillis != null) CalendarMonth.of(initialDisplayedMonthMillis, calendar.firstDayOfWeek).also {
            require(it.year in years) { "The initial month's year (${it.year}) is outside $years." }
        } else CalendarMonth.of(calendar.today(), calendar.firstDayOfWeek),
    )

    /** The month on show, as the start in UTC of its first day. */
    var displayedMonthMillis: Long
        get() = month.startUtcMillis
        set(value) {
            val next = CalendarMonth.of(value, calendar.firstDayOfWeek)
            require(next.year in years) { "The month's year (${next.year}) is outside $years." }
            month = next
        }

    /** The month on show, laid out in this calendar's weeks. */
    val displayedMonth: CalendarMonth get() = month

    private var shown by mutableStateOf(initialEntry)

    /** Which way of entering the day is showing. Switching brings the chosen day's month into view. */
    var entry: DateEntry
        get() = shown
        set(value) {
            selectedDateMillis?.let { displayedMonthMillis = CalendarMonth.of(it, calendar.firstDayOfWeek).startUtcMillis }
            shown = value
        }

    /** Every month [years] holds. */
    val monthCount: Int get() = (years.last - years.first + 1) * 12

    /** The [index]th month of [years], in this calendar's weeks. */
    fun month(index: Int): CalendarMonth = CalendarMonth.at(index, years, calendar.firstDayOfWeek)

    /** Whether [utcDay], in [year], can be chosen. */
    fun canChoose(utcDay: Long, year: Int = CalendarDay.of(utcDay).year): Boolean =
        selectable.isSelectableYear(year) && selectable.isSelectableDate(utcDay)

    /**
     * What typed [digits] (no delimiters) say, read in this calendar's typed form: nothing yet, a
     * day that can be chosen, or why the day can't be.
     */
    fun read(digits: String): TypedDate {
        val pattern = calendar.input.patternWithoutDelimiters
        if (digits.trim().length < pattern.length) return TypedDate.Incomplete
        val day = calendar.parse(digits.trim(), pattern)?.let(CalendarDay::of) ?: return TypedDate.Unreadable(calendar.input.patternWithDelimiters.uppercase())
        if (day.year !in years) return TypedDate.OutsideYears(years)
        if (!canChoose(day.utcMillis, day.year)) return TypedDate.NotAllowed(day.utcMillis)
        return TypedDate.Chosen(day.utcMillis)
    }

    private fun withinYears(utcMillis: Long): Long? = CalendarDay.of(utcMillis).takeIf { it.year in years }?.utcMillis

    companion object {
        /** Keeps the chosen day, the month, the years and the way of entry across a configuration change. */
        fun Saver(calendar: CalendarText, selectable: SelectableDates): Saver<DatePickerState, Any> = listSaver(
            save = { listOf(it.selectedDateMillis, it.displayedMonthMillis, it.years.first, it.years.last, it.entry.ordinal) },
            restore = { value ->
                DatePickerState(
                    calendar, value[0] as Long?, value[1] as Long?, (value[2] as Int)..(value[3] as Int),
                    DateEntry.entries[value[4] as Int], selectable,
                )
            },
        )
    }
}

/** What a typed date says. */
@Immutable
sealed interface TypedDate {
    /** Fewer digits than the typed form needs. */
    data object Incomplete : TypedDate

    /** The digits name no day in the typed form, shown as [pattern] (MM/DD/YYYY). */
    data class Unreadable(val pattern: String) : TypedDate

    /** A day outside the years on offer. */
    data class OutsideYears(val years: IntRange) : TypedDate

    /** A day that can't be chosen. */
    data class NotAllowed(val utcDay: Long) : TypedDate

    /** A day that can be chosen. */
    data class Chosen(val utcDay: Long) : TypedDate
}

/** A date picker's state, kept across configuration changes, in the device's calendar. */
@Composable
fun rememberDatePickerState(
    initialSelectedDateMillis: Long? = null,
    initialDisplayedMonthMillis: Long? = initialSelectedDateMillis,
    years: IntRange = DatePickerYears,
    initialEntry: DateEntry = DateEntry.Calendar,
    selectable: SelectableDates = AllDates,
): DatePickerState {
    val calendar = rememberCalendarText()
    return rememberSaveable(calendar, saver = DatePickerState.Saver(calendar, selectable)) {
        DatePickerState(calendar, initialSelectedDateMillis, initialDisplayedMonthMillis, years, initialEntry, selectable)
    }.apply { this.selectable = selectable }
}

/**
 * The month on show as a grid of day numbers under the month's name, with buttons for the months
 * either side; typed entry is a plain field for the digits.
 */
val BareDatePicker: DatePickerAppearance = object : DatePickerAppearance {
    @Composable
    override fun Content(properties: DatePickerProperties, state: DatePickerState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        val calendar = properties.calendar
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.entry == DateEntry.Typed) {
                var digits by rememberSaveable { mutableStateOf(state.selectedDateMillis?.let { calendar.pattern(it, calendar.input.patternWithoutDelimiters) }.orEmpty()) }
                BasicTextField(
                    digits,
                    { typed ->
                        if (typed.length <= calendar.input.patternWithoutDelimiters.length && typed.all(Char::isDigit)) {
                            digits = typed
                            state.selectedDateMillis = (state.read(typed) as? TypedDate.Chosen)?.utcDay
                        }
                    },
                    Modifier.fillMaxWidth().border(1.dp, Color.Gray).padding(8.dp).semantics { contentDescription = calendar.input.patternWithDelimiters.uppercase() },
                    singleLine = true,
                )
                return@Column
            }
            val month = state.displayedMonth
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(calendar.format(month.startUtcMillis, "yMMMM"), Modifier.weight(1f))
                val index = month.indexIn(state.years)
                if (index > 0) BasicText("‹", Modifier.size(48.dp).clickable(role = Role.Button) { state.displayedMonthMillis = state.month(index - 1).startUtcMillis })
                if (index < state.monthCount - 1) BasicText("›", Modifier.size(48.dp).clickable(role = Role.Button) { state.displayedMonthMillis = state.month(index + 1).startUtcMillis })
            }
            val today = calendar.today()
            for (week in 0 until 6) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (cell in 0 until 7) {
                        val dayNumber = week * 7 + cell - month.daysFromStartOfWeekToFirstOfMonth + 1
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            if (dayNumber in 1..month.numberOfDays) {
                                val day = month.day(dayNumber)
                                val chosen = day == state.selectedDateMillis
                                BasicText(
                                    calendar.number(dayNumber),
                                    Modifier.size(40.dp)
                                        .background(if (chosen) Color.Black.copy(alpha = 0.12f) else Color.Transparent, CircleShape)
                                        .border(if (day == today) 1.dp else 0.dp, if (day == today) Color.Black else Color.Transparent, CircleShape)
                                        .semantics { selected = chosen }
                                        .clickable(enabled = state.canChoose(day, month.year), role = Role.Button) { state.selectedDateMillis = day },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
