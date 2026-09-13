package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@Serializable data class AgentRemoteProfile(val host: String, val workspaceRoot: String, val launcher: String)

/** SSH uses the host's credentials and persistent supervisor. A disconnected client owns no work. */
internal class AgentRemoteWorkspace(private val profile: AgentRemoteProfile) {
    init {
        require(profile.host.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.@-]{0,200}"))) { "Use an SSH config host alias" }
        require(profile.workspaceRoot.startsWith("/") && profile.launcher.startsWith("/")) { "Remote workspace and launcher must be absolute POSIX paths" }
        require(listOf(profile.workspaceRoot, profile.launcher).none { '\n' in it || '\r' in it || '\u0000' in it })
    }
    fun command(): List<String> {
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        return listOf("ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2",
            profile.host, listOf(profile.launcher, "mcp", "--dir", profile.workspaceRoot).joinToString(" ", transform = ::quote))
    }
    fun exchange(message: String): JsonElement? {
        val output = Files.createTempFile("reaktor-remote-", ".out")
        val errors = Files.createTempFile("reaktor-remote-", ".err")
        try {
            val process = ProcessBuilder(command()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
            process.outputStream.bufferedWriter().use { it.appendLine(message) }
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Remote observation timed out; inspect the saved run before retrying mutations") }
            check(process.exitValue() == 0) { "SSH connection failed. ${Files.readString(errors).takeLast(1000)}" }
            require(Files.size(output) <= 2_000_000)
            val result = Files.readString(output).trim()
            return result.takeIf(String::isNotBlank)?.let(ConductorJson::parseToJsonElement)
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(errors) }
    }
}
