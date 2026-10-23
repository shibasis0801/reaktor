package dev.shibasis.reaktor.conductor.cli

import kotlinx.serialization.json.*
import java.io.File

internal fun codexMcpArgs(path: String?): List<String> {
    if (path == null) return emptyList()
    val file = File(path)
    require(file.length() <= 250000)
    val servers = Json.parseToJsonElement(file.readText()).jsonObject.getValue("mcpServers").jsonObject
    fun toml(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.joinToString(", ", "{ ", " }") { (key, child) -> "${JsonPrimitive(key)} = ${toml(child)}" }
        is JsonArray -> value.joinToString(", ", "[", "]") { toml(it) }
        JsonNull -> error("Null is not a supported MCP config value")
        else -> value.toString()
    }
    return servers.flatMap { (name, value) ->
        val declared = value.jsonObject.filterKeys { it != "type" }
        val forwarded = (declared["env_vars"] as? JsonArray).orEmpty() + JsonPrimitive(dev.shibasis.reaktor.conductor.AgentRequest.RunVariable)
        val entry = if ("command" in declared) declared + ("env_vars" to JsonArray(forwarded.distinct())) else declared
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        listOf("-c", "mcp_servers.$name=" + toml(JsonObject(entry)))
    }
}
