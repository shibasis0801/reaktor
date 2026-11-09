package dev.shibasis.reaktor.core.i18n

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Each platform's formatter through the same assertions, on CLDR's own words. */
class DateFormatsTest {
    private val ninth = LocalDate(2026, 11, 9)

    private fun String.spaces() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun monthsInTheirOwnLanguage() {
        assertEquals("November", dateFormats("en-IN").month(11))
        assertEquals("Noviembre", dateFormats("es-ES").month(11))
        assertEquals("November 2026", dateFormats("en-US").monthYear(2026, 11))
    }

    @Test
    fun dayAndMonthInTheLocalesOrder() {
        assertEquals("9 November", dateFormats("en-IN").dayMonth(ninth))
        assertEquals("November 9", dateFormats("en-US").dayMonth(ninth))
        assertEquals("9. November", dateFormats("de-DE").dayMonth(ninth))
    }

    @Test
    fun theLocalesClock() {
        assertEquals("22:37", dateFormats("de-DE").time(LocalTime(22, 37)))
        assertEquals("10:37 PM", dateFormats("en-US").time(LocalTime(22, 37)).spaces().uppercase())
    }

    @Test
    fun alwaysGregorianInTheLocalesDigits() {
        // ar-SA defaults to the Islamic calendar on every platform; a ledger's dates are Gregorian.
        val saudi = dateFormats("ar-SA").monthYear(2026, 11)
        assertTrue(saudi.contains("٢٠٢٦") || saudi.contains("2026"), saudi)
        val egypt = dateFormats("ar-EG").dayMonth(ninth)
        assertTrue(egypt.contains("٩"), egypt)
    }

    @Test
    fun relativeDaysWhereThePlatformHasWords() {
        val english = dateFormats("en-IN")
        val today = english.relativeDay(0)
        val yesterday = english.relativeDay(-1)
        if (today != null) {
            assertEquals("Today", today)
            assertEquals("Yesterday", yesterday)
        }
        assertEquals(null, english.relativeDay(-5))
        assertNotEquals("", english.date(ninth))
    }
}
