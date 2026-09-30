package dev.shibasis.reaktor.conductor.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Walks the door the way an agent would: starts every provider, then makes every call that can
 * be made without deciding anything — one the provider marks read-only and that needs no
 * arguments, or one its entry names as a safe probe.
 *
 * It goes through the same message handler a harness talks to, so what passes here is what an
 * agent gets, policy and result gate included.
 */
internal suspend fun verifyDoor(root: File, seat: String, only: Set<String>) = withContext(Dispatchers.IO) {
    val providers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        WorkspaceDoor(root, seat, providers) {}.use { workspace ->
            var next = 0
            fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject? =
                (workspace.door.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", ++next); put("method", method); put("params", params) }.toString()) as? JsonObject)
            fun call(name: String, arguments: JsonObject = buildJsonObject {}): Pair<JsonObject?, Long> {
                val started = System.nanoTime()
                val answer = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
                return (answer?.get("result") as? JsonObject) to (System.nanoTime() - started) / 1_000_000
            }
            fun status(): List<JsonObject> = call("reaktor_status", buildJsonObject { put("names", true) }).first
                ?.get("structuredContent")?.jsonObject?.get("providers")?.jsonArray?.map { it.jsonObject }.orEmpty()
            fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull

            rpc("initialize", buildJsonObject { put("protocolVersion", "2025-11-25"); put("capabilities", buildJsonObject {}); put("clientInfo", buildJsonObject { put("name", "reaktor-verify"); put("version", "1") }) })
            rpc("tools/list")
            (call("reaktor_status").first?.get("structuredContent") as? JsonObject)?.get("notices")?.jsonArray?.forEach { println("note: ${it.jsonPrimitive.content}\n") }

            val started = status().filter { it.text("mode") != "Off" && (only.isEmpty() || it.text("id") in only) }.associate { provider ->
                val id = provider.text("id").orEmpty()
                val (_, millis) = call("reaktor_provider_refresh", buildJsonObject { put("provider", id) })
                id to millis
            }
            val tools = rpc("tools/list")?.get("result")?.jsonObject?.get("tools")?.jsonArray?.map { it.jsonObject }.orEmpty().associateBy { it.text("name").orEmpty() }
            val probes = WorkspaceDoor.probes(root)

            println("%-16s %-13s %5s %7s  %s".format("provider", "state", "calls", "start", "reads made with no arguments"))
            var failures = 0
            // A name two providers offer is answered by the first of them. Calling it again under the second would test the first twice and say otherwise.
            val answeredBy = mutableMapOf<String, String>()
            status().forEach { provider ->
                val id = provider.text("id").orEmpty()
                if (only.isNotEmpty() && id !in only) return@forEach
                if (provider.text("mode") == "Off") { println("%-16s %-13s %5s %7s  switched off in the provider list".format(id, "off", "-", "-")); return@forEach }
                val state = provider.text("availability").orEmpty()
                val names = provider["names"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                if (state != "Available") {
                    println("%-16s %-13s %5s %7s  %s".format(id, state, "-", "${started[id] ?: 0}ms", (provider.text("detail") ?: provider.text("note") ?: provider.text("hint") ?: "").take(200)))
                    return@forEach
                }
                val shared = names.filter { it in answeredBy }
                names.forEach { answeredBy.putIfAbsent(it, id) }
                val safe = names.filter { name ->
                    if (name in shared) return@filter false
                    val tool = tools[name] ?: return@filter false
                    val named = probes[id].orEmpty().any { name == it || name.endsWith("_$it") }
                    val readOnly = (tool["annotations"]?.jsonObject?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true
                    val needsNothing = (tool["inputSchema"]?.jsonObject?.get("required") as? JsonArray).isNullOrEmpty()
                    name !in NEVER && (named || (readOnly && needsNothing))
                }
                val results = safe.map { name ->
                    val (result, millis) = call(name)
                    val failed = result == null || (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
                    val first = ((result?.get("content") as? JsonArray)?.firstOrNull() as? JsonObject)
                    val shown = when (first?.text("type")) { "image" -> "image, ${first.text("data")?.length ?: 0} chars"; else -> first?.text("text")?.replace(Regex("\\s+"), " ")?.take(90).orEmpty() }
                    Triple(name, failed, "${millis}ms $shown")
                }
                failures += results.count { it.second }
                println("%-16s %-13s %5d %7s  %d of %d ok%s".format(id, state, names.size, "${started[id] ?: 0}ms", results.count { !it.second }, results.size,
                    if (results.isEmpty()) " (nothing here can be called without arguments)" else ""))
                results.forEach { (name, failed, shown) -> println("%-16s %s %-44s %s".format("", if (failed) "  FAILED" else "      ok", name, shown)) }
                if (shared.isNotEmpty()) println("%-16s   shared %d call(s) also offered by ${shared.map { answeredBy.getValue(it) }.distinct().joinToString()}, which answers them while it is up; this provider stands in when it is not".format("", shared.size))
            }
            println("\n${tools.size} calls listed behind the one server; ${if (failures == 0) "every read that was made answered" else "$failures read(s) failed"}.")
        }
    } finally { providers.cancel() }
}

/** Read-only by their own account, but they wait, stream or spend: not something a health check should start. */
private val NEVER = setOf("agent_wait", "agent_attach", "agent_transcript")

