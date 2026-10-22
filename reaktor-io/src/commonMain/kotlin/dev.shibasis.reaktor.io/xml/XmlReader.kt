package dev.shibasis.reaktor.io.xml

import kotlinx.io.Source
import kotlinx.io.readCodePointValue

/** What [XmlReader.next] has just moved onto. */
enum class XmlToken { StartElement, EndElement, Text, EndDocument }

/**
 * A streaming, forgiving XML reader: one token at a time, no tree, no dependency.
 *
 * Built for documents written by other people's software — feeds, OPML, sitemaps — where the
 * question is "what does this say", not "is this valid". It reads every well-formed document the
 * way a strict parser would, and where a strict parser would stop, it carries on:
 *
 * - **Entities.** The five XML ones and numeric references, and the HTML names a feed generator
 *   leaves in titles (`&rsquo;`, `&nbsp;`, `&mdash;` …). An entity it does not know, or a bare `&`,
 *   is kept as the literal text it was rather than failing the document.
 * - **Tags.** An end tag closes everything opened inside it that was left open; an end tag that
 *   matches nothing open is ignored; anything still open at the end is closed.
 * - **Attributes.** Single or double quotes, unquoted values, and names with no value.
 *
 * It streams because the documents are large: a long-running podcast feed is 20 MB with three
 * thousand items, and a tree of that is well over a hundred megabytes on a phone. Memory here is
 * one element's attributes and one run of text.
 *
 * Names are kept as written, prefix included (`itunes:duration`), and [namespace] resolves the
 * prefix through the `xmlns` declarations in scope — a feed that binds the iTunes namespace to an
 * unusual prefix still reads as iTunes.
 *
 * Whitespace-only text between elements is not reported. Text is reported with CDATA sections and
 * decoded entities merged into one run.
 *
 * Input is UTF-8. A document declaring another encoding is read as UTF-8 regardless.
 */
class XmlReader private constructor(private val input: CharInput) {

    constructor(text: String) : this(StringInput(text))

    constructor(source: Source) : this(Utf8SourceInput(source))

    /** The token [next] last returned. */
    var token: XmlToken = XmlToken.Text
        private set

    /** The element's name as written, prefix included, on [XmlToken.StartElement] and [XmlToken.EndElement]. */
    var name: String = ""
        private set

    /** The name without its prefix. */
    val localName: String get() = name.substringAfter(':')

    /** The namespace URI the element's prefix resolves to, or "" when nothing declares one. */
    var namespace: String = ""
        private set

    /** The text of a [XmlToken.Text] token, entities decoded, CDATA included. */
    var text: String = ""
        private set

    /** The current start element's attributes, by name as written. Empty on any other token. */
    var attributes: Map<String, String> = emptyMap()
        private set

    /** How many elements enclose the current position. A start element counts itself. */
    val depth: Int get() = open.size

    fun attribute(name: String): String? = attributes[name]

    private class Frame(val name: String, val declarations: Map<String, String>?, val namespace: String)

    private val open = ArrayList<Frame>()

    // End tags owed before reading further: an empty element, or elements an end tag closed for
    // the ones left open inside it.
    private var owedEnds = 0

    private val buffer = StringBuilder()

    /** Moves to the next token and returns it. After [XmlToken.EndDocument], keeps returning it. */
    fun next(): XmlToken {
        attributes = emptyMap()
        text = ""
        if (owedEnds > 0) {
            owedEnds--
            return end()
        }
        while (true) {
            val c = input.read()
            if (c == EOF) {
                return if (open.isNotEmpty()) end() else XmlToken.EndDocument.also { token = it; name = ""; namespace = "" }
            }
            if (c == '<'.code) {
                val step = markup()
                if (step != null) return step
                // A CDATA section opened the run: report it with whatever text follows it.
                if (pendingCdata && readRun()) return XmlToken.Text.also { token = it }
                continue
            }
            input.unread(c)
            if (readRun()) return XmlToken.Text.also { token = it }
        }
    }

    /**
     * From a start element, everything written inside it as one string — the text of nested
     * elements included, their tags not. Leaves the reader on the matching end element.
     */
    fun readText(): String {
        check(token == XmlToken.StartElement) { "readText() needs a start element, not $token" }
        val target = depth
        val out = StringBuilder()
        while (true) {
            when (next()) {
                XmlToken.Text -> out.append(text)
                XmlToken.EndElement -> if (depth < target) return out.toString()
                XmlToken.EndDocument -> return out.toString()
                XmlToken.StartElement -> Unit
            }
        }
    }

    /** From a start element, skips to its matching end element. */
    fun skip() {
        check(token == XmlToken.StartElement) { "skip() needs a start element, not $token" }
        val target = depth
        while (true) {
            when (next()) {
                XmlToken.EndElement -> if (depth < target) return
                XmlToken.EndDocument -> return
                else -> Unit
            }
        }
    }

    private fun end(): XmlToken {
        val frame = open.removeAt(open.lastIndex)
        token = XmlToken.EndElement
        name = frame.name
        namespace = frame.namespace
        return token
    }

    /** After a `<`. Returns the token it produced, or null when it produced nothing to report. */
    private fun markup(): XmlToken? {
        val c = input.read()
        return when {
            c == '/'.code -> endTag()
            c == '!'.code -> { bang(); null }
            c == '?'.code -> { skipPast("?>"); null }
            c != EOF && isNameStart(c) -> { input.unread(c); startTag() }
            else -> {
                // "<" that opens nothing — "a < b" in an unescaped title. It is text.
                input.unread(c)
                input.unread('<'.code)
                if (readRun(literalLt = true)) XmlToken.Text.also { token = it } else null
            }
        }
    }

    private fun startTag(): XmlToken {
        val tagName = readName()
        var declarations: MutableMap<String, String>? = null
        val attrs = LinkedHashMap<String, String>()
        var empty = false
        while (true) {
            skipWhitespace()
            val c = input.read()
            when {
                c == EOF || c == '>'.code -> break
                c == '/'.code -> {
                    val after = input.read()
                    if (after == '>'.code) { empty = true; break }
                    input.unread(after)
                }
                c == '<'.code -> {
                    // A tag that never closed before the next one opened. Close it here.
                    input.unread(c)
                    break
                }
                else -> {
                    input.unread(c)
                    val attrName = readName()
                    if (attrName.isEmpty()) { input.read(); continue }
                    skipWhitespace()
                    val eq = input.read()
                    val value = if (eq == '='.code) { skipWhitespace(); readAttributeValue() } else { input.unread(eq); "" }
                    attrs[attrName] = value
                    if (attrName == "xmlns") {
                        (declarations ?: mutableMapOf<String, String>().also { declarations = it })[""] = value
                    } else if (attrName.startsWith("xmlns:")) {
                        (declarations ?: mutableMapOf<String, String>().also { declarations = it })[attrName.substring(6)] = value
                    }
                }
            }
        }
        val prefix = if (':' in tagName) tagName.substringBefore(':') else ""
        val frame = Frame(tagName, declarations, resolve(prefix, declarations))
        open.add(frame)
        token = XmlToken.StartElement
        name = tagName
        namespace = frame.namespace
        attributes = attrs
        if (empty) owedEnds++
        return token
    }

    private fun endTag(): XmlToken? {
        val tagName = readName()
        skipPast(">")
        val index = open.indexOfLast { it.name == tagName }
        if (index < 0) return null // closes nothing that is open — ignore it
        owedEnds += open.lastIndex - index
        return end()
    }

    /** After `<!`: a comment, a CDATA section (as text), or a declaration such as DOCTYPE. */
    private fun bang() {
        val first = input.read()
        if (first == '-'.code) {
            val second = input.read()
            if (second == '-'.code) { skipPast("-->"); return }
            input.unread(second)
        } else if (first == '['.code) {
            if (matches("CDATA[")) {
                // A CDATA section is text, and text around it belongs to the same run.
                val cdata = readUntil("]]>")
                buffer.setLength(0)
                buffer.append(cdata)
                pendingCdata = true
                return
            }
        } else {
            input.unread(first)
        }
        // A declaration: skip to its closing '>', past any internal subset in brackets.
        var nesting = 0
        while (true) {
            val c = input.read()
            if (c == EOF) return
            if (c == '['.code) nesting++
            if (c == ']'.code && nesting > 0) nesting--
            if (c == '>'.code && nesting == 0) return
        }
    }

    // Set when a CDATA section was just read into the buffer and has not been reported yet.
    private var pendingCdata = false

    /**
     * Reads a run of text up to the next tag, entities decoded, CDATA and comments inside it merged
     * in. Returns whether there was anything other than whitespace to report.
     */
    private fun readRun(literalLt: Boolean = false): Boolean {
        if (!pendingCdata) buffer.setLength(0)
        pendingCdata = false
        var takeLt = literalLt
        while (true) {
            val c = input.read()
            when {
                c == EOF -> break
                c == '<'.code && !takeLt -> {
                    val after = input.read()
                    if (after == '!'.code) {
                        if (matches("[CDATA[")) { buffer.append(readUntil("]]>")); continue }
                        if (matches("--")) { skipPast("-->"); continue }
                    } else if (after != '/'.code && after != '?'.code && (after == EOF || !isNameStart(after))) {
                        // Opens nothing, so it is part of the text: "a < b".
                        input.unread(after)
                        buffer.append('<')
                        continue
                    }
                    input.unread(after)
                    input.unread(c)
                    break
                }
                c == '&'.code -> appendEntity(buffer)
                else -> { appendCode(buffer, c); takeLt = false }
            }
        }
        if (buffer.isBlank()) return false
        text = buffer.toString()
        return true
    }

    private fun readAttributeValue(): String {
        val out = StringBuilder()
        val quote = input.read()
        if (quote == '"'.code || quote == '\''.code) {
            while (true) {
                val c = input.read()
                if (c == EOF || c == quote) break
                if (c == '&'.code) appendEntity(out) else appendCode(out, c)
            }
        } else {
            // Unquoted: up to whitespace or the end of the tag.
            input.unread(quote)
            while (true) {
                val c = input.read()
                if (c == EOF || isWhitespace(c) || c == '>'.code) { input.unread(c); break }
                if (c == '/'.code) {
                    val after = input.read()
                    input.unread(after)
                    if (after == '>'.code) { input.unread(c); break }
                }
                if (c == '&'.code) appendEntity(out) else appendCode(out, c)
            }
        }
        return out.toString()
    }

    /** After `&`: decodes a reference, or writes back exactly what was there when it is not one. */
    private fun appendEntity(out: StringBuilder) {
        val raw = StringBuilder()
        var terminated = false
        while (raw.length < MAX_ENTITY) {
            val c = input.read()
            if (c == ';'.code) { terminated = true; break }
            if (c == EOF || !(isAsciiLetterOrDigit(c) || c == '#'.code)) { input.unread(c); break }
            raw.append(c.toChar())
        }
        val decoded = if (terminated) decode(raw.toString()) else null
        if (decoded != null) {
            appendCode(out, decoded)
        } else {
            out.append('&').append(raw)
            if (terminated) out.append(';')
        }
    }

    private fun decode(entity: String): Int? {
        if (entity.startsWith("#")) {
            val number = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X'))
                entity.substring(2).toIntOrNull(16) else entity.substring(1).toIntOrNull()
            return number?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
        }
        return NamedEntities[entity]
    }

    private fun resolve(prefix: String, own: Map<String, String>?): String {
        own?.get(prefix)?.let { return it }
        for (i in open.lastIndex downTo 0) open[i].declarations?.get(prefix)?.let { return it }
        return if (prefix == "xml") "http://www.w3.org/XML/1998/namespace" else ""
    }

    private fun readName(): String {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == EOF || isWhitespace(c) || c == '>'.code || c == '/'.code || c == '='.code ||
                c == '<'.code || c == '"'.code || c == '\''.code
            ) {
                input.unread(c)
                return out.toString()
            }
            appendCode(out, c)
        }
    }

    private fun skipWhitespace() {
        while (true) {
            val c = input.read()
            if (c == EOF || !isWhitespace(c)) { input.unread(c); return }
        }
    }

    /** Consumes [expected] if the input continues with it; otherwise consumes nothing. */
    private fun matches(expected: String): Boolean {
        val seen = IntArray(expected.length)
        for (i in expected.indices) {
            val c = input.read()
            seen[i] = c
            if (c != expected[i].code) {
                for (j in i downTo 0) input.unread(seen[j])
                return false
            }
        }
        return true
    }

    private fun skipPast(terminator: String) {
        readUntil(terminator, keep = false)
    }

    private fun readUntil(terminator: String, keep: Boolean = true): String {
        // Everything read goes in; the terminator is recognised as the tail of what was read, so
        // "]]]>" ends a CDATA section holding "]" — a partial match is never thrown away.
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == EOF) return out.toString()
            appendCode(out, c)
            if (endsWith(out, terminator)) {
                out.setLength(out.length - terminator.length)
                return out.toString()
            }
            // Skipping, only the tail can matter.
            if (!keep && out.length > terminator.length) out.deleteAt(0)
        }
    }

    private fun endsWith(out: StringBuilder, suffix: String): Boolean {
        if (out.length < suffix.length) return false
        val offset = out.length - suffix.length
        for (i in suffix.indices) if (out[offset + i] != suffix[i]) return false
        return true
    }

    private companion object {
        const val EOF = -1
        const val MAX_ENTITY = 12

        fun isWhitespace(c: Int) = c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code
        fun isAsciiLetterOrDigit(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code
        fun isNameStart(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c == '_'.code ||
            c == ':'.code || c > 0x7F

        fun appendCode(out: StringBuilder, code: Int) {
            if (code < 0x10000) {
                out.append(code.toChar())
            } else {
                val v = code - 0x10000
                out.append((0xD800 + (v shr 10)).toChar())
                out.append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }
}

/**
 * One UTF-16 unit — or, from a byte source, one code point — at a time, with push-back.
 *
 * Push-back is a stack: the reader un-reads at most a handful of characters at once.
 */
private abstract class CharInput {
    private val pushed = IntArray(16)
    private var pushedCount = 0

    fun read(): Int = if (pushedCount > 0) pushed[--pushedCount] else readFresh()

    fun unread(c: Int) {
        if (c == -1) return
        pushed[pushedCount++] = c
    }

    protected abstract fun readFresh(): Int
}

private class StringInput(private val text: String) : CharInput() {
    private var index = 0
    override fun readFresh(): Int = if (index < text.length) text[index++].code else -1
}

/** Decodes UTF-8 as it goes, so a 20 MB document is never a 40 MB string. */
private class Utf8SourceInput(private val source: Source) : CharInput() {
    private var bom = true
    override fun readFresh(): Int {
        if (source.exhausted()) return -1
        val code = source.readCodePointValue()
        if (bom) {
            bom = false
            if (code == 0xFEFF) return readFresh()
        }
        return code
    }
}
