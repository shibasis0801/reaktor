package dev.shibasis.reaktor.tooling.query

class SearchQuery(raw: String) {
    private class Term(val key: String?, val value: String, val negated: Boolean, val pattern: Regex?)

    private val terms: List<Term> = Token.findAll(raw).map { it.value }.filter { it != "-" }.map { token ->
        val negated = token.startsWith('-')
        val body = token.removePrefix("-")
        val key = body.substringBefore(':', "").lowercase().takeIf { it.isNotBlank() && it.all { c -> c.isLetter() || c == '-' } }
        val value = (if (key != null) body.substringAfter(':') else body).unquoted()
        val pattern = value.takeIf { it.startsWith('~') && it.length > 1 }?.let { runCatching { Regex(it.drop(1), RegexOption.IGNORE_CASE) }.getOrNull() }
        Term(key, if (pattern != null) value.drop(1) else value, negated, pattern)
    }.toList()

    val blank: Boolean get() = terms.isEmpty()

    fun <T> mapTerms(convert: (key: String?, value: String, negated: Boolean, pattern: Regex?) -> T): List<T> =
        terms.map { convert(it.key, it.value, it.negated, it.pattern) }

    fun matches(text: String, field: (key: String, value: String, pattern: Regex?) -> Boolean?): Boolean = terms.all { term ->
        val hit = if (term.key == null) {
            term.pattern?.containsMatchIn(text) ?: text.contains(term.value, ignoreCase = true)
        } else {
            field(term.key, term.value, term.pattern) ?: text.contains("${term.key}:${term.value}", ignoreCase = true)
        }
        hit != term.negated
    }
}

private val Token = Regex("""(?:[^\s"]|"(?:[^"\\]|\\.)*"?)+""")
private val Escape = Regex("""\\(.)""")

private fun String.unquoted(): String =
    if (startsWith('"')) drop(1).removeSuffix("\"").replace(Escape) { it.groupValues[1] } else this
