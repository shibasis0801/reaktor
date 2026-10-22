package dev.shibasis.reaktor.io

import dev.shibasis.reaktor.io.xml.XmlReader
import dev.shibasis.reaktor.io.xml.XmlToken
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XmlReaderTest {

    /** Every token, rendered compactly: `<name a=v>`, `</name>`, `"text"`. */
    private fun tokens(xml: String): List<String> = render(XmlReader(xml))

    private fun render(reader: XmlReader): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            when (reader.next()) {
                XmlToken.StartElement -> out += "<" + reader.name +
                    reader.attributes.entries.joinToString("") { " ${it.key}=${it.value}" } + ">"
                XmlToken.EndElement -> out += "</${reader.name}>"
                XmlToken.Text -> out += "\"${reader.text}\""
                XmlToken.EndDocument -> return out
            }
        }
    }

    @Test
    fun readsAnRssItem() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><item>
              <title>Episode 1</title>
              <enclosure url="https://example.com/a.mp3?x=1&amp;y=2" length="123" type="audio/mpeg"/>
            </item></channel></rss>
        """.trimIndent()

        assertEquals(
            listOf(
                "<rss version=2.0>", "<channel>", "<item>",
                "<title>", "\"Episode 1\"", "</title>",
                "<enclosure url=https://example.com/a.mp3?x=1&y=2 length=123 type=audio/mpeg>", "</enclosure>",
                "</item>", "</channel>", "</rss>",
            ),
            tokens(xml),
        )
    }

    @Test
    fun mergesCdataWithTheTextAroundIt() {
        assertEquals(
            listOf("<d>", "\"before <b>bold</b> after\"", "</d>"),
            tokens("<d>before <![CDATA[<b>bold</b>]]> after</d>"),
        )
    }

    @Test
    fun cdataRightBeforeTheEndTagIsKept() {
        assertEquals(listOf("<d>", "\"<p>notes</p>\"", "</d>"), tokens("<d><![CDATA[<p>notes</p>]]></d>"))
    }

    @Test
    fun aCdataSectionEndsAtItsOwnTerminatorEvenAfterExtraBrackets() {
        assertEquals(listOf("<d>", "\"a]\"", "</d>"), tokens("<d><![CDATA[a]]]></d>"))
    }

    @Test
    fun decodesXmlNumericAndHtmlEntities() {
        assertEquals(
            listOf("<t>", "\"It’s 5 < 6 & café — © 🎙\"", "</t>"),
            tokens("<t>It&rsquo;s 5 &lt; 6 &amp; caf&eacute; &mdash; &#169; &#x1F399;</t>"),
        )
    }

    @Test
    fun keepsWhatIsNotAnEntityAsWritten() {
        assertEquals(
            listOf("<t>", "\"Tom & Jerry &bogus; AT&T &\"", "</t>"),
            tokens("<t>Tom & Jerry &bogus; AT&T &</t>"),
        )
    }

    @Test
    fun aLoneLessThanIsText() {
        assertEquals(listOf("<t>", "\"a < b\"", "</t>"), tokens("<t>a < b</t>"))
    }

    @Test
    fun skipsCommentsProcessingInstructionsAndDoctype() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [ <!ENTITY x "y"> ]>
            <!-- generated -->
            <rss><!-- inside --><a>1<!-- mid -->2</a></rss>
        """.trimIndent()
        assertEquals(listOf("<rss>", "<a>", "\"12\"", "</a>", "</rss>"), tokens(xml))
    }

    @Test
    fun anEndTagClosesWhateverWasLeftOpenInsideIt() {
        assertEquals(
            listOf("<item>", "<title>", "\"x\"", "<br>", "</br>", "</title>", "</item>"),
            tokens("<item><title>x<br></title></item>"),
        )
    }

    @Test
    fun anEndTagMatchingNothingIsIgnored() {
        assertEquals(listOf("<a>", "\"x\"", "</a>"), tokens("<a>x</b></a>"))
    }

    @Test
    fun whateverIsStillOpenAtTheEndIsClosed() {
        assertEquals(listOf("<a>", "<b>", "\"x\"", "</b>", "</a>"), tokens("<a><b>x"))
    }

    @Test
    fun readsSingleQuotedUnquotedAndValuelessAttributes() {
        assertEquals(
            listOf("<e a=1 b=two c=>", "</e>"),
            tokens("<e a='1' b=two c/>"),
        )
    }

    @Test
    fun resolvesPrefixesThroughDeclarationsInScope() {
        val xml = """
            <rss xmlns:it="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel><it:duration>12:34</it:duration><plain/></channel>
            </rss>
        """.trimIndent()
        val reader = XmlReader(xml)
        val seen = mutableListOf<String>()
        while (reader.next() != XmlToken.EndDocument) {
            if (reader.token == XmlToken.StartElement) seen += "${reader.localName}@${reader.namespace}"
        }
        assertEquals(
            listOf("rss@", "channel@", "duration@http://www.itunes.com/dtds/podcast-1.0.dtd", "plain@"),
            seen,
        )
    }

    @Test
    fun readTextGathersNestedTextAndStopsOnTheEndTag() {
        val reader = XmlReader("<item><description>a <b>b</b> c</description><title>t</title></item>")
        reader.next() // item
        reader.next() // description
        assertEquals("a b c", reader.readText())
        assertEquals(XmlToken.EndElement, reader.token)
        assertEquals("description", reader.name)
        reader.next()
        assertEquals("title", reader.name)
    }

    @Test
    fun skipLeavesTheReaderOnTheMatchingEndTag() {
        val reader = XmlReader("<a><skip><x>1</x><y/></skip><keep>2</keep></a>")
        reader.next() // a
        reader.next() // skip
        reader.skip()
        assertEquals("skip", reader.name)
        reader.next()
        assertEquals("keep", reader.name)
        assertEquals("2", reader.readText())
    }

    @Test
    fun attributesAreOnlyReportedOnTheirStartTag() {
        val reader = XmlReader("<a href=\"x\">t</a>")
        reader.next()
        assertEquals("x", reader.attribute("href"))
        reader.next()
        assertNull(reader.attribute("href"))
    }

    @Test
    fun readsUtf8FromAByteSourceIncludingABomAndAstralCharacters() {
        val buffer = Buffer()
        buffer.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        buffer.writeString("<t>café 🎙</t>")
        assertEquals(listOf("<t>", "\"café 🎙\"", "</t>"), render(XmlReader(buffer)))
    }
}
