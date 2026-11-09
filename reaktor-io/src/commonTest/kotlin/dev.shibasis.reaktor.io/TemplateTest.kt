package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.text.HoleReader
import dev.shibasis.reaktor.io.text.Template
import dev.shibasis.reaktor.io.text.TemplateMatch
import dev.shibasis.reaktor.io.text.TemplateReaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TemplateTest {
    private fun Template.captures(text: String): Map<String, String> {
        val match = match(text)
        assertIs<TemplateMatch.Matched>(match, "\"$text\" against \"$source\": $match")
        return match.captures.associate { it.name to it.text }
    }

    @Test
    fun literalsIgnoreCaseAndSpacing() {
        val template = Template("Your A/C {account} is debited")
        assertEquals(mapOf("account" to "XX1234"), template.captures("your a/c   XX1234\nIS DEBITED"))
    }

    @Test
    fun aSpaceNeedsWhitespace() {
        val template = Template("Rs {amount:number}")
        assertIs<TemplateMatch.Missed>(template.match("Rs250"))
        assertEquals(mapOf("amount" to "250"), Template("Rs[ ]{amount:number}").captures("Rs250"))
    }

    @Test
    fun lazyTextStopsWhereTheRestCanMatch() {
        val template = Template("To {payee} On {date:word}")
        assertEquals(mapOf("payee" to "THE ON CAFE", "date" to "09/11/26"), template.captures("To THE ON CAFE On 09/11/26"))
    }

    @Test
    fun optionalsAndChoices() {
        val template = Template("[Rs.|INR|₹][ ]{amount:number} [is ]debited")
        assertEquals("250.00", template.captures("Rs. 250.00 is debited")["amount"])
        assertEquals("250", template.captures("INR 250 debited")["amount"])
        assertEquals("1,499", template.captures("₹1,499 debited")["amount"])
        assertEquals("75", template.captures("75 debited")["amount"])
    }

    @Test
    fun theWholeTextUnlessItSaysOtherwise() {
        assertIs<TemplateMatch.Missed>(Template("Paid {amount:number}").match("Paid 250 to Swiggy"))
        assertEquals(mapOf("amount" to "250"), Template("Paid {amount:number}{*}").captures("Paid 250 to Swiggy"))
        assertEquals(mapOf("amount" to "250"), Template("{*}paid {amount:number}{*}").captures("Hi! You paid 250 to Swiggy"))
    }

    @Test
    fun builtInReaders() {
        val template = Template("{n:number} {d:digits} {w:word} {t}")
        assertEquals(
            mapOf("n" to "1,23,456.78", "d" to "431234567890", "w" to "UPI/P2M", "t" to "rest of it"),
            template.captures("1,23,456.78 431234567890 UPI/P2M rest of it"),
        )
    }

    @Test
    fun appReaders() {
        val masked = HoleReader { text, from -> TemplateReaders.greedy(text, from) { it.isDigit() || it == 'X' || it == '*' } }
        val readers = TemplateReaders.Builtin.with("account", masked)
        val template = Template("A/C {account} debited{*}", readers)
        assertEquals(mapOf("account" to "XX1234"), template.captures("A/C XX1234 debited by 250"))
        assertIs<TemplateMatch.Missed>(template.match("A/C 12-34 debited"))
    }

    @Test
    fun aMissSaysWhereAndWhat() {
        val miss = Template("Sent {amount:number} From HDFC Bank{*}").match("Sent 250 from SBI Bank")
        assertIs<TemplateMatch.Missed>(miss)
        assertEquals(8, miss.at)
        assertEquals("\"From HDFC Bank\"", miss.expected)
    }

    @Test
    fun escapes() {
        assertEquals(mapOf("x" to "1"), Template("""\{\[a\|b\]\} {x:digits}""").captures("{[a|b]} 1"))
    }

    @Test
    fun badTemplatesFailWhenMade() {
        assertFailsWith<IllegalArgumentException> { Template("{amount:nothing}") }
        assertFailsWith<IllegalArgumentException> { Template("{amount") }
        assertFailsWith<IllegalArgumentException> { Template("[a|b") }
        assertFailsWith<IllegalArgumentException> { Template("a]b") }
        assertFailsWith<IllegalArgumentException> { Template("{}") }
    }

    @Test
    fun aRunawayTemplateStops() {
        val template = Template("{a} {b} {c} {d} {e} end")
        val text = (1..400).joinToString(" ") { "w" }
        val miss = template.match(text, maxSteps = 10_000)
        assertIs<TemplateMatch.Missed>(miss)
        assertTrue("step limit" in miss.expected, miss.expected)
    }

    @Test
    fun holesAreListed() {
        assertEquals(listOf("amount", "payee", "ref"), Template("{amount} to {payee}[ ref {ref}]{*}").holes)
    }
}
