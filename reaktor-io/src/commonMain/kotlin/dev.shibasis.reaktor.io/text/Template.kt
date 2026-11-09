package dev.shibasis.reaktor.io.text

/**
 * Text with named, typed holes, matched against messages someone else's software writes: a bank's
 * transaction alert, a spoken command, a subject line.
 *
 * ```
 * Sent {amount} From HDFC Bank A/C[ ]{account} To {payee} On {date} Ref {ref}{*}
 * ```
 *
 * - **Literal text** matches without regard to case. A space matches one or more whitespace
 *   characters, line breaks included.
 * - **`{name}`** is a hole read by the reader called `name`, or by `text` if there is none.
 *   **`{name:reader}`** names the reader. **`{*}`** is any text, possibly none, not captured.
 * - **`[x]`** is optional; **`[x|y]`** is one of `x`, `y` or neither.
 * - `\{`, `\[`, `\]`, `\|` and `\\` are those characters.
 *
 * A template matches the whole text (after trimming): put `{*}` where a message may go on.
 *
 * Matching is a hand-written scanner over characters, with backtracking for holes that can end in
 * more than one place, and a step limit so no message can make it run away. There is deliberately
 * no regex: Kotlin/JS compiles patterns with a different engine, and a pattern that is fine on the
 * JVM can throw when its file loads in a browser. This behaves the same on every target.
 */
class Template(val source: String, readers: TemplateReaders = TemplateReaders.Builtin) {
    private val pieces: List<Piece> = TemplateSyntax(source.trim(), readers).parse()

    /** Every hole's name, in order. */
    val holes: List<String> = pieces.flatMap { it.holeNames() }

    fun match(text: String, maxSteps: Int = DEFAULT_STEPS): TemplateMatch =
        Matcher(text.trim(), maxSteps).run(pieces)

    override fun toString() = source

    companion object {
        const val DEFAULT_STEPS = 200_000
    }
}

sealed interface TemplateMatch {
    data class Matched(val captures: List<Capture>) : TemplateMatch {
        operator fun get(name: String): String? = captures.firstOrNull { it.name == name }?.text
    }

    /**
     * No way through. [at] is the furthest point in the text any attempt reached, and [expected]
     * what it wanted to find there, which is what a person fixing a template wants to see.
     */
    data class Missed(val at: Int, val expected: String) : TemplateMatch
}

/** One hole's text, trimmed, and where it sat in the matched text. */
data class Capture(val name: String, val text: String, val start: Int, val end: Int)

/**
 * Reads a hole starting at [from]: every place the hole could end, most likely first. An empty
 * sequence means the hole cannot start here.
 */
fun interface HoleReader {
    fun ends(text: CharSequence, from: Int): Sequence<Int>
}

/** The readers a template's holes may name. */
class TemplateReaders private constructor(private val readers: Map<String, HoleReader>) {
    operator fun get(name: String): HoleReader? = readers[name]

    fun with(name: String, reader: HoleReader): TemplateReaders {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) { "Reader names are letters, digits and _" }
        return TemplateReaders(readers + (name to reader))
    }

    companion object {
        /** Any text, at least one character, as short as will let the rest match. */
        val Text = HoleReader { text, from -> ((from + 1)..text.length).asSequence() }

        /** Any text including none, as short as will let the rest match. */
        val Any = HoleReader { text, from -> (from..text.length).asSequence() }

        /** One or more digits, all of them. */
        val Digits = HoleReader { text, from -> greedy(text, from) { it.isDigit() } }

        /** One or more characters that are not whitespace, all of them. */
        val Word = HoleReader { text, from -> greedy(text, from) { !it.isWhitespace() } }

        /** Digits with `,` or `.` between them, starting and ending on a digit: `1,23,456.78`. */
        val Number = HoleReader { text, from ->
            if (from >= text.length || !text[from].isDigit()) return@HoleReader emptySequence()
            var end = from
            while (end < text.length) {
                val c = text[end]
                val inner = (c == ',' || c == '.') && end + 1 < text.length && text[end + 1].isDigit()
                if (c.isDigit() || inner) end++ else break
            }
            sequenceOf(end)
        }

        val Builtin = TemplateReaders(
            mapOf("text" to Text, "digits" to Digits, "word" to Word, "number" to Number),
        )

        /** All of the run of characters from [from] that satisfy [accept], if there is at least one. */
        fun greedy(text: CharSequence, from: Int, accept: (Char) -> Boolean): Sequence<Int> {
            var end = from
            while (end < text.length && accept(text[end])) end++
            return if (end > from) sequenceOf(end) else emptySequence()
        }
    }
}

// --- The template, parsed. -------------------------------------------------------------------

internal sealed interface Piece {
    fun holeNames(): List<String> = emptyList()

    /** Literal text; each space stands for one or more whitespace characters. */
    data class Literal(val text: String) : Piece

    data class Hole(val name: String, val reader: HoleReader, val captured: Boolean) : Piece {
        override fun holeNames() = if (captured) listOf(name) else emptyList()
    }

    /** One of [choices], or nothing. */
    data class Optional(val choices: List<List<Piece>>) : Piece {
        override fun holeNames() = choices.flatMap { choice -> choice.flatMap { it.holeNames() } }
    }
}

private class TemplateSyntax(private val source: String, private val readers: TemplateReaders) {
    private var at = 0

    fun parse(): List<Piece> {
        val pieces = sequence(stopAt = emptySet())
        require(at == source.length) { "Unexpected '${source[at]}' at $at in: $source" }
        return pieces
    }

    private fun sequence(stopAt: Set<Char>): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) pieces += Piece.Literal(collapse(literal.toString()))
            literal.clear()
        }
        while (at < source.length) {
            val c = source[at]
            when {
                c in stopAt -> break
                c == '\\' -> {
                    require(at + 1 < source.length) { "A template cannot end with \\: $source" }
                    literal.append(source[at + 1]); at += 2
                }
                c == '{' -> { flush(); pieces += hole() }
                c == '[' -> { flush(); pieces += optional() }
                c == '}' || c == ']' || c == '|' -> throw IllegalArgumentException("Unexpected '$c' at $at in: $source")
                else -> { literal.append(c); at++ }
            }
        }
        flush()
        return pieces
    }

    private fun hole(): Piece.Hole {
        val close = source.indexOf('}', at)
        require(close > at) { "Unclosed { at $at in: $source" }
        val body = source.substring(at + 1, close).trim()
        at = close + 1
        if (body == "*") return Piece.Hole("*", TemplateReaders.Any, captured = false)
        val name = body.substringBefore(':').trim()
        val readerName = if (':' in body) body.substringAfter(':').trim() else null
        require(name.isNotEmpty()) { "A hole needs a name: $source" }
        val reader = if (readerName != null) {
            requireNotNull(readers[readerName]) { "No reader called '$readerName' for {$body} in: $source" }
        } else {
            readers[name] ?: TemplateReaders.Text
        }
        return Piece.Hole(name, reader, captured = true)
    }

    private fun optional(): Piece.Optional {
        at++ // [
        val choices = mutableListOf<List<Piece>>()
        while (true) {
            choices += sequence(stopAt = setOf('|', ']'))
            require(at < source.length) { "Unclosed [ in: $source" }
            val c = source[at++]
            if (c == ']') break
        }
        return Piece.Optional(choices)
    }

    private fun collapse(text: String): String {
        val out = StringBuilder()
        var space = false
        for (c in text) {
            if (c.isWhitespace()) space = true
            else {
                if (space) out.append(' ')
                space = false
                out.append(c)
            }
        }
        if (space) out.append(' ')
        return out.toString()
    }
}

// --- Matching. -------------------------------------------------------------------------------

private class Matcher(private val text: String, private val maxSteps: Int) {
    private var steps = 0
    private var furthest = 0
    private var expectedThere = "the start"
    private val captures = ArrayList<Capture>()

    fun run(pieces: List<Piece>): TemplateMatch {
        val done = try {
            match(Chain.of(pieces), 0)
        } catch (_: OutOfSteps) {
            false
        }
        if (done) return TemplateMatch.Matched(captures.toList())
        val expected = if (steps >= maxSteps) "a simpler template (step limit reached)" else expectedThere
        return TemplateMatch.Missed(furthest, expected)
    }

    /** A persistent list of pieces still to match, so optional branches can splice cheaply. */
    private class Chain(val piece: Piece, val next: Chain?) {
        companion object {
            fun of(pieces: List<Piece>, tail: Chain? = null): Chain? =
                pieces.foldRight(tail) { piece, rest -> Chain(piece, rest) }
        }
    }

    private object OutOfSteps : RuntimeException()

    private fun reached(position: Int, expected: String) {
        if (position >= furthest) {
            furthest = position
            expectedThere = expected
        }
    }

    private fun match(chain: Chain?, position: Int): Boolean {
        if (++steps > maxSteps) throw OutOfSteps
        if (chain == null) {
            if (position == text.length) return true
            reached(position, "the end of the text")
            return false
        }
        return when (val piece = chain.piece) {
            is Piece.Literal -> {
                val end = literal(piece.text, position)
                if (end == null) { reached(position, "\"${piece.text.trim()}\""); false }
                else match(chain.next, end)
            }
            is Piece.Optional -> {
                piece.choices.any { choice -> match(Chain.of(choice, chain.next), position) } ||
                    match(chain.next, position)
            }
            is Piece.Hole -> {
                for (end in piece.reader.ends(text, position)) {
                    if (end < position || end > text.length) continue
                    val mark = captures.size
                    if (piece.captured) captures += capture(piece.name, position, end)
                    if (match(chain.next, end)) return true
                    while (captures.size > mark) captures.removeAt(captures.lastIndex)
                    if (++steps > maxSteps) throw OutOfSteps
                }
                reached(position, if (piece.captured) "{${piece.name}}" else "more text")
                false
            }
        }
    }

    /** Where [literal] ends if it matches at [from]; spaces in it take one or more whitespace. */
    private fun literal(literal: String, from: Int): Int? {
        var i = from
        for (c in literal) {
            if (c == ' ') {
                if (i >= text.length || !text[i].isWhitespace()) return null
                while (i < text.length && text[i].isWhitespace()) i++
            } else {
                if (i >= text.length || text[i].lowercaseChar() != c.lowercaseChar()) return null
                i++
            }
        }
        return i
    }

    private fun capture(name: String, start: Int, end: Int): Capture {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return Capture(name, text.substring(s, e), s, e)
    }
}
