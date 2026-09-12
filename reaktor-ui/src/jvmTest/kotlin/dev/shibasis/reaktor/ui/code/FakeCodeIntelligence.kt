package dev.shibasis.reaktor.ui.code

import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodeCompletionKind
import dev.shibasis.reaktor.code.CodeDiagnostic
import dev.shibasis.reaktor.code.CodeHover
import dev.shibasis.reaktor.code.CodeIntelligence
import dev.shibasis.reaktor.code.CodeIntelligenceStatus
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Stands in for a language server so the editor's side of the contract is exercised without one. */
class FakeCodeIntelligence : CodeIntelligence {
override val status = CodeIntelligenceStatus("Fake LSP", available = true, detail = "test double")
val published = MutableStateFlow<List<CodeDiagnostic>>(emptyList())
var opened: CodeSource? = null
var lastChange: CodeSource? = null
var closed: String? = null

override suspend fun opened(source: CodeSource) { opened = source }
override suspend fun changed(source: CodeSource) { lastChange = source }
override suspend fun closed(uri: String) { closed = uri }
override suspend fun hover(uri: String, at: CodePosition) = CodeHover("val answer: Int")
override fun diagnostics(uri: String): Flow<List<CodeDiagnostic>> = published
override suspend fun completions(uri: String, at: CodePosition) = listOf(
    CodeCompletion("valueFromServer", kind = CodeCompletionKind.Property, detail = "Int"),
)
}
