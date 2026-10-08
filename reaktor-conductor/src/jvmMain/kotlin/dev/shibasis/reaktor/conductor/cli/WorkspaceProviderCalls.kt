package dev.shibasis.reaktor.conductor.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

class WorkspaceProviderCalls(root: File, scope: CoroutineScope, seat: String = "hangar") : AutoCloseable {
    private val host = WorkspaceDoor(root, seat, scope) {}

    suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject = withContext(Dispatchers.IO) {
        val response = requireNotNull(host.door.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", arguments) }
        }.toString())).jsonObject
        require(response["error"] == null) { "Workspace provider call failed" }
        val result = requireNotNull(response["result"]).jsonObject
        require(result["isError"]?.jsonPrimitive?.booleanOrNull != true) { "Workspace provider read was unavailable or denied; inspect the door audit" }
        result
    }

    override fun close() = host.close()
}
