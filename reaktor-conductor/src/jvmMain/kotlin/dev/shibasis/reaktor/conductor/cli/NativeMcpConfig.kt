package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentRequest
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
        val declared = value.jsonObject.filterKeys { it !in ClaudeOnly }
        val entry = when {
            "command" in declared -> declared + ("env_vars" to JsonArray(((declared["env_vars"] as? JsonArray).orEmpty() + JsonPrimitive(AgentRequest.RunVariable)).distinct()))
            "url" in declared -> declared + listOfNotNull(
                (value.jsonObject["headers"] as? JsonObject)?.let { "http_headers" to JsonObject((declared["http_headers"] as? JsonObject).orEmpty() + it) },
                "env_http_headers" to JsonObject((declared["env_http_headers"] as? JsonObject).orEmpty() + (AgentRequest.RunHeader to JsonPrimitive(AgentRequest.RunVariable))))
            else -> declared
        }
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        listOf("-c", "mcp_servers.$name=" + toml(JsonObject(entry)))
    }
}

private val ClaudeOnly = setOf("type", "headers", "headersHelper")
