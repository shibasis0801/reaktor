package dev.shibasis.reaktor.conductor.cli

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeMcpConfigTest {
    @Test fun anHttpServerReachesCodexWithTheRunHeaderAndWithoutClaudeKeys() {
        val config = Files.createTempFile("harness-mcp", ".json")
        try {
            Files.writeString(config, buildJsonObject { putJsonObject("mcpServers") {
                putJsonObject("reaktor") { put("type", "http"); put("url", "http://127.0.0.1:9318/mcp/ws?seat=spawned"); put("headersHelper", "/h/mcp-headers") }
                putJsonObject("door") { put("command", "/bin/door"); putJsonArray("args") { add("--seat"); add("spawned") } }
            } }.toString())
            assertEquals(listOf(
                "-c", """mcp_servers.reaktor={ "url" = "http://127.0.0.1:9318/mcp/ws?seat=spawned", "env_http_headers" = { "Reaktor-Run" = "REAKTOR_AGENT_RUN_ID" } }""",
                "-c", """mcp_servers.door={ "command" = "/bin/door", "args" = ["--seat", "spawned"], "env_vars" = ["REAKTOR_AGENT_RUN_ID"] }""",
            ), codexMcpArgs(config.toString()))
        } finally { Files.deleteIfExists(config) }
    }
}
