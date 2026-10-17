package dev.shibasis.reaktor.tooling.lsp

import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodeCompletionKind
import dev.shibasis.reaktor.code.CodeDiagnostic
import dev.shibasis.reaktor.code.CodeHover
import dev.shibasis.reaktor.code.CodeIntelligence
import dev.shibasis.reaktor.code.CodeIntelligenceStatus
import dev.shibasis.reaktor.code.CodeLocation
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSeverity
import dev.shibasis.reaktor.code.CodeSource
import dev.shibasis.reaktor.code.CodeSpan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemCapabilities
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SynchronizationCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A language server launched as a child process and spoken to over stdio. */
data class LanguageServerLaunch(val name: String, val command: List<String>, val origin: String)

/**
 * One LSP session behind reaktor's own [CodeIntelligence] contract. Document sync is full-text:
 * the editor's quick edits are small, and a whole-buffer change cannot desynchronise the server
 * the way a mis-ranged incremental one can.
 */
class LanguageServerSession private constructor(
    private val process: Process,
    private val server: LanguageServer,
    private val listening: Future<*>,
    private val client: SessionClient,
    override val status: CodeIntelligenceStatus,
) : CodeIntelligence, AutoCloseable {

    private val open: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override suspend fun opened(source: CodeSource) {
        if (!alive()) return
        if (!open.add(source.uri)) return changed(source)
        server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(source.uri, source.languageId, source.version, source.text)),
        )
    }

    override suspend fun changed(source: CodeSource) {
        if (!alive() || source.uri !in open) return
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(source.uri, source.version),
                listOf(TextDocumentContentChangeEvent(source.text)),
            ),
        )
    }

    override suspend fun closed(uri: String) {
        if (!alive() || !open.remove(uri)) return
        server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
    }

    override suspend fun completions(uri: String, at: CodePosition): List<CodeCompletion> {
        if (!alive() || uri !in open) return emptyList()
        val params = CompletionParams(TextDocumentIdentifier(uri), at.toLsp())
        val answer = server.textDocumentService.completion(params).awaitOrNull(RequestTimeoutMillis) ?: return emptyList()
        val items = if (answer.isLeft) answer.left else answer.right?.items.orEmpty()
        return items.orEmpty().map { it.toCode() }
    }

    override suspend fun hover(uri: String, at: CodePosition): CodeHover? {
        if (!alive() || uri !in open) return null
        val hover = server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), at.toLsp()))
            .awaitOrNull(RequestTimeoutMillis) ?: return null
        val text = when {
            hover.contents == null -> null
            hover.contents.isRight -> hover.contents.right?.value
            else -> hover.contents.left.orEmpty().joinToString("\n") { part ->
                if (part.isLeft) part.left.orEmpty() else part.right?.value.orEmpty()
            }
        }
        return text?.takeIf { it.isNotBlank() }?.let { CodeHover(it.trim(), hover.range?.toCode()) }
    }

    override suspend fun definition(uri: String, at: CodePosition): List<CodeLocation> {
        if (!alive() || uri !in open) return emptyList()
        val answer = server.textDocumentService
            .definition(DefinitionParams(TextDocumentIdentifier(uri), at.toLsp()))
            .awaitOrNull(RequestTimeoutMillis) ?: return emptyList()
        return when {
            answer.isLeft -> answer.left.orEmpty().map { CodeLocation(it.uri, it.range.toCode()) }
            else -> answer.right.orEmpty().map { CodeLocation(it.targetUri, it.targetSelectionRange.toCode()) }
        }
    }

    override fun diagnostics(uri: String): Flow<List<CodeDiagnostic>> =
        client.published.map { it[uri].orEmpty() }.distinctUntilChanged()

    /** Nothing above this class should have to know the child died; every call degrades to empty. */
    fun alive() = process.isAlive

    fun errorTail(): String = client.stderr.toString()

    override fun close() {
        runCatching { server.shutdown().get(2, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { server.exit() }
        listening.cancel(true)
        process.destroy()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    internal class SessionClient : LanguageClient {
        val published = MutableStateFlow<Map<String, List<CodeDiagnostic>>>(emptyMap())
        val stderr = StringBuilder()

        override fun telemetryEvent(payload: Any?) = Unit

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            published.value = published.value + (params.uri to params.diagnostics.orEmpty().map { it.toCode() })
        }

        override fun showMessage(params: MessageParams?) = Unit

        override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(params: MessageParams?) = Unit

        // A server that registers capabilities must not be answered with lsp4j's throwing default.
        override fun registerCapability(params: RegistrationParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun unregisterCapability(params: UnregistrationParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)
    }

    companion object {
        private const val RequestTimeoutMillis = 4_000L
        private const val StartTimeoutMillis = 180_000L
        private const val StderrTailBytes = 8_000

        /**
         * Starts [launch] against [root] and completes the LSP handshake. Returns an unavailable
         * intelligence rather than throwing, because a missing server is an ordinary state for a
         * workbench pane, not an error the caller should have to catch.
         */
        suspend fun start(launch: LanguageServerLaunch, root: File): CodeIntelligence {
            val process = runCatching {
                ProcessBuilder(launch.command)
                    .directory(root)
                    .redirectErrorStream(false)
                    .start()
            }.getOrElse { failure ->
                return unavailable(launch.name, "Could not start ${launch.command.first()}: ${failure.message}")
            }

            val client = SessionClient()
            drainStderr(process, client)
            val launcher = LSPLauncher.createClientLauncher(client, process.inputStream, process.outputStream)
            val listening = launcher.startListening()
            val server = launcher.remoteProxy

            val result = server.initialize(initializeParams(root)).awaitOrNull(StartTimeoutMillis)
            if (result == null) {
                listening.cancel(true)
                process.destroyForcibly()
                return unavailable(launch.name, "${launch.name} did not answer initialize within ${StartTimeoutMillis / 1000}s.")
            }
            server.initialized(InitializedParams())

            val detail = result.serverInfo?.let { "${it.name} ${it.version.orEmpty()}".trim() } ?: launch.origin
            return LanguageServerSession(
                process = process,
                server = server,
                listening = listening,
                client = client,
                status = CodeIntelligenceStatus(launch.name, available = true, detail = detail),
            )
        }

        fun unavailable(name: String, detail: String, remedy: String? = null) = object : CodeIntelligence {
            override val status = CodeIntelligenceStatus(name, available = false, detail = detail, remedy = remedy)
        }

        @Suppress("DEPRECATION") // Servers that predate workspaceFolders still read rootUri.
        private fun initializeParams(root: File) = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            clientInfo = org.eclipse.lsp4j.ClientInfo("reaktor", "1")
            rootUri = root.toURI().toString()
            workspaceFolders = listOf(WorkspaceFolder(root.toURI().toString(), root.name))
            capabilities = ClientCapabilities().apply {
                textDocument = TextDocumentClientCapabilities().apply {
                    synchronization = SynchronizationCapabilities(false, false, false)
                    completion = CompletionCapabilities(CompletionItemCapabilities(false))
                    publishDiagnostics = PublishDiagnosticsCapabilities(false)
                    hover = org.eclipse.lsp4j.HoverCapabilities()
                    definition = org.eclipse.lsp4j.DefinitionCapabilities()
                }
            }
        }

        private fun drainStderr(process: Process, client: SessionClient) {
            Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(client.stderr) {
                            client.stderr.append(line).append('\n')
                            if (client.stderr.length > StderrTailBytes) {
                                client.stderr.delete(0, client.stderr.length - StderrTailBytes)
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; name = "lsp-stderr" }.start()
        }
    }
}

private fun CodePosition.toLsp() = Position(line, column)

private fun Position.toCode() = CodePosition(line, character)

private fun Range.toCode() = CodeSpan(start.toCode(), end.toCode())

private fun org.eclipse.lsp4j.Diagnostic.toCode() = CodeDiagnostic(
    span = range.toCode(),
    message = message.orEmpty(),
    severity = when (severity) {
        DiagnosticSeverity.Warning -> CodeSeverity.Warning
        DiagnosticSeverity.Information -> CodeSeverity.Information
        DiagnosticSeverity.Hint -> CodeSeverity.Hint
        else -> CodeSeverity.Error
    },
    source = source,
    code = code?.let { if (it.isLeft) it.left else it.right?.toString() },
)

private fun CompletionItem.toCode(): CodeCompletion {
    val edit = textEdit?.let { if (it.isLeft) it.left else null }
    return CodeCompletion(
        label = label,
        insert = edit?.newText ?: insertText ?: label,
        kind = kind.toCode(),
        detail = detail ?: labelDetails?.detail,
        documentation = documentation?.let { if (it.isLeft) it.left else it.right?.value },
        sortText = sortText,
        replaces = edit?.range?.toCode(),
    )
}

private fun CompletionItemKind?.toCode() = when (this) {
    CompletionItemKind.Method -> CodeCompletionKind.Method
    CompletionItemKind.Function -> CodeCompletionKind.Function
    CompletionItemKind.Constructor -> CodeCompletionKind.Constructor
    CompletionItemKind.Field -> CodeCompletionKind.Field
    CompletionItemKind.Variable -> CodeCompletionKind.Variable
    CompletionItemKind.Class -> CodeCompletionKind.Class
    CompletionItemKind.Interface -> CodeCompletionKind.Interface
    CompletionItemKind.Module -> CodeCompletionKind.Module
    CompletionItemKind.Property -> CodeCompletionKind.Property
    CompletionItemKind.Value -> CodeCompletionKind.Value
    CompletionItemKind.Enum -> CodeCompletionKind.Enum
    CompletionItemKind.Keyword -> CodeCompletionKind.Keyword
    CompletionItemKind.Snippet -> CodeCompletionKind.Snippet
    CompletionItemKind.File -> CodeCompletionKind.File
    CompletionItemKind.Reference -> CodeCompletionKind.Reference
    CompletionItemKind.Folder -> CodeCompletionKind.Folder
    CompletionItemKind.EnumMember -> CodeCompletionKind.EnumMember
    CompletionItemKind.Constant -> CodeCompletionKind.Constant
    CompletionItemKind.Struct -> CodeCompletionKind.Struct
    CompletionItemKind.Event -> CodeCompletionKind.Event
    CompletionItemKind.Operator -> CodeCompletionKind.Operator
    CompletionItemKind.TypeParameter -> CodeCompletionKind.TypeParameter
    else -> CodeCompletionKind.Text
}

private suspend fun <T> CompletableFuture<T>.awaitOrNull(timeoutMillis: Long): T? =
    withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            whenComplete { value, error ->
                when {
                    error != null -> continuation.resumeWithException(error)
                    else -> continuation.resume(value)
                }
            }
            continuation.invokeOnCancellation { cancel(true) }
        }
    }
