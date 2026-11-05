package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClusterTerminal(private val state: Path = ClusterLocations.state()) {
    fun command(host: String): List<String> {
        if (host == "local") return listOf("/bin/zsh", "-l")
        val config = state.resolve("config.json")
        require(Files.isRegularFile(config) && !Files.isSymbolicLink(config) && Files.size(config) <= 1_048_576) { "Cluster configuration unavailable" }
        val configured = Json.parseToJsonElement(Files.readString(config)).jsonObject["host"]?.jsonPrimitive?.content ?: "m1-worker"
        require(configured.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) && host == configured) { "Terminal host is not a configured cluster machine" }
        return listOf("ssh", "-tt", "--", configured)
    }
}
