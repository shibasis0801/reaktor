package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

/** `reaktor engine` — launch the committed reaktorDesktop Compose engine JAR. */
class Engine : CliktCommand("engine") {
    private val env by requireObject<ReaktorEnv>()
    private val jarPath by option("--jar", help = "engine JAR to launch; defaults to targets/reaktorDesktop/engine.jar")
    private val javaPath by option("--java", help = "java executable; defaults to REAKTOR_ENGINE_JAVA or java on PATH")
    private val mcpPort by option("--mcp-port", help = "local reaktorDesktop MCP port").default("8765")
    private val args by argument(help = "arguments passed to the engine JAR").multiple()

    override fun run() {
        val p = env.requireProject()
        val engineJar = jarPath
            ?.let { File(it).let { file -> if (file.isAbsolute) file else File(p.root, it) } }
            ?: File(p.root, "targets/reaktorDesktop/engine.jar")

        if (!engineJar.isFile) {
            throw UsageError(
                "No reaktorDesktop engine JAR at ${engineJar.path}. " +
                    "Run `./gradlew :reaktorDesktop:build` from ${p.root.path} first, " +
                    "or `./gradlew :reaktorDesktop:storeEngineJar` to refresh only the artifact.",
            )
        }

        val java = javaPath ?: System.getenv("REAKTOR_ENGINE_JAVA") ?: "java"
        val command = mutableListOf<String>()
        command += java
        if (isMacOs()) {
            command += "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED"
        }
        command += "-Dreaktor.mcp.port=$mcpPort"
        command += "-jar"
        command += engineJar.absolutePath
        command += args

        runChecked(
            env,
            ProjectCommand(
                label = "$java -jar ${engineJar.relativeToOrSelf(p.root).path}",
                command = command,
                cwd = engineJar.parentFile ?: p.root,
            ),
        )
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").contains("mac", ignoreCase = true)
}
