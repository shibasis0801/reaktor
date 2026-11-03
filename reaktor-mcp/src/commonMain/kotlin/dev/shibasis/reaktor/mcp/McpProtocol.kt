package dev.shibasis.reaktor.mcp

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.service.TraceContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock

const val REAKTOR_MCP_MAX_REQUEST_BYTES: Int = 1_048_576
const val REAKTOR_MCP_DEFAULT_ANSWER_BYTES: Int = 10_240
const val REAKTOR_MCP_PROTOCOL_VERSION: String = "2025-11-25"
val REAKTOR_MCP_PROTOCOL_VERSIONS: Set<String> = setOf("2025-06-18", REAKTOR_MCP_PROTOCOL_VERSION, MCP_TASKS_VERSION)
val MCP_LOG_LEVELS: Set<String> = setOf("debug", "info", "notice", "warning", "error", "critical", "alert", "emergency")

data class McpRequestMeta(
    val protocolVersion: String? = null,
    val clientCapabilities: JsonObject = JsonObject(emptyMap()),
    val clientInfo: JsonObject? = null,
    val logLevel: String? = null,
    val traceparent: String? = null,
    val tracestate: String? = null,
    val baggage: String? = null,
    val inputResponses: JsonElement? = null,
    val requestState: JsonElement? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val isModern: Boolean get() = protocolVersion == MCP_TASKS_VERSION

    fun serviceCall(island: String = "mcp"): ServiceCall? {
        val parent = TraceContext.parse(traceparent) ?: return null
        val child = parent.child()
        val attributes = buildMap<String, Any?> {
            put(ServiceCall.IslandAttribute, island)
            ServiceCall.sessionFromBaggage(baggage)?.let { put(ServiceCall.SessionAttribute, it) }
            tracestate?.let { put("tracestate", it) }
            baggage?.let { put("baggage", it) }
            put("traceparent", child.traceparent)
        }
        return ServiceCall(child, parent.spanId, attributes)
    }
}

data class McpSubscription(
    val id: String,
    val toolsListChanged: Boolean = false,
    val promptsListChanged: Boolean = false,
    val resourcesListChanged: Boolean = false,
    val allResources: Boolean = false,
    val resourceUris: Set<String> = emptySet(),
    val kinds: Set<String> = emptySet(),
    val metadata: JsonObject = JsonObject(emptyMap()),
)

data class McpInputRequest(
    val id: String,
    val kind: String = "approval",
    val prompt: String,
    val schema: JsonObject = emptyObjectSchema(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("prompt", prompt)
        put("schema", schema)
    }
}

fun mcpInputRequired(
    inputRequests: List<McpInputRequest>,
    requestState: JsonElement,
    content: JsonArray? = null,
): JsonObject = buildJsonObject {
    put("resultType", "input_required")
    putJsonArray("inputRequests") { inputRequests.forEach { add(it.toJson()) } }
    put("requestState", requestState)
    if (content != null) put("content", content)
}

fun mcpApprovalRequired(
    approvalId: String,
    message: String,
    requestState: JsonElement = buildJsonObject { put("approval", approvalId) },
    confirmationPhrase: String? = null,
): JsonObject = mcpInputRequired(
    inputRequests = listOf(
        McpInputRequest(
            id = approvalId,
            kind = "approval",
            prompt = message + confirmationPhrase?.let { ". To approve, enter the exact confirmation phrase: $it" }.orEmpty(),
            schema = objectSchema(
                buildMap {
                    put("approved", buildJsonObject { put("type", "boolean"); put("description", "Approve operation") })
                    if (confirmationPhrase != null) put("confirmationPhrase", stringSchema("Exact confirmation phrase when approving"))
                },
                listOf("approved"),
            ),
        ),
    ),
    requestState = requestState,
    content = buildJsonArray { addJsonObject { put("type", "text"); put("text", message) } },
)

data class McpReadResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
    val ttlMs: Long? = null,
    val cacheScope: String? = null,
    val read: () -> String,
)

data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
)

data class McpPromptArgument(val name: String, val description: String, val required: Boolean = false)

data class McpPrompt(
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument> = emptyList(),
    val render: (Map<String, String>) -> String,
)

fun interface McpMessageHandler {
    fun handle(body: String): JsonElement?
}

/** Effectful tools require an authenticated host and explicit effect annotations. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val idempotent: Boolean,
    val destructive: Boolean = false,
    val openWorld: Boolean = false,
    /** The value is already a complete tool result; re-wrapping it would flatten image and error content into text. */
    val raw: Boolean = false,
    val effect: String? = null,
    val alwaysLoad: Boolean = true,
    val requiredCapabilities: Set<String> = emptySet(),
    val executeWithMeta: ((JsonObject, JsonObject) -> JsonElement)? = null,
    val title: String? = null,
    val outputSchema: JsonObject? = null,
    val execute: (JsonObject) -> JsonElement,
) {
    fun invoke(arguments: JsonObject, metadata: JsonObject = JsonObject(emptyMap())): JsonElement =
        executeWithMeta?.invoke(arguments, metadata) ?: execute(arguments)
}

class ReaktorMcpServer(
    private val name: String,
    private val version: String,
    private val instructions: String,
    tools: List<McpTool>,
    resources: List<McpReadResource> = emptyList(),
    /** True only for a host that can push `notifications/tools/list_changed` on its own channel. */
    private val toolsListChanged: Boolean = false,
    private val structuredContent: Boolean = true,
    prompts: List<McpPrompt> = emptyList(),
    private val tasks: McpTasks? = null,
    private val ttlMs: Long = 60_000L,
    private val cacheScope: String = "private",
    private val resourceTemplates: List<McpResourceTemplate> = emptyList(),
    private val maxAnswerBytes: Int? = null,
    private val nextPointer: ((String, JsonObject) -> String?)? = null,
    private val onInputResponse: ((tool: String, arguments: JsonObject, inputResponses: JsonElement, requestState: JsonElement?, metadata: JsonObject) -> JsonElement?)? = null,
    private val withRequestMeta: ((McpRequestMeta, () -> JsonElement) -> JsonElement)? = null,
    private val spanSink: SpanSink? = null,
    private val resourceList: ((JsonObject) -> List<McpReadResource>)? = null,
    private val resourceRead: ((String, JsonObject) -> McpReadResource?)? = null,
) : McpMessageHandler {
    private val tools = tools.associateBy(McpTool::name)
    private val resources = resources.associateBy(McpReadResource::uri)
    private val prompts = prompts.associateBy(McpPrompt::name)
    private val notifications = McpNotifications()
    var lastRequestMeta: McpRequestMeta? = null
        private set

    init {
        require(this.tools.size == tools.size && this.prompts.size == prompts.size) { "Duplicate tool or prompt names" }
        require(cacheScope == "public" || cacheScope == "private") { "Invalid cacheScope: $cacheScope" }
    }

    /**
     * What this server actually serves.
     *
     * A prompt that names a tool the server does not expose costs the model a failed call and then
     * a detour around a capability it was promised. Generating that claim from here instead of
     * writing it out keeps the two from drifting apart.
     */
    val toolNames: List<String> get() = this.tools.keys.toList()

    fun activeSubscriptions(): List<McpSubscription> = notifications.local.list()

    fun connectionSubscriptions(): List<McpSubscription> = notifications.subscriptions()

    fun onNotification(sink: (JsonElement) -> Unit) {
        notifications.local.attach(sink)
    }

    fun attachEmitter(sink: (JsonElement) -> Unit): () -> Unit = notifications.local.attach(sink)

    fun openConnection(sink: (JsonElement) -> Unit): McpConnection = notifications.open(sink, ::handle)

    fun closeSubscription(subscriptionId: String): Boolean = notifications.local.remove(subscriptionId)

    fun notifyToolsListChanged(): List<JsonObject> =
        publishNotification("notifications/tools/list_changed", JsonObject(emptyMap())) { it.toolsListChanged || "toolsListChanged" in it.kinds }

    fun notifyPromptsListChanged(): List<JsonObject> =
        publishNotification("notifications/prompts/list_changed", JsonObject(emptyMap())) { it.promptsListChanged || "promptsListChanged" in it.kinds }

    fun notifyResourcesListChanged(visibleTo: (JsonObject) -> Boolean = { true }): List<JsonObject> =
        publishNotification("notifications/resources/list_changed", JsonObject(emptyMap())) { (it.resourcesListChanged || "resourcesListChanged" in it.kinds) && visibleTo(it.metadata) }

    fun notifyResourceUpdated(uri: String, data: JsonObject = JsonObject(emptyMap()), visibleTo: (JsonObject) -> Boolean = { true }): List<JsonObject> =
        publishNotification("notifications/resources/updated", JsonObject(data + ("uri" to JsonPrimitive(uri)))) {
            (it.allResources || uri in it.resourceUris || "resourceSubscriptions" in it.kinds) && visibleTo(it.metadata)
        }

    fun notifyPlanChanged(data: JsonObject = JsonObject(emptyMap()), visibleTo: (JsonObject) -> Boolean = { true }): List<JsonObject> =
        publishNotification("notifications/reaktor/plan_changed", data) { ("plan" in it.kinds || "plans" in it.kinds) && visibleTo(it.metadata) }

    fun notifyMessageChanged(data: JsonObject = JsonObject(emptyMap()), visibleTo: (JsonObject) -> Boolean = { true }): List<JsonObject> =
        publishNotification("notifications/reaktor/message_changed", data) { ("message" in it.kinds || "messages" in it.kinds) && visibleTo(it.metadata) }

    fun notifyMemoryChanged(data: JsonObject = JsonObject(emptyMap()), visibleTo: (JsonObject) -> Boolean = { true }): List<JsonObject> =
        publishNotification("notifications/reaktor/memory_changed", data) { "memory" in it.kinds && visibleTo(it.metadata) }

    fun progressNotification(
        progressToken: JsonElement,
        progress: Number,
        total: Number? = null,
        message: String? = null,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/progress")
        putJsonObject("params") {
            put("progressToken", progressToken)
            put("progress", progress)
            total?.let { put("total", it) }
            message?.let { put("message", it) }
        }
    }

    private fun publishNotification(
        method: String,
        params: JsonObject,
        predicate: (McpSubscription) -> Boolean,
    ): List<JsonObject> = notifications.publish(method, params, predicate)

    override fun handle(body: String): JsonElement? = handle(body, emptyMap())

    fun handle(body: String, headers: Map<String, String>): JsonElement? = handle(body, headers, notifications.local)

    private fun handle(body: String, headers: Map<String, String>, connection: McpSubscriptionState): JsonElement? {
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "Parse error")
        return when (parsed) {
            is JsonArray -> if (parsed.isEmpty()) {
                rpcError(JsonNull, -32600, "Invalid Request: empty batch")
            } else {
                parsed.mapNotNull { handleSingle(it, headers, connection) }.let { if (it.isEmpty()) null else JsonArray(it) }
            }
            else -> handleSingle(parsed, headers, connection)
        }
    }

    internal suspend fun handleAsync(
        body: String,
        definitions: List<ToolDefinition>,
        execute: suspend (String, JsonObject) -> ToolResult,
    ): JsonElement? = handleAsyncWithMeta(body, definitions) { name, arguments, _ -> execute(name, arguments) }

    internal suspend fun handleAsyncWithMeta(
        body: String,
        definitions: List<ToolDefinition>,
        execute: suspend (String, JsonObject, JsonObject) -> ToolResult,
    ): JsonElement? {
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "Parse error")
        suspend fun single(message: JsonElement): JsonElement? {
            val request = message as? JsonObject ?: return handleSingle(message, emptyMap())
            val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            if (method != "tools/list" && method != "tools/call") return handleSingle(message, emptyMap())
            val id = request["id"]?.takeUnless { it is JsonNull } ?: return null
            val validated = validateRequestMeta(id, method, request, emptyMap())
            val meta = validated.first ?: return validated.second
            lastRequestMeta = meta
            val started = Clock.System.now()
            val callContext = meta.serviceCall(name)
            if (method == "tools/list") {
                val response = modernResponse(request, rpcResult(id, buildJsonObject {
                    putJsonArray("tools") { definitions.forEach { add(it.mcpValue()) } }
                    put("ttlMs", ttlMs)
                    put("cacheScope", cacheScope)
                }), meta, callContext?.trace?.traceparent)
                recordSpan(method, null, meta, callContext, started, response)
                return response
            }
            val params = request["params"] as? JsonObject
            val toolName = (params?.get("name") as? JsonPrimitive)?.contentOrNull
                ?: return rpcError(id, -32602, "Missing tool name")
            if (definitions.none { it.name == toolName }) return rpcError(id, -32602, "Unknown tool: $toolName")
            val arguments = params["arguments"] ?: JsonObject(emptyMap())
            if (arguments !is JsonObject) return rpcError(id, -32602, "Tool arguments must be an object")
            val result = withContext(callContext ?: EmptyCoroutineContext) {
                execute(toolName, arguments, meta.raw)
            }
            val response = modernResponse(request, rpcResult(id, buildJsonObject {
                put("content", result.content ?: buildJsonArray {
                    val text = result.data.toString()
                    val bounded = maxAnswerBytes?.let { boundMcpText(text, it, nextPointer?.invoke(toolName, arguments)) } ?: text
                    addJsonObject { put("type", "text"); put("text", bounded) }
                })
                put("structuredContent", result.data as? JsonObject ?: buildJsonObject { put("result", result.data) })
                put("isError", result.isError)
            }), meta, callContext?.trace?.traceparent)
            recordSpan(method, params, meta, callContext, started, response)
            return response
        }
        return when (parsed) {
            is JsonArray -> if (parsed.isEmpty()) rpcError(JsonNull, -32600, "Invalid Request: empty batch")
            else parsed.mapNotNull { single(it) }.let { if (it.isEmpty()) null else JsonArray(it) }
            else -> single(parsed)
        }
    }

    private fun handleSingle(message: JsonElement, headers: Map<String, String>, connection: McpSubscriptionState = notifications.local): JsonElement? {
        val request = message as? JsonObject ?: return rpcError(JsonNull, -32600, "Invalid Request")
        val id = request["id"]
        val responseId = id?.takeUnless { it is JsonNull }
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            ?: return responseId?.let { rpcError(it, -32600, "Invalid Request: missing method") }
        if (responseId == null) return null
        val validated = validateRequestMeta(responseId, method, request, headers)
        val meta = validated.first ?: return validated.second
        lastRequestMeta = meta
        val started = Clock.System.now()
        val callContext = meta.serviceCall(name)
        val params = request["params"] as? JsonObject
        val rawResponse = when (method) {
            "server/discover" -> rpcResult(responseId, discover())
            "subscriptions/listen" -> runCatching { listenSubscriptions(responseId, params, meta, connection) }
                .getOrElse { rpcError(responseId, -32602, it.message ?: "Invalid subscription") }
            "subscriptions/unlisten" -> unlistenSubscriptions(responseId, params, connection)
            "tasks/get", "tasks/update", "tasks/cancel" -> {
                val host = tasks ?: return rpcError(responseId, -32601, "Tasks are unavailable")
                val taskParams = params ?: return rpcError(responseId, -32602, "Missing task parameters")
                runCatching { rpcResult(responseId, host.request(method, taskParams)) }
                    .getOrElse { rpcError(responseId, -32602, it.message ?: "Invalid task request") }
            }
            "initialize" -> if (meta.isModern) rpcError(responseId, -32601, "Method not found: $method")
            else initializeRequest(responseId, params)
            "tools/list" -> rpcResult(responseId, listTools())
            "tools/call" -> callTool(responseId, params, meta)
            "resources/list" -> runCatching { rpcResult(responseId, listResources(meta.raw)) }
                .getOrElse { rpcError(responseId, -32603, it.message ?: "Resource listing failed") }
            "resources/read" -> readResource(responseId, params, meta.raw)
            "resources/templates/list" -> rpcResult(responseId, listResourceTemplates())
            "prompts/list" -> rpcResult(responseId, listPrompts())
            "prompts/get" -> getPrompt(responseId, params)
            "ping" -> if (meta.isModern) rpcError(responseId, -32601, "Method not found: $method")
            else rpcResult(responseId, buildJsonObject {})
            else -> rpcError(responseId, -32601, "Method not found: $method")
        }
        val response = modernResponse(request, rawResponse, meta, callContext?.trace?.traceparent)
        recordSpan(method, params, meta, callContext, started, response)
        return response
    }

    private fun recordSpan(
        method: String,
        params: JsonObject?,
        meta: McpRequestMeta,
        callContext: ServiceCall?,
        started: kotlin.time.Instant,
        response: JsonObject,
    ) {
        val sink = spanSink ?: return
        val finished = Clock.System.now()
        val trace = callContext?.trace ?: TraceContext.root()
        val parentId = callContext?.parentSpanId.orEmpty()
        val operation = if (method == "tools/call") (params?.get("name") as? JsonPrimitive)?.contentOrNull ?: method else method
        val isError = response["error"] != null ||
            ((response["result"] as? JsonObject)?.get("isError") as? JsonPrimitive)?.booleanOrNull == true
        val decisions = buildMap {
            meta.tracestate?.let { put("tracestate", it) }
            meta.baggage?.let { put("baggage", it) }
            meta.protocolVersion?.let { put("protocolVersion", it) }
        }
        sink.record(
            ServiceSpan(
                traceId = trace.traceId,
                spanId = trace.spanId,
                parentId = parentId,
                phase = ServiceExecutionPhase.SERVER,
                island = name,
                contract = "mcp",
                operation = operation,
                startedMillis = started.toEpochMilliseconds(),
                durationMillis = (finished - started).inWholeMicroseconds / 1000.0,
                status = if (isError) 500 else 200,
                applicationSession = ServiceCall.sessionFromBaggage(meta.baggage).orEmpty(),
                decisions = decisions,
            ),
        )
    }

    private fun validateRequestMeta(
        id: JsonElement,
        method: String,
        request: JsonObject,
        headers: Map<String, String>,
    ): Pair<McpRequestMeta?, JsonObject?> {
        val params = request["params"] as? JsonObject
        val metaElement = params?.get("_meta") ?: request["_meta"]
        if (metaElement != null && metaElement !is JsonObject) {
            return null to rpcError(id, -32602, "Invalid _meta: must be an object")
        }
        val metadata = metaElement as? JsonObject ?: JsonObject(emptyMap())
        val normalizedHeaders = headers.entries.associate { it.key.lowercase() to it.value }
        val versionElement = metadata[MCP_REQUEST_VERSION]
        val version = (versionElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (versionElement != null && (version == null || version !in REAKTOR_MCP_PROTOCOL_VERSIONS)) {
            return null to rpcError(id, MCP_ERROR_UNSUPPORTED_VERSION, "UnsupportedProtocolVersionError: $versionElement")
        }
        val headerVersion = normalizedHeaders["mcp-protocol-version"]
        if (headerVersion != null) {
            if (headerVersion !in REAKTOR_MCP_PROTOCOL_VERSIONS) {
                return null to rpcError(id, MCP_ERROR_UNSUPPORTED_VERSION, "UnsupportedProtocolVersionError: $headerVersion")
            }
            if (version != null && version != headerVersion) {
                return null to rpcError(id, MCP_ERROR_HEADER_MISMATCH, "HeaderMismatchError: MCP-Protocol-Version")
            }
        }
        val headerMethod = normalizedHeaders["mcp-method"]
        if (headerMethod != null && headerMethod != method) {
            return null to rpcError(id, MCP_ERROR_HEADER_MISMATCH, "HeaderMismatchError: MCP-Method")
        }
        val headerName = normalizedHeaders["mcp-name"]
        if (headerName != null) {
            val targetName = (params?.get("name") ?: params?.get("uri"))?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (targetName == null || headerName != targetName) {
                return null to rpcError(id, MCP_ERROR_HEADER_MISMATCH, "HeaderMismatchError: MCP-Name")
            }
        }
        val capabilitiesElement = metadata[MCP_REQUEST_CAPABILITIES]
        if (capabilitiesElement != null && capabilitiesElement !is JsonObject) {
            return null to rpcError(id, MCP_ERROR_MISSING_CAPABILITY, "MissingRequiredClientCapabilityError: clientCapabilities must be an object")
        }
        val clientInfoElement = metadata[MCP_CLIENT_INFO]
        if (clientInfoElement != null && clientInfoElement !is JsonObject) {
            return null to rpcError(id, -32602, "Invalid clientInfo: must be an object")
        }
        val logLevel = (metadata[MCP_LOG_LEVEL] as? JsonPrimitive)?.contentOrNull
        if (logLevel != null && logLevel.lowercase() !in MCP_LOG_LEVELS) {
            return null to rpcError(id, -32602, "Invalid logLevel: $logLevel")
        }
        return McpRequestMeta(
            protocolVersion = version ?: headerVersion,
            clientCapabilities = capabilitiesElement as? JsonObject ?: JsonObject(emptyMap()),
            clientInfo = clientInfoElement as? JsonObject,
            logLevel = logLevel?.lowercase(),
            traceparent = (metadata["traceparent"] as? JsonPrimitive)?.contentOrNull,
            tracestate = (metadata["tracestate"] as? JsonPrimitive)?.contentOrNull,
            baggage = (metadata["baggage"] as? JsonPrimitive)?.contentOrNull,
            inputResponses = params?.get("inputResponses") ?: metadata["inputResponses"],
            requestState = params?.get("requestState") ?: metadata["requestState"],
            raw = metadata,
        ) to null
    }

    private fun modernResponse(
        request: JsonObject,
        response: JsonObject,
        meta: McpRequestMeta,
        activeTraceparent: String? = null,
    ): JsonObject {
        val result = response["result"] as? JsonObject ?: return response
        if (!meta.isModern) return response
        val existingMeta = result["_meta"] as? JsonObject ?: JsonObject(emptyMap())
        val serverInfo = buildJsonObject { put("name", name); put("version", version) }
        val traceMeta = buildMap<String, JsonElement> {
            (activeTraceparent ?: meta.traceparent)?.let { put("traceparent", JsonPrimitive(it)) }
            meta.tracestate?.let { put("tracestate", JsonPrimitive(it)) }
            meta.baggage?.let { put("baggage", JsonPrimitive(it)) }
        }
        val mergedMeta = JsonObject(traceMeta + existingMeta + (MCP_SERVER_INFO to serverInfo))
        val resultType = result["resultType"] ?: JsonPrimitive("complete")
        return rpcResult(request.getValue("id"), JsonObject(mapOf("resultType" to resultType) + result + ("_meta" to mergedMeta)))
    }

    private fun listenSubscriptions(id: JsonElement, params: JsonObject?, meta: McpRequestMeta, connection: McpSubscriptionState): JsonObject {
        val subId = (params?.get("subscriptionId") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: (meta.raw[MCP_SUBSCRIPTION_ID] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: connection.nextId()
        val toolsChanged = (params?.get("toolsListChanged") as? JsonPrimitive)?.booleanOrNull == true
        val promptsChanged = (params?.get("promptsListChanged") as? JsonPrimitive)?.booleanOrNull == true
        val resourcesChanged = (params?.get("resourcesListChanged") as? JsonPrimitive)?.booleanOrNull == true
        val resourceSubElement = params?.get("resourceSubscriptions")
        val allResources = (resourceSubElement as? JsonPrimitive)?.booleanOrNull == true
        val resourceUris = (resourceSubElement as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val explicitKinds = (params?.get("kinds") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toMutableSet()
        if ((params?.get("plans") as? JsonPrimitive)?.booleanOrNull == true || (params?.get("planChanged") as? JsonPrimitive)?.booleanOrNull == true) {
            explicitKinds += "plan"
        }
        if ((params?.get("messages") as? JsonPrimitive)?.booleanOrNull == true || (params?.get("messageChanged") as? JsonPrimitive)?.booleanOrNull == true) {
            explicitKinds += "message"
        }
        if ((params?.get("memory") as? JsonPrimitive)?.booleanOrNull == true || (params?.get("memoryChanged") as? JsonPrimitive)?.booleanOrNull == true) {
            explicitKinds += "memory"
        }
        val subscription = McpSubscription(
            id = subId,
            toolsListChanged = toolsChanged || "toolsListChanged" in explicitKinds,
            promptsListChanged = promptsChanged || "promptsListChanged" in explicitKinds,
            resourcesListChanged = resourcesChanged || "resourcesListChanged" in explicitKinds,
            allResources = allResources || "resourceSubscriptions" in explicitKinds,
            resourceUris = resourceUris,
            kinds = explicitKinds,
            metadata = meta.raw,
        )
        connection.listen(subscription)
        val subscribed = buildList {
            if (subscription.toolsListChanged) add("toolsListChanged")
            if (subscription.promptsListChanged) add("promptsListChanged")
            if (subscription.resourcesListChanged) add("resourcesListChanged")
            if (subscription.allResources || subscription.resourceUris.isNotEmpty()) add("resourceSubscriptions")
            explicitKinds.filterNot { it in setOf("toolsListChanged", "promptsListChanged", "resourcesListChanged", "resourceSubscriptions") }.forEach(::add)
        }
        return rpcResult(id, buildJsonObject {
            put("resultType", "complete")
            put("subscriptionId", subId)
            putJsonArray("subscribed") { subscribed.forEach(::add) }
            putJsonObject("_meta") { put(MCP_SUBSCRIPTION_ID, subId) }
        })
    }

    private fun unlistenSubscriptions(id: JsonElement, params: JsonObject?, connection: McpSubscriptionState): JsonObject {
        val subId = (params?.get("subscriptionId") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing subscriptionId")
        connection.remove(subId)
        return rpcResult(id, buildJsonObject {
            put("resultType", "complete")
            put("subscriptionId", subId)
        })
    }

    private fun initializeRequest(id: JsonElement, params: JsonObject?): JsonObject {
        val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing protocolVersion")
        return rpcResult(id, initialize(requested.takeIf { it in REAKTOR_MCP_PROTOCOL_VERSIONS } ?: REAKTOR_MCP_PROTOCOL_VERSION))
    }

    private fun initialize(protocolVersion: String): JsonObject = buildJsonObject {
        put("protocolVersion", protocolVersion)
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", toolsListChanged) }
            putJsonObject("resources") { put("listChanged", false) }
            if (prompts.isNotEmpty()) putJsonObject("prompts") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", name)
            put("version", version)
        }
        put("instructions", instructions)
    }

    private fun discover(): JsonObject = buildJsonObject {
        put("resultType", "complete")
        putJsonArray("supportedVersions") { REAKTOR_MCP_PROTOCOL_VERSIONS.forEach { add(it) } }
        val capabilities = initialize(REAKTOR_MCP_PROTOCOL_VERSION).getValue("capabilities") as JsonObject
        put("capabilities", buildJsonObject {
            capabilities.forEach { (key, value) -> put(key, value) }
            if (tasks != null) putJsonObject("extensions") { putJsonObject(MCP_TASKS_EXTENSION) {} }
        })
        putJsonObject("serverInfo") { put("name", name); put("version", version) }
        putJsonObject("_meta") { putJsonObject(MCP_SERVER_INFO) { put("name", name); put("version", version) } }
        put("instructions", instructions)
    }

    private fun listTools(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            tools.values.forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                    tool.title?.let { put("title", it) }
                    tool.outputSchema?.let { put("outputSchema", it) }
                    if (tool.alwaysLoad || tool.effect != null) {
                        putJsonObject("_meta") {
                            if (tool.alwaysLoad) put(MCP_ALWAYS_LOAD, true)
                            tool.effect?.let { effect -> put("dev.shibasis.reaktor/effect", effect) }
                        }
                    }
                    putJsonObject("annotations") {
                        put("readOnlyHint", tool.readOnly)
                        put("destructiveHint", tool.destructive)
                        put("idempotentHint", tool.idempotent)
                        put("openWorldHint", tool.openWorld)
                    }
                }
            }
        }
        put("ttlMs", ttlMs)
        put("cacheScope", cacheScope)
    }

    private fun hasCapability(capabilities: JsonObject, required: String): Boolean {
        if (capabilities[required] != null) return true
        val extensions = capabilities["extensions"] as? JsonObject
        return extensions?.get(required) != null
    }

    private fun callTool(id: JsonElement, params: JsonObject?, meta: McpRequestMeta): JsonObject {
        val toolName = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing tool name")
        val tool = tools[toolName] ?: return rpcError(id, -32602, "Unknown tool: $toolName")
        val missingCapability = tool.requiredCapabilities.firstOrNull { !hasCapability(meta.clientCapabilities, it) }
        if (missingCapability != null) {
            return rpcError(id, MCP_ERROR_MISSING_CAPABILITY, "MissingRequiredClientCapabilityError: $missingCapability")
        }
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val metadata = meta.raw
        if (meta.inputResponses != null) {
            if (meta.inputResponses !is JsonObject && meta.inputResponses !is JsonArray) {
                return rpcError(id, -32602, "Invalid inputResponses: must be an object or array")
            }
            val inputHandler = onInputResponse
            if (inputHandler != null) {
                val handled = runCatching { inputHandler(toolName, arguments, meta.inputResponses, meta.requestState, metadata) }
                    .getOrElse { error -> return toolText(id, error.message ?: "Input response failed", isError = true) }
                if (handled != null) return wrapToolOutput(id, tool, toolName, arguments, handled, meta)
            }
        }
        val extensions = meta.clientCapabilities["extensions"] as? JsonObject
        if (meta.isModern && (extensions?.get(MCP_TASKS_EXTENSION) is JsonObject || tasks?.unsolicited(toolName, arguments, metadata) == true)) {
            runCatching { tasks?.call(toolName, arguments, metadata) }
                .getOrElse { return toolText(id, it.message ?: "Task admission failed; inspect receipts before retrying", isError = true) }
                ?.let { return rpcResult(id, it) }
        }
        val invokeBlock = { tool.invoke(arguments, metadata) }
        val result = runCatching { withRequestMeta?.invoke(meta, invokeBlock) ?: invokeBlock() }
            .getOrElse { error -> return toolText(id, error.message ?: "Tool failed", isError = true) }
        return wrapToolOutput(id, tool, toolName, arguments, result, meta)
    }

    private fun wrapToolOutput(
        id: JsonElement,
        tool: McpTool,
        toolName: String,
        arguments: JsonObject,
        result: JsonElement,
        meta: McpRequestMeta,
    ): JsonObject {
        if (result is JsonObject) {
            val resultType = (result["resultType"] as? JsonPrimitive)?.contentOrNull
            if (resultType == "task" || resultType == "input_required") {
                return rpcResult(id, result)
            }
            val outcome = if (tool.raw) result["structuredContent"] as? JsonObject ?: result else result
            if (meta.isModern && (outcome["type"] as? JsonPrimitive)?.contentOrNull == "approval-required") {
                val approvalId = (outcome["approval"] as? JsonPrimitive)?.contentOrNull ?: "approval"
                val message = ((outcome["message"] ?: outcome["reason"]) as? JsonPrimitive)?.contentOrNull
                    ?: "Approval required for $toolName"
                val state = buildJsonObject {
                    put("approval", approvalId)
                    put("tool", toolName)
                    (arguments["operation"] as? JsonPrimitive)?.contentOrNull?.let { put("operation", it) }
                }
                return rpcResult(id, mcpApprovalRequired(approvalId, message, state,
                    (outcome["confirmationPhrase"] as? JsonPrimitive)?.contentOrNull))
            }
        }
        if (tool.raw && result is JsonObject) {
            val boundedRaw = maxAnswerBytes?.let { limit ->
                val content = (result["content"] as? JsonArray)?.map { item ->
                    val obj = item as? JsonObject
                    val text = (obj?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (obj != null && text != null) JsonObject(obj + ("text" to JsonPrimitive(boundMcpText(text, limit, nextPointer?.invoke(toolName, arguments)))))
                    else item
                }?.let(::JsonArray)
                if (content != null) JsonObject(result + ("content" to content)) else result
            } ?: result
            return rpcResult(id, boundedRaw)
        }
        val rawText = result.toString()
        val text = maxAnswerBytes?.let { boundMcpText(rawText, it, nextPointer?.invoke(toolName, arguments)) } ?: rawText
        return rpcResult(id, buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            if (structuredContent || tool.outputSchema != null) put("structuredContent", result as? JsonObject ?: buildJsonObject { put("result", result) })
            put("isError", false)
        })
    }

    private fun listResources(metadata: JsonObject): JsonObject = buildJsonObject {
        putJsonArray("resources") {
            (resources.values + resourceList?.invoke(metadata).orEmpty()).distinctBy(McpReadResource::uri).forEach { resource ->
                addJsonObject {
                    put("uri", resource.uri)
                    put("name", resource.name)
                    put("description", resource.description)
                    put("mimeType", resource.mimeType)
                }
            }
        }
        put("ttlMs", ttlMs)
        put("cacheScope", cacheScope)
    }

    private fun listResourceTemplates(): JsonObject = buildJsonObject {
        putJsonArray("resourceTemplates") {
            resourceTemplates.forEach { template ->
                addJsonObject {
                    put("uriTemplate", template.uriTemplate)
                    put("name", template.name)
                    put("description", template.description)
                    put("mimeType", template.mimeType)
                }
            }
        }
        put("ttlMs", ttlMs)
        put("cacheScope", cacheScope)
    }

    private fun readResource(id: JsonElement, params: JsonObject?, metadata: JsonObject): JsonObject {
        val uri = (params?.get("uri") as? JsonPrimitive)?.contentOrNull
            ?: return rpcError(id, -32602, "Missing resource URI")
        val resource = runCatching { resources[uri] ?: resourceRead?.invoke(uri, metadata) }
            .getOrElse { return rpcError(id, -32603, it.message ?: "Resource lookup failed") }
            ?: return rpcError(id, -32602, "Unknown resource: $uri")
        if (resource.uri != uri) return rpcError(id, -32603, "Resource lookup returned another URI")
        val text = runCatching(resource.read)
            .getOrElse { error -> return rpcError(id, -32603, error.message ?: "Resource read failed") }
        return rpcResult(id, buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("uri", resource.uri)
                    put("mimeType", resource.mimeType)
                    put("text", text)
                }
            }
            put("ttlMs", resource.ttlMs ?: ttlMs)
            put("cacheScope", resource.cacheScope ?: cacheScope)
        })
    }

    private fun listPrompts(): JsonObject = buildJsonObject {
        putJsonArray("prompts") {
            prompts.values.forEach { prompt ->
                addJsonObject {
                    put("name", prompt.name)
                    put("description", prompt.description)
                    putJsonArray("arguments") {
                        prompt.arguments.forEach { argument ->
                            addJsonObject { put("name", argument.name); put("description", argument.description); put("required", argument.required) }
                        }
                    }
                }
            }
        }
        put("ttlMs", ttlMs)
        put("cacheScope", cacheScope)
    }

    private fun getPrompt(id: JsonElement, params: JsonObject?): JsonObject {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull ?: return rpcError(id, -32602, "Missing prompt name")
        val prompt = prompts[name] ?: return rpcError(id, -32602, "Unknown prompt: $name")
        val arguments = (params?.get("arguments") as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
        prompt.arguments.firstOrNull { it.required && arguments[it.name].isNullOrBlank() }?.let { return rpcError(id, -32602, "Missing argument: ${it.name}") }
        val text = runCatching { prompt.render(arguments) }.getOrElse { error -> return rpcError(id, -32603, error.message ?: "Prompt failed") }
        return rpcResult(id, buildJsonObject {
            put("description", prompt.description)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonObject("content") { put("type", "text"); put("text", text) }
                }
            }
        })
    }

    private fun rpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun toolText(id: JsonElement, text: String, isError: Boolean): JsonObject =
        rpcResult(id, buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("isError", isError)
        })
}

internal fun ToolDefinition.mcpValue(): JsonObject = buildJsonObject {
    put("name", name)
    title?.let { put("title", it) }
    put("description", description)
    put("inputSchema", inputSchema)
    outputSchema?.let { put("outputSchema", it) }
    if (alwaysLoad) putJsonObject("_meta") { put(MCP_ALWAYS_LOAD, true) }
    putJsonObject("annotations") {
        put("readOnlyHint", readOnly)
        put("idempotentHint", idempotent)
        put("destructiveHint", destructive)
        put("openWorldHint", openWorld)
    }
}

fun boundMcpText(
    text: String,
    maxBytes: Int = REAKTOR_MCP_DEFAULT_ANSWER_BYTES,
    next: String? = null,
): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (text.encodeToByteArray().size <= maxBytes) return text
    val suffix = next?.takeIf(String::isNotBlank)?.let { if (it.startsWith("next:")) "\n$it" else "\nnext: $it" }.orEmpty()
    val targetBytes = (maxBytes - suffix.encodeToByteArray().size).coerceAtLeast(0)
    var low = 0
    var high = text.length.coerceAtMost(targetBytes)
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (text.substring(0, mid).encodeToByteArray().size <= targetBytes) low = mid else high = mid - 1
    }
    if (low > 0 && text[low - 1].isHighSurrogate()) low--
    return text.substring(0, low) + suffix
}

fun objectSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    put("additionalProperties", false)
}

fun emptyObjectSchema(): JsonObject = objectSchema(emptyMap())

fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun enumSchema(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") { values.forEach { add(it) } }
}

fun jsonArray(items: Iterable<JsonElement>): JsonArray = buildJsonArray { items.forEach(::add) }
