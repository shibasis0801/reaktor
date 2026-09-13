package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.cli.AgentBundle
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.TimeUnit

/** The OS owns the service; every desktop/CLI connection is only a client. */
object AgentBackgroundService {
    @Synchronized fun connect(root: File, command: List<String> = command(root), directory: Path = AgentWorkspaceConnection.defaultDirectory(root)): AgentWorkspaceConnection {
        runCatching { AgentWorkspaceConnection.open(root, directory, allowStart = false) }.getOrNull()?.let { return it }
        check(System.getProperty("os.name").lowercase().contains("mac")) {
            "Background supervision currently requires macOS. Run workspace serve under your system service manager."
        }
        privateDirectory(directory)
        if (!Files.exists(directory.resolve("service.log"))) atomicWrite(directory.resolve("service.log"), "")
        FileChannel.open(directory.resolve("service-install.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            val label = label(root)
            val domain = "gui/${run(listOf("/usr/bin/id", "-u")).second.trim()}"
            val agents = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents")
            Files.createDirectories(agents)
            val plist = agents.resolve("$label.plist")
            val owned = directory.resolve("launch-agent.plist")
            if (Files.exists(plist)) {
                check(Files.exists(owned) && Files.readString(plist) == Files.readString(owned)) { "The background service configuration was edited outside Reaktor: $plist" }
            }
            val loaded = run(listOf("/bin/launchctl", "print", "$domain/$label")).first == 0
            if (!loaded) {
                val content = configuration(label, snapshotServiceCommand(command, directory), root, directory)
                atomicWrite(plist, content)
                atomicWrite(owned, content)
                val result = run(listOf("/bin/launchctl", "bootstrap", domain, plist.toString()))
                check(result.first == 0 || run(listOf("/bin/launchctl", "print", "$domain/$label")).first == 0) {
                    "Could not start background service: ${result.second.take(500)}"
                }
            }
        } }
        repeat(150) {
            runCatching { AgentWorkspaceConnection.open(root, directory, allowStart = false) }.getOrNull()?.let { return it }
            Thread.sleep(100)
        }
        error("Background service did not publish its endpoint. Inspect ${directory.resolve("service.log")}")
    }

    fun command(root: File): List<String> = AgentBundle.launchCommand(root, null).toMutableList().apply {
        this[indexOfLast { it == "workspace" } + 1] = "serve"
        add("--background")
    }

    fun label(root: File) = "build.reaktor.agents.${digest(root.canonicalPath).take(24)}"

    fun stop(root: File): String {
        val label = label(root)
        val domain = "gui/${run(listOf("/usr/bin/id", "-u")).second.trim()}"
        val result = run(listOf("/bin/launchctl", "bootout", "$domain/$label"))
        check(result.first == 0) { "Could not stop background service: ${result.second.take(500)}" }
        return "Background service stopped; unfinished tasks remain checkpointed. It can recover them on the next start or macOS login."
    }

    internal fun configuration(label: String, command: List<String>, root: File, directory: Path): String {
        require(command.isNotEmpty() && File(command.first()).isAbsolute)
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun string(value: String) = "<string>${xml(value)}</string>"
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key>${string(label)}
<key>ProgramArguments</key><array>${command.joinToString("") { string(it) }}</array>
<key>WorkingDirectory</key>${string(root.canonicalPath)}
<key>KeepAlive</key><true/>
<key>RunAtLoad</key><true/>
<key>ThrottleInterval</key><integer>10</integer>
<key>ExitTimeOut</key><integer>20</integer>
<key>AbandonProcessGroup</key><false/>
<key>ProcessType</key><string>Standard</string>
<key>EnvironmentVariables</key><dict><key>REAKTOR_AGENT_SUPERVISOR</key><string>launchd</string><key>PATH</key>${string(agentServicePath())}</dict>
<key>StandardOutPath</key>${string(directory.resolve("service.log").toString())}
<key>StandardErrorPath</key>${string(directory.resolve("service.log").toString())}
</dict></plist>
"""
    }

    private fun run(command: List<String>): Pair<Int, String> {
        val output = Files.createTempFile("reaktor-service-", ".log").toFile()
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
            check(process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); "Service manager timed out" }
            process.exitValue() to output.readText().take(16000)
        } finally { output.delete() }
    }
}
