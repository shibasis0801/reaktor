package dev.shibasis.reaktor.tooling.lsp

import dev.shibasis.reaktor.code.CodeIntelligence
import java.io.File
import java.util.concurrent.TimeUnit

object ClangdLanguageServer {
    const val Name = "clangd"

    val remedy = "Install Xcode or its command line tools, which carry clangd, or point REAKTOR_CLANGD at a clangd binary"

    fun locate(
        environment: Map<String, String> = System.getenv(),
        path: String? = System.getenv("PATH"),
        xcrun: () -> String? = ::xcrun,
    ): File? {
        environment["REAKTOR_CLANGD"]?.takeIf { it.isNotBlank() }?.let { configured -> File(configured).takeIf { it.isFile && it.canExecute() }?.let { return it } }
        xcrun()?.let(::File)?.takeIf { it.isFile && it.canExecute() }?.let { return it }
        val searched = path.orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank).map { File(it) } + WellKnown.map(::File)
        return searched.map { File(it, "clangd") }.firstOrNull { it.isFile && it.canExecute() }
    }

    fun launch(binary: File, commands: File, origin: String = binary.path) = LanguageServerLaunch(Name, listOf(binary.absolutePath,
        "--compile-commands-dir=${commands.absolutePath}", "--background-index", "--background-index-priority=low", "-j=2", "--log=error", "--header-insertion=never"), origin)

    suspend fun start(root: File, commands: File, environment: Map<String, String> = System.getenv()): CodeIntelligence {
        val binary = locate(environment) ?: return LanguageServerSession.unavailable(Name, "clangd is not installed on this machine.", remedy)
        if (!File(commands, "compile_commands.json").isFile) {
            return LanguageServerSession.unavailable(Name, "${commands.path} holds no compile_commands.json.", "Build the native module once so its compile commands are written")
        }
        return LanguageServerSession.start(launch(binary, commands), root)
    }

    private fun xcrun(): String? = runCatching {
        val process = ProcessBuilder("xcrun", "--find", "clangd").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        text.takeIf { process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 && it.isNotEmpty() }
    }.getOrNull()

    private val WellKnown = listOf("/opt/homebrew/opt/llvm/bin", "/usr/local/opt/llvm/bin", "/usr/bin")
}
