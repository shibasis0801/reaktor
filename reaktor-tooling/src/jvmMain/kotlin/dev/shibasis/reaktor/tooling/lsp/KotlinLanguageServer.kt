package dev.shibasis.reaktor.tooling.lsp

import dev.shibasis.reaktor.code.CodeIntelligence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * JetBrains' Kotlin language server, found on the machine rather than bundled: it ships as an
 * IntelliJ-based release archive, not a Maven artifact, so reaktor locates it and speaks LSP to it.
 */
object KotlinLanguageServer {
    const val Name = "Kotlin LSP"
    const val Releases = "https://github.com/Kotlin/kotlin-lsp/releases"

    val remedy =
        "Unpack a kotlin-lsp release into ~/.reaktor/kotlin-lsp, or point REAKTOR_KOTLIN_LSP at its launcher. Releases: $Releases"

    private val launcherNames = listOf("kotlin-lsp.sh", "kotlin-lsp", "kotlin-lsp.bat")
    private val sessions = HashMap<String, CodeIntelligence>()
    private val lock = Mutex()

    /**
     * Where a launcher was found, or null. [environment] and [home] are parameters so discovery is
     * testable on a machine that has no server installed.
     */
    fun locate(
        environment: Map<String, String> = System.getenv(),
        home: File = File(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
    ): LanguageServerLaunch? {
        environment["REAKTOR_KOTLIN_LSP"]?.takeIf { it.isNotBlank() }?.let { configured ->
            val candidate = File(configured)
            val launcher = if (candidate.isDirectory) candidate.firstLauncher() else candidate.takeIf { it.isFile }
            if (launcher != null) return launch(launcher, "REAKTOR_KOTLIN_LSP")
        }
        val wellKnown = listOf(
            File(home, ".reaktor/kotlin-lsp"),
            File(home, ".local/share/kotlin-lsp"),
            File(home, "Applications/kotlin-lsp"),
        )
        wellKnown.forEach { directory ->
            directory.firstLauncher()?.let { return launch(it, directory.path) }
        }
        path.orEmpty().split(File.pathSeparatorChar).forEach { entry ->
            if (entry.isBlank()) return@forEach
            File(entry).firstLauncher()?.let { return launch(it, "PATH") }
        }
        return null
    }

    /**
     * One server per workspace root. A pane that opens a second file must not pay another cold
     * start, and the server holds a whole project index.
     */
    suspend fun shared(
        root: File,
        environment: Map<String, String> = System.getenv(),
        home: File = File(System.getProperty("user.home")),
    ): CodeIntelligence = lock.withLock {
        val key = root.canonicalPath
        sessions[key]?.let { existing ->
            if (existing !is LanguageServerSession || existing.alive()) return@withLock existing
            sessions.remove(key)
        }
        val started = start(root, environment, home)
        sessions[key] = started
        started
    }

    suspend fun start(
        root: File,
        environment: Map<String, String> = System.getenv(),
        home: File = File(System.getProperty("user.home")),
    ): CodeIntelligence {
        val launch = locate(environment, home)
            ?: return LanguageServerSession.unavailable(Name, "kotlin-lsp is not installed on this machine.", remedy)
        if (!root.isDirectory) {
            return LanguageServerSession.unavailable(Name, "${root.path} is not a directory.", remedy)
        }
        return LanguageServerSession.start(launch, root)
    }

    fun shutdown() {
        sessions.values.filterIsInstance<LanguageServerSession>().forEach { runCatching { it.close() } }
        sessions.clear()
    }

    private fun File.firstLauncher(): File? = launcherNames
        .map { File(this, it) }
        .firstOrNull { it.isFile && it.canExecute() }

    private fun launch(launcher: File, origin: String) =
        LanguageServerLaunch(Name, listOf(launcher.absolutePath, "--stdio"), origin)
}
