package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.mcp.McpMessageHandler
import dev.shibasis.reaktor.mcp.McpReadResource
import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.mcp.emptyObjectSchema
import dev.shibasis.reaktor.mcp.objectSchema
import dev.shibasis.reaktor.mcp.stringSchema
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.CallCatalog
import dev.shibasis.reaktor.tooling.CallRequest
import dev.shibasis.reaktor.tooling.ProviderAvailability
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.ToolingCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A provider as the door presents it. */
class DoorMount(
    val provider: McpLinkProvider,
    val mode: DoorMode = DoorMode.OnDemand,
    /** In front of every call name; empty keeps the provider's own names, which is right for Reaktor's own servers. */
    val prefix: String = "",
    val title: String = provider.providerId,
    val offlineHint: String? = null,
    /** Reaktor's own servers gate their own calls; the door's policy is for providers where nothing else stands in the way. */
    val selfGoverned: Boolean = false,
    val callClasses: Map<String, SafetyClass> = emptyMap(),
    val trustReadOnlyHint: Boolean = false,
    val sqlArguments: Map<String, String> = emptyMap(),
) {
    /** The class of this call with these arguments. Looking at a statement can only ever show it to be a read; it never makes a call riskier than its provider's class. */
    fun effect(call: ToolingCall, arguments: JsonObject = JsonObject(emptyMap())): SafetyClass {
        val declared = DoorAuthority.effect(call, provider.safetyClass, callClasses, trustReadOnlyHint)
        val sql = sqlArguments[call.name]?.let { (arguments[it] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        return if (sql != null && declared > SafetyClass.LiveRead && DoorAuthority.readsOnly(sql)) SafetyClass.LiveRead else declared
    }

    val id: String get() = provider.providerId
    /** Some servers already put their own name in front (`firebase_get_environment`); saying it twice helps nobody. */
    fun exposed(call: ToolingCall): String = if (prefix.isEmpty() || call.name.startsWith("${prefix}_")) call.name else "${prefix}_${call.name}"
}

/**
 * One MCP server in front of many.
 *
 * It lists from snapshots, so opening a session never waits for a provider to start, and a
 * provider that is down keeps its calls listed and says why it cannot answer them.
 */
class McpDoor(
    private val mounts: List<DoorMount>,
    private val snapshots: CallSnapshots,
    private val log: CallLog,
    private val caller: CallCaller,
    private val scope: CoroutineScope,
    private val version: String = "1",
    /** Called when the listed calls changed; a host with its own channel turns it into a notification. */
    private val onCallsChanged: () -> Unit = {},
    private val policy: DoorPolicy = DoorPolicy(),
    /** Absent in a host that has no operator face yet: a call that needs a person is then simply not made. */
    private val approvals: DoorApprovals? = null,
    private val workspace: String? = null,
    /** Things an agent should be able to tell a person, such as a provider list nobody has accepted yet. */
    private val notices: List<String> = emptyList(),
    /** Called when a call starts waiting for a person, so a host can put the question in front of one. */
    private val onApprovalNeeded: (DoorApproval) -> Unit = {},
) : McpMessageHandler {
    private class Built(val revision: String, val server: ReaktorMcpServer, val dropped: Map<String, String>)

    @Volatile private var built: Built? = null
    /** A client that has not listed yet has nothing to be told about; announcing then is noise before the handshake is even done. */
    @Volatile private var listed = false
    private val refreshed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        val active = mounts.filter { it.mode != DoorMode.Off }
        require(active.map(DoorMount::id).toSet().size == active.size) { "Two providers share an id" }
        val prefixes = active.map(DoorMount::prefix).filter(String::isNotEmpty)
        require(prefixes.toSet().size == prefixes.size) { "Two providers share a name prefix: $prefixes" }
    }

    override fun handle(body: String): JsonElement? {
        // Also before the first message of any kind: a client that calls without listing must still find the loopback providers.
        if (built == null || body.contains("\"tools/list\"") || body.contains("\"resources/list\"")) refreshCheap()
        return server().server.handle(body).also { if (body.contains("\"tools/list\"")) listed = true }
    }

    /** Starts what is meant to be running; everything else waits for its first call. */
    fun open() {
        active().filter { it.mode == DoorMode.Running }.forEach { mount -> scope.launch { refresh(mount) } }
    }

    fun status(names: Boolean = false): JsonObject = buildJsonObject {
        val dropped = server().dropped
        putJsonArray("providers") {
            mounts.forEach { mount ->
                val snapshot = snapshots.read(mount.id)
                val state = mount.provider.state()
                addJsonObject {
                    put("id", mount.id)
                    put("title", mount.title)
                    put("mode", mount.mode.name)
                    put("prefix", mount.prefix)
                    put("availability", state.availability.name)
                    state.detail?.let { put("detail", it) }
                    put("calls", snapshot?.calls?.size ?: 0)
                    snapshot?.let { put("snapshotAgeSeconds", (System.currentTimeMillis() - it.fetchedAtEpochMillis) / 1000); put("digest", it.digest.take(12)) }
                    if (snapshot == null && mount.mode != DoorMode.Off) put("note", "No snapshot yet, so none of its calls are listed. Call reaktor_provider_refresh with this id once.")
                    if (state.availability == ProviderAvailability.Unavailable) mount.offlineHint?.let { put("hint", it) }
                    put("safety", mount.provider.safetyClass.name)
                    if (names) putJsonArray("names") { snapshot?.calls.orEmpty().forEach { add(JsonPrimitive(mount.exposed(it))) } }
                }
            }
        }
        if (dropped.isNotEmpty()) putJsonArray("hiddenByNameCollision") { dropped.forEach { (name, owner) -> addJsonObject { put("call", name); put("keptFrom", owner) } } }
        if (notices.isNotEmpty()) putJsonArray("notices") { notices.forEach { add(JsonPrimitive(it)) } }
        caller.seat?.let { put("seat", it) }
        policy.seat(caller.seat).let { seat -> put("mayCauseWithoutAsking", seat.allow.name); put("mayCauseAfterApproval", seat.ask.name) }
    }

    suspend fun refresh(mount: DoorMount): CallCatalog {
        val catalog = mount.provider.catalog()
        refreshed[mount.id] = mount.provider.state().observedAtEpochMillis ?: System.currentTimeMillis()
        if (snapshots.write(catalog)) { built = null; if (listed) runCatching(onCallsChanged) }
        return catalog
    }

    private fun active() = mounts.filter { it.mode != DoorMode.Off }

    /** Loopback providers answer in milliseconds, so their listing is always current; a dead one costs the timeout once. */
    private fun refreshCheap() = runBlocking {
        active().filter { it.provider.cheap && it.mode != DoorMode.SnapshotOnly }.forEach { mount ->
            withTimeoutOrNull(CHEAP_REFRESH_MILLIS) { runCatching { refresh(mount) } }
        }
    }

    private fun server(): Built {
        val catalogs = active().associateWith { snapshots.read(it.id) }
        val revision = sha256Hex(catalogs.entries.joinToString("|") { "${it.key.id}:${it.key.prefix}:${it.value?.digest}" })
        built?.takeIf { it.revision == revision }?.let { return it }

        val owners = linkedMapOf<String, MutableList<Pair<DoorMount, ToolingCall>>>()
        val dropped = linkedMapOf<String, String>()
        catalogs.forEach { (mount, catalog) ->
            catalog?.calls.orEmpty().forEach { call ->
                val name = mount.exposed(call)
                if (name in OWN_CALLS || !NAME.matches(name)) { dropped[name] = "the door"; return@forEach }
                val offers = owners.getOrPut(name) { mutableListOf() }
                // The first provider owns a name. A later one only stands in for it, and only for a read:
                // the desktop and the kernel answer the same questions, and either may be the one running.
                if (offers.isEmpty() || (offers.first().second.claims.readOnly == true && call.claims.readOnly == true)) offers += mount to call
                else dropped[name] = offers.first().first.id
            }
        }
        val tools = owners.map { (name, offers) ->
            val (mount, call) = offers.first()
            McpTool(
                name = name,
                description = describe(mount, call),
                inputSchema = call.inputSchema,
                readOnly = call.claims.readOnly ?: false,
                idempotent = call.claims.idempotent ?: false,
                destructive = call.claims.destructive ?: true,
                openWorld = call.claims.openWorld ?: true,
                raw = true,
            ) { arguments -> runBlocking { route(offers, arguments) } }
        }
        val resources = catalogs.flatMap { (mount, catalog) ->
            catalog?.resources.orEmpty().map { resource ->
                McpReadResource(resource.uri, resource.name, resource.description.orEmpty(), resource.mimeType ?: "application/json") {
                    runBlocking { firstText(mount.provider.read(resource.uri)) }
                }
            }
        }.distinctBy(McpReadResource::uri)
        val server = ReaktorMcpServer("reaktor", version, instructions(catalogs), ownCalls() + tools.sortedBy(McpTool::name), resources, toolsListChanged = true)
        return Built(revision, server, dropped).also { built = it }
    }

    private suspend fun route(offers: List<Pair<DoorMount, ToolingCall>>, arguments: JsonObject): JsonObject {
        val reasons = mutableListOf<String>()
        offers.forEach { (mount, call) ->
            val started = System.nanoTime()
            fun took() = (System.nanoTime() - started) / 1_000_000
            if (mount.mode == DoorMode.SnapshotOnly) {
                log.record(mount.id, call.name, arguments, took(), "unavailable", "snapshot-only")
                reasons += "${mount.title} is listed from its snapshot and is not set to run here."
                return@forEach
            }
            val effect = mount.effect(call, arguments)
            var approvedAs: String? = null
            if (!mount.selfGoverned) when (val decision = DoorAuthority.decide(effect, policy.seat(caller.seat))) {
                DoorDecision.Allowed -> Unit
                is DoorDecision.Refused -> {
                    log.record(mount.id, call.name, arguments, took(), "refused", "${effect.name}: ${decision.reason}")
                    return textResult("Not done. ${mount.title} ${call.name} would cause ${effect.name}, and ${decision.reason}.", failed = true)
                }
                is DoorDecision.Ask -> {
                    val spent = approvals?.spend(caller.seat, mount.id, call.name, arguments)
                    if (spent == null) {
                        approvals?.denied(caller.seat, mount.id, call.name, arguments)?.let { denied ->
                            log.record(mount.id, call.name, arguments, took(), "denied", "approval ${denied.id} denied by ${denied.decidedBy}")
                            return textResult("Not done. A person denied this call (approval ${denied.id}). Do not ask again with the same arguments.", failed = true)
                        }
                        val before = approvals?.pending()?.map(DoorApproval::id).orEmpty()
                        val waiting = approvals?.request(caller.seat, mount.id, call.name, effect, arguments)
                        if (waiting != null && waiting.id !in before) runCatching { onApprovalNeeded(waiting) }
                        log.record(mount.id, call.name, arguments, took(), "approval-required", "${effect.name}${waiting?.let { " ${it.id}" }.orEmpty()}")
                        return if (waiting == null) textResult("Not done. ${mount.title} ${call.name} would cause ${effect.name}, which needs a person's approval, and this host has no way to ask for one.", failed = true)
                            else JsonObject(textResult(approvalNeeded(waiting, workspace).toString(), failed = true) + ("structuredContent" to approvalNeeded(waiting, workspace)))
                    }
                    approvedAs = "approval ${spent.id} by ${spent.decidedBy}"
                }
            }
            try {
                val outcome = mount.provider.call(CallRequest(mount.id, call.name, arguments, caller))
                val gated = if (mount.selfGoverned) null else ResultGate.apply(outcome.result, policy.maxResultChars)
                log.record(mount.id, call.name, arguments, took(), if (outcome.failed) "failed" else "ok", listOfNotNull(effect.name, approvedAs,
                    gated?.redactions?.takeIf { it > 0 }?.let { "$it credential-shaped value(s) withheld" }, "cut".takeIf { gated?.truncated == true }).joinToString("; "))
                // A provider that only starts on demand has just shown what it really offers; keep the snapshot honest.
                if (!mount.provider.cheap && refreshed[mount.id] == null) scope.launch { runCatching { refresh(mount) } }
                return gated?.result ?: outcome.result
            } catch (failure: Exception) {
                val reason = failure.message ?: failure::class.simpleName.orEmpty()
                log.record(mount.id, call.name, arguments, took(), "unavailable", reason)
                reasons += listOfNotNull("${mount.title} could not answer ${call.name}: $reason", mount.offlineHint).joinToString(" ")
            }
        }
        return textResult((reasons + "The call was not retried. reaktor_status shows every provider.").joinToString(" "), failed = true)
    }

    private fun ownCalls(): List<McpTool> = listOf(
        McpTool("reaktor_status", "Which providers sit behind this server, whether each is running, how old its listing is, and what to start when one is down.",
            objectSchema(mapOf("names" to buildJsonObject { put("type", "boolean"); put("description", "Also list each provider's call names") })), readOnly = true, idempotent = true) { arguments ->
            status((arguments["names"] as? JsonPrimitive)?.content == "true")
        },
        McpTool("reaktor_provider_refresh", "Start one provider and read its calls again. Needed once for a provider that has never run here; its calls appear in the listing afterwards.",
            objectSchema(mapOf("provider" to stringSchema("Provider id from reaktor_status")), listOf("provider")), readOnly = false, idempotent = true) { arguments ->
            val id = (arguments["provider"] as? JsonPrimitive)?.contentOrNull ?: error("provider is required")
            val mount = active().firstOrNull { it.id == id } ?: error("No provider '$id'. reaktor_status lists them.")
            val catalog = runBlocking { refresh(mount) }
            buildJsonObject {
                put("provider", id); put("calls", catalog.calls.size); put("digest", catalog.digest.take(12))
                put("names", JsonArray(catalog.calls.map { JsonPrimitive(mount.exposed(it)) }))
            }
        },
    )

    private fun describe(mount: DoorMount, call: ToolingCall): String {
        val text = call.description?.trim().orEmpty()
        val label = if (mount.prefix.isEmpty()) text else "[${mount.title}] $text"
        return if (label.length <= MAX_DESCRIPTION) label else label.take(MAX_DESCRIPTION - 1) + "…"
    }

    private fun instructions(catalogs: Map<DoorMount, CallCatalog?>): String = buildString {
        append("One server for this workspace's tools. Providers: ")
        append(catalogs.entries.joinToString("; ") { (mount, catalog) ->
            "${mount.title} (${catalog?.calls?.size ?: 0} calls" + (if (mount.prefix.isEmpty()) "" else ", names start ${mount.prefix}_") +
                (if (mount.provider.cheap) "" else ", starts on first use") + ")"
        })
        append(". When a call says its provider is offline, reaktor_status says what to start.")
        notices.forEach { append(' ').append(it) }
    }

    private fun firstText(result: JsonObject): String =
        ((result["contents"] as? JsonArray)?.firstOrNull() as? JsonObject)?.let { (it["text"] as? JsonPrimitive)?.contentOrNull } ?: result.toString()

    private companion object {
        const val CHEAP_REFRESH_MILLIS = 2_500L
        const val MAX_DESCRIPTION = 1_900
        val OWN_CALLS = setOf("reaktor_status", "reaktor_provider_refresh")
        val NAME = Regex("[a-zA-Z0-9_-]{1,100}")
    }
}
