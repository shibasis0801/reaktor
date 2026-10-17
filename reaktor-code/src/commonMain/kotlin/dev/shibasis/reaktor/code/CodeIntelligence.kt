package dev.shibasis.reaktor.code

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Zero-based line and UTF-16 column. This is the Language Server Protocol's position model on
 * purpose: the editor, the gutter and every language server agree without a conversion step.
 */
data class CodePosition(val line: Int, val column: Int) : Comparable<CodePosition> {
    override fun compareTo(other: CodePosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)

    override fun toString() = "${line + 1}:${column + 1}"

    companion object {
        val Start = CodePosition(0, 0)
    }
}

data class CodeSpan(val start: CodePosition, val end: CodePosition) {
    val empty get() = start == end

    /** Endpoints in document order, so a selection dragged upwards still reads as a range. */
    val ordered get() = if (start <= end) this else CodeSpan(end, start)

    val singleLine get() = start.line == end.line

    operator fun contains(position: CodePosition) =
        ordered.let { position >= it.start && position <= it.end }

    companion object {
        fun at(position: CodePosition) = CodeSpan(position, position)

        fun of(line: Int, from: Int, to: Int) = CodeSpan(CodePosition(line, from), CodePosition(line, to))
    }
}

enum class CodeSeverity { Error, Warning, Information, Hint }

data class CodeDiagnostic(
    val span: CodeSpan,
    val message: String,
    val severity: CodeSeverity = CodeSeverity.Error,
    val source: String? = null,
    val code: String? = null,
)

enum class CodeCompletionKind {
    Text, Method, Function, Constructor, Field, Variable, Class, Interface, Module, Property,
    Value, Enum, Keyword, Snippet, File, Reference, Folder, EnumMember, Constant, Struct,
    Event, Operator, TypeParameter;

    /** The one- or two-letter sigil the completion list shows in place of an icon font. */
    val sigil: String
        get() = when (this) {
            Method, Function, Constructor -> "fn"
            Field, Property -> "pr"
            Variable, Value -> "va"
            Class, Struct -> "cl"
            Interface -> "in"
            Enum, EnumMember -> "en"
            Keyword -> "kw"
            Module, File, Folder -> "mo"
            Constant -> "co"
            TypeParameter -> "tp"
            Snippet -> "sn"
            else -> "id"
        }
}

data class CodeCompletion(
    val label: String,
    val insert: String = label,
    val kind: CodeCompletionKind = CodeCompletionKind.Text,
    val detail: String? = null,
    val documentation: String? = null,
    val sortText: String? = null,
    /** Replaced when the item is accepted. Null means "the identifier under the caret". */
    val replaces: CodeSpan? = null,
) {
    val order get() = sortText ?: label
}

data class CodeHover(val text: String, val span: CodeSpan? = null)

data class CodeLocation(val uri: String, val span: CodeSpan)

/** A document as the intelligence layer sees it: identity, language and current text. */
data class CodeSource(
    val uri: String,
    val languageId: String,
    val text: String,
    val version: Int = 0,
)

/**
 * What a person needs to know when intelligence is thinner than they expect. [remedy] is the
 * step that turns the missing half on, so a pane can say why rather than quietly degrading.
 */
data class CodeIntelligenceStatus(
    val name: String,
    val available: Boolean,
    val detail: String,
    val remedy: String? = null,
)

/**
 * Source intelligence for an editing surface. Everything is optional: the editor stays useful
 * against an implementation that answers nothing, and gets richer as a server comes up.
 */
interface CodeIntelligence {
    val status: CodeIntelligenceStatus

    suspend fun opened(source: CodeSource) = Unit

    suspend fun changed(source: CodeSource) = Unit

    suspend fun closed(uri: String) = Unit

    suspend fun completions(uri: String, at: CodePosition): List<CodeCompletion> = emptyList()

    suspend fun hover(uri: String, at: CodePosition): CodeHover? = null

    suspend fun definition(uri: String, at: CodePosition): List<CodeLocation> = emptyList()

    fun diagnostics(uri: String): Flow<List<CodeDiagnostic>> = emptyFlow()

    companion object {
        /** The editor's own behaviour, with no server attached. */
        val None: CodeIntelligence = object : CodeIntelligence {
            override val status = CodeIntelligenceStatus(
                name = "None",
                available = false,
                detail = "Syntax and document completions only.",
                remedy = "Attach a language server for types, diagnostics and go-to-definition.",
            )
        }
    }
}
