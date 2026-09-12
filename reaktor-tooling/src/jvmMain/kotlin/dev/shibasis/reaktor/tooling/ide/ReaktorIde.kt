package dev.shibasis.reaktor.tooling.ide

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Opens a source file in a JetBrains IDE. Running IDEs are preferred over cold-start fallbacks so
 * a node click keeps the developer in the project they already have open.
 */
object ReaktorIde {

    fun open(file: File, line: Int) {
        commandPlan(file.absoluteFile, line, ProcessIdeEnvironment)
            .firstOrNull(::runProcess)
    }

    internal fun commandPlan(
        file: File,
        line: Int,
        environment: IdeEnvironment,
    ): List<List<String>> {
        val path = file.absolutePath
        val orderedIdes = orderedIdes(environment)
        val ideCommands = orderedIdes.flatMap { ide ->
            buildList {
                ide.executable(environment)?.let { add(listOf(it, "--line", line.toString(), path)) }
                if (environment.isMac) {
                    ide.bundleIds.forEach { add(listOf("open", "-b", it, path)) }
                    ide.appNames.forEach { add(listOf("open", "-a", it, path)) }
                }
            }
        }

        val fallback = if (environment.isMac) {
            listOf("open", path)
        } else {
            listOf("xdg-open", path)
        }

        return ideCommands + listOf(fallback)
    }

    private fun orderedIdes(environment: IdeEnvironment): List<JetBrainsIde> {
        val running = jetBrainsIdes.filter { it.isRunning(environment) }
        val frontmost = environment.frontmostApplicationName()
            ?.let { appName -> running.firstOrNull { it.matchesAppName(appName) } }

        return buildList {
            if (frontmost != null) add(frontmost)
            addAll(running.filter { it != frontmost })
            addAll(jetBrainsIdes.filter { it !in this && it.executable(environment) != null })
            addAll(jetBrainsIdes.filter { it !in this })
        }
    }

    private fun JetBrainsIde.isRunning(environment: IdeEnvironment): Boolean =
        environment.runningProcessText()
            .any { text ->
                val normalized = text.normalized()
                processMarkers.any { marker -> marker in normalized }
            }

    private fun JetBrainsIde.matchesAppName(name: String): Boolean {
        val normalized = name.normalized()
        return appNames.any { it.normalized() == normalized } || processMarkers.any { it in normalized }
    }

    private fun JetBrainsIde.executable(environment: IdeEnvironment): String? =
        cliCommands.firstNotNullOfOrNull(environment::findExecutable)

    private fun runProcess(command: List<String>): Boolean =
        runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
}

internal data class JetBrainsIde(
    val id: String,
    val appNames: List<String>,
    val bundleIds: List<String>,
    val cliCommands: List<String>,
    val processMarkers: List<String>,
)

internal interface IdeEnvironment {
    val isMac: Boolean
    fun findExecutable(command: String): String?
    fun frontmostApplicationName(): String?
    fun runningProcessText(): List<String>
}

internal object ProcessIdeEnvironment : IdeEnvironment {
    override val isMac: Boolean =
        System.getProperty("os.name").orEmpty().normalized().contains("mac")

    override fun findExecutable(command: String): String? =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it, command) }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath

    override fun frontmostApplicationName(): String? {
        if (!isMac) return null
        return runCatching {
            val process = ProcessBuilder(
                "osascript",
                "-e",
                "tell application \"System Events\" to get name of first application process whose frontmost is true",
            )
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            process.inputStream.bufferedReader().readText().trim().takeIf(String::isNotBlank)
        }.getOrNull()
    }

    override fun runningProcessText(): List<String> =
        ProcessHandle.allProcesses()
            .iterator()
            .asSequence()
            .mapNotNull { handle ->
                runCatching {
                    val info = handle.info()
                    listOfNotNull(
                        info.command().orElse(null),
                        info.commandLine().orElse(null),
                    ).joinToString(" ")
                        .takeIf(String::isNotBlank)
                        ?.normalized()
                }.getOrNull()
            }
            .toList()
}

internal val jetBrainsIdes = listOf(
    JetBrainsIde(
        id = "idea",
        appNames = listOf(
            "IntelliJ IDEA",
            "IntelliJ IDEA Ultimate",
            "IntelliJ IDEA CE",
            "IntelliJ IDEA Community Edition",
        ),
        bundleIds = listOf(
            "com.jetbrains.intellij",
            "com.jetbrains.intellij.ce",
            "com.jetbrains.intellij-EAP",
        ),
        cliCommands = listOf("idea", "idea64"),
        processMarkers = listOf(
            "intellij idea",
            "com.jetbrains.intellij",
            "/contents/macos/idea",
            "/bin/idea",
            "com.intellij.idea.main",
        ),
    ),
    JetBrainsIde(
        id = "studio",
        appNames = listOf("Android Studio", "Android Studio Preview"),
        bundleIds = listOf(
            "com.google.android.studio",
            "com.google.android.studio-EAP",
            "com.google.android.studio-eap",
        ),
        cliCommands = listOf("studio"),
        processMarkers = listOf(
            "android studio",
            "com.google.android.studio",
            "/contents/macos/studio",
            "/bin/studio",
            "com.android.tools.idea.main",
        ),
    ),
)

private fun String.normalized(): String =
    lowercase(Locale.US)
