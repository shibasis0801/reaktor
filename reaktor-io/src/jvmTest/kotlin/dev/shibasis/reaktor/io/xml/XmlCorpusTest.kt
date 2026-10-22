package dev.shibasis.reaktor.io.xml

import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [XmlReader] against real documents, checked against the JDK's strict parser.
 *
 * Feeds are other people's copyrighted text, so none is committed. Point `XML_CORPUS` at a folder
 * of `.xml` files to run it — every well-formed one must read exactly as StAX reads it:
 *
 * ```sh
 * XML_CORPUS=~/feeds ./gradlew :reaktor-io:jvmTest --tests '*XmlCorpusTest*'
 * ```
 *
 * Without the variable the test passes without reading anything, and says so.
 */
class XmlCorpusTest {

    @Test
    fun readsEveryWellFormedDocumentTheWayAStrictParserDoes() {
        val folder = System.getenv("XML_CORPUS")?.let(::File)
        if (folder == null || !folder.isDirectory) {
            println("XmlCorpusTest: XML_CORPUS not set, nothing read")
            return
        }
        val files = folder.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val expected = strict(file) ?: continue
            val started = System.nanoTime()
            val actual = ours(file)
            val millis = (System.nanoTime() - started) / 1_000_000
            println("XmlCorpusTest: ${file.name} ${file.length() / 1024} KB, ${actual.elements} elements, ${actual.items} items, $millis ms")
            assertEquals(expected, actual, file.name)
        }
    }

    /** What a document says, in the terms a feed reader cares about. */
    private data class Summary(
        val elements: Int,
        val items: Int,
        val enclosures: List<String>,
        val titles: List<String>,
    )

    private fun strict(file: File): Summary? = runCatching {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_COALESCING, true)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }
        val reader = factory.createXMLStreamReader(file.inputStream(), "UTF-8")
        var elements = 0
        var items = 0
        val enclosures = mutableListOf<String>()
        val titles = mutableListOf<String>()
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            elements++
            val name = reader.localName
            when (name) {
                "item" -> items++
                "enclosure" -> enclosures += reader.getAttributeValue(null, "url").orEmpty()
                "title" -> if (reader.namespaceURI.isNullOrEmpty()) {
                    val (text, nested) = textOf(reader)
                    elements += nested
                    titles += text.trim()
                }
            }
        }
        Summary(elements, items, enclosures, titles)
    }.getOrElse {
        println("XmlCorpusTest: ${file.name} is not well-formed (${it.message}); skipped")
        null
    }

    /** The text inside the current element, and how many elements were nested in it. */
    private fun textOf(reader: javax.xml.stream.XMLStreamReader): Pair<String, Int> {
        val out = StringBuilder()
        var depth = 1
        var nested = 0
        while (depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> { depth++; nested++ }
                XMLStreamConstants.END_ELEMENT -> depth--
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> out.append(reader.text)
            }
        }
        return out.toString() to nested
    }

    private fun ours(file: File): Summary {
        val reader = XmlReader(file.inputStream().asSource().buffered())
        var elements = 0
        var items = 0
        val enclosures = mutableListOf<String>()
        val titles = mutableListOf<String>()
        while (reader.next() != XmlToken.EndDocument) {
            if (reader.token != XmlToken.StartElement) continue
            elements++
            when (reader.localName) {
                "item" -> items++
                "enclosure" -> enclosures += reader.attribute("url").orEmpty()
                "title" -> if (reader.namespace.isEmpty()) {
                    val depth = reader.depth
                    var nested = 0
                    val text = StringBuilder()
                    while (true) {
                        val t = reader.next()
                        if (t == XmlToken.Text) text.append(reader.text)
                        if (t == XmlToken.StartElement) nested++
                        if ((t == XmlToken.EndElement && reader.depth < depth) || t == XmlToken.EndDocument) break
                    }
                    elements += nested
                    titles += text.toString().trim()
                }
            }
        }
        return Summary(elements, items, enclosures, titles)
    }
}
