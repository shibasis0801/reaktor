package dev.shibasis.reaktor.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpProtocolTest {
    private val log = buildJsonObject {
        put("type", "completed")
        putJsonObject("result") { putJsonArray("turns") { addJsonObject { put("prompt", "Read the log") } } }
    }

    @Test
    fun aToolResultTravelsAsCompactTextBesideItsStructuredCopy() {
        val result = call(server())
        assertEquals(log.toString(), result.text())
        assertEquals(log, result.getValue("structuredContent"))
    }

    @Test
    fun aServerForModelsSendsEachResultOnce() {
        val result = call(server(structuredContent = false))
        assertEquals(log, Json.parseToJsonElement(result.text()))
        assertNull(result["structuredContent"])
    }

    @Test
    fun aPromptIsListedAndRenderedOnlyWithItsRequiredArguments() {
        val server = ReaktorMcpServer("test", "1", "", emptyList(), prompts = listOf(
            McpPrompt("hangar", "Plans a request", listOf(McpPromptArgument("request", "What to do", required = true))) { "Plan: ${it.getValue("request")}" }))
        fun answer(body: String) = server.handle(body)!!.jsonObject
        val capabilities = answer("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")
            .getValue("result").jsonObject.getValue("capabilities").jsonObject
        assertEquals(setOf("tools", "resources", "prompts"), capabilities.keys)
        val listed = answer("""{"jsonrpc":"2.0","id":2,"method":"prompts/list"}""").getValue("result").jsonObject.getValue("prompts").jsonArray.single().jsonObject
        assertEquals("hangar", listed.getValue("name").jsonPrimitive.content)
        assertEquals("request", listed.getValue("arguments").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        val message = answer("""{"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"hangar","arguments":{"request":"split the parser"}}}""")
            .getValue("result").jsonObject.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals("Plan: split the parser", message.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(-32602, answer("""{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"hangar"}}""")
            .getValue("error").jsonObject.getValue("code").jsonPrimitive.content.toInt())
        assertNull(ReaktorMcpServer("test", "1", "", emptyList()).handle("""{"jsonrpc":"2.0","id":5,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")!!
            .jsonObject.getValue("result").jsonObject.getValue("capabilities").jsonObject["prompts"])
    }

    @Test
    fun statelessModernRequestsValidateMetaCarryServerInfoAndKeepLegacyWorking() {
        var observedRootConversation: String? = null
        val server = ReaktorMcpServer(
            name = "reaktor",
            version = "2.0",
            instructions = "Stateless door",
            tools = listOf(
                McpTool(
                    name = "kernel",
                    description = "Kernel gate",
                    inputSchema = emptyObjectSchema(),
                    readOnly = false,
                    idempotent = false,
                    requiredCapabilities = setOf("sampling"),
                    executeWithMeta = { _, meta ->
                        observedRootConversation = meta["antigravity.google/root_conversation_id"]?.jsonPrimitive?.content
                        log
                    },
                ) { log },
            ),
        )
        val discovered = server.handle("""{"jsonrpc":"2.0","id":1,"method":"server/discover"}""")!!.jsonObject.getValue("result").jsonObject
        assertEquals("complete", discovered.getValue("resultType").jsonPrimitive.content)
        assertEquals(setOf("2025-06-18", "2025-11-25", "2026-07-28"), discovered.getValue("supportedVersions").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("reaktor", discovered.getValue("_meta").jsonObject.getValue(MCP_SERVER_INFO).jsonObject.getValue("name").jsonPrimitive.content)

        val unsupported = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/list")
            putJsonObject("params") { putJsonObject("_meta") { put(MCP_REQUEST_VERSION, "2099-01-01") } }
        }.toString())!!.jsonObject.getValue("error").jsonObject
        assertEquals(MCP_ERROR_UNSUPPORTED_VERSION, unsupported.getValue("code").jsonPrimitive.content.toInt())

        val headerMismatch = server.handle(
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 3); put("method", "tools/list")
                putJsonObject("params") { putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) } }
            }.toString(),
            headers = mapOf("MCP-Method" to "tools/call"),
        )!!.jsonObject.getValue("error").jsonObject
        assertEquals(MCP_ERROR_HEADER_MISMATCH, headerMismatch.getValue("code").jsonPrimitive.content.toInt())

        val missingCap = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 4); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "kernel")
                putJsonObject("_meta") {
                    put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION)
                    putJsonObject(MCP_REQUEST_CAPABILITIES) {}
                }
            }
        }.toString())!!.jsonObject.getValue("error").jsonObject
        assertEquals(MCP_ERROR_MISSING_CAPABILITY, missingCap.getValue("code").jsonPrimitive.content.toInt())

        val removedPing = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 5); put("method", "ping")
            putJsonObject("params") { putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) } }
        }.toString())!!.jsonObject.getValue("error").jsonObject
        assertEquals(-32601, removedPing.getValue("code").jsonPrimitive.content.toInt())

        val legacyPing = server.handle("""{"jsonrpc":"2.0","id":6,"method":"ping"}""")!!.jsonObject
        assertNull(legacyPing["error"])

        val modernCall = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 7); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "kernel")
                putJsonObject("_meta") {
                    put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION)
                    put(MCP_LOG_LEVEL, "info")
                    put("antigravity.google/root_conversation_id", "conv-42")
                    putJsonObject(MCP_CLIENT_INFO) { put("name", "agy"); put("version", "1.4.10") }
                    putJsonObject(MCP_REQUEST_CAPABILITIES) { putJsonObject("sampling") {} }
                }
            }
        }.toString())!!.jsonObject.getValue("result").jsonObject
        assertEquals("complete", modernCall.getValue("resultType").jsonPrimitive.content)
        assertEquals("reaktor", modernCall.getValue("_meta").jsonObject.getValue(MCP_SERVER_INFO).jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("conv-42", observedRootConversation)
        assertEquals("info", server.lastRequestMeta?.logLevel)
        assertEquals("agy", server.lastRequestMeta?.clientInfo?.getValue("name")?.jsonPrimitive?.content)
    }

    @Test
    fun listAndReadResultsCarryCacheMetadataAlwaysLoadAndBoundedAnswers() {
        val server = ReaktorMcpServer(
            name = "reaktor",
            version = "1.0",
            instructions = "",
            tools = listOf("kernel", "atlas_query", "atlas_read", "change_plan", "change_apply", "check_run").map { name ->
                McpTool(name, "Tool $name", emptyObjectSchema(), readOnly = true, idempotent = true) {
                    JsonPrimitive("x".repeat(12_000))
                }
            },
            resources = listOf(McpReadResource("memory://plan", "Plan memory", "Shared facts", ttlMs = 15_000L, cacheScope = "public") { "{}" }),
            resourceTemplates = listOf(McpResourceTemplate("memory://{scope}", "Scoped memory", "Memory by scope")),
            prompts = listOf(McpPrompt("hangar", "Plan") { "ok" }),
            ttlMs = 30_000L,
            cacheScope = "private",
            maxAnswerBytes = REAKTOR_MCP_DEFAULT_ANSWER_BYTES,
            nextPointer = { tool, _ -> "$tool {\"offset\":10240}" },
        )
        fun ask(method: String, params: JsonObject = JsonObject(emptyMap())) = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method)
            put("params", JsonObject(params + ("_meta" to buildJsonObject { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) })))
        }.toString())!!.jsonObject.getValue("result").jsonObject

        val toolsResult = ask("tools/list")
        assertEquals("complete", toolsResult.getValue("resultType").jsonPrimitive.content)
        assertEquals(30_000L, toolsResult.getValue("ttlMs").jsonPrimitive.content.toLong())
        assertEquals("private", toolsResult.getValue("cacheScope").jsonPrimitive.content)
        val listedTools = toolsResult.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("kernel", "atlas_query", "atlas_read", "change_plan", "change_apply", "check_run"), listedTools.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(6, listedTools.count { it.getValue("_meta").jsonObject.getValue(MCP_ALWAYS_LOAD).jsonPrimitive.content.toBoolean() })

        val promptsResult = ask("prompts/list")
        assertEquals(30_000L, promptsResult.getValue("ttlMs").jsonPrimitive.content.toLong())
        assertEquals("private", promptsResult.getValue("cacheScope").jsonPrimitive.content)

        val resourcesResult = ask("resources/list")
        assertEquals(30_000L, resourcesResult.getValue("ttlMs").jsonPrimitive.content.toLong())
        assertEquals("private", resourcesResult.getValue("cacheScope").jsonPrimitive.content)

        val templatesResult = ask("resources/templates/list")
        assertEquals(30_000L, templatesResult.getValue("ttlMs").jsonPrimitive.content.toLong())
        assertEquals("private", templatesResult.getValue("cacheScope").jsonPrimitive.content)
        assertEquals("memory://{scope}", templatesResult.getValue("resourceTemplates").jsonArray.single().jsonObject.getValue("uriTemplate").jsonPrimitive.content)

        val readResult = ask("resources/read", buildJsonObject { put("uri", "memory://plan") })
        assertEquals(15_000L, readResult.getValue("ttlMs").jsonPrimitive.content.toLong())
        assertEquals("public", readResult.getValue("cacheScope").jsonPrimitive.content)

        val missingResource = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 2); put("method", "resources/read")
            putJsonObject("params") { put("uri", "memory://missing") }
        }.toString())!!.jsonObject.getValue("error").jsonObject
        assertEquals(-32602, missingResource.getValue("code").jsonPrimitive.content.toInt())

        val called = ask("tools/call", buildJsonObject { put("name", "atlas_query") })
        val text = called.text()
        kotlin.test.assertTrue(text.encodeToByteArray().size <= REAKTOR_MCP_DEFAULT_ANSWER_BYTES)
        kotlin.test.assertTrue(text.endsWith("\nnext: atlas_query {\"offset\":10240}"))
    }

    @Test
    fun subscriptionsListenStreamsTaggedNotificationsAndKeepsProgressRequestScoped() {
        val server = ReaktorMcpServer("reaktor", "1.0", "", emptyList(), toolsListChanged = true)
        val emitted = mutableListOf<JsonObject>()
        server.onNotification { emitted += it.jsonObject }

        val ack = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "subscriptions/listen")
            putJsonObject("params") {
                put("subscriptionId", "sub-door-1")
                put("toolsListChanged", true)
                put("resourceSubscriptions", buildJsonArray { add(JsonPrimitive("memory://shared")) })
                put("plans", true)
                put("messages", true)
                put("memory", true)
                putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) }
            }
        }.toString())!!.jsonObject.getValue("result").jsonObject

        assertEquals("complete", ack.getValue("resultType").jsonPrimitive.content)
        assertEquals("sub-door-1", ack.getValue("subscriptionId").jsonPrimitive.content)
        assertEquals("sub-door-1", ack.getValue("_meta").jsonObject.getValue(MCP_SUBSCRIPTION_ID).jsonPrimitive.content)

        server.notifyToolsListChanged()
        server.notifyResourceUpdated("memory://shared")
        server.notifyResourceUpdated("memory://other")
        server.notifyPlanChanged(buildJsonObject { put("thread", "hangar:launch"); put("revision", 285) })
        server.notifyMessageChanged(buildJsonObject { put("to", "s2") })
        server.notifyMemoryChanged(buildJsonObject { put("scope", "repository") })

        assertEquals(
            listOf(
                "notifications/tools/list_changed",
                "notifications/resources/updated",
                "notifications/reaktor/plan_changed",
                "notifications/reaktor/message_changed",
                "notifications/reaktor/memory_changed",
            ),
            emitted.map { it.getValue("method").jsonPrimitive.content },
        )
        kotlin.test.assertTrue(emitted.all {
            it.getValue("params").jsonObject.getValue("_meta").jsonObject.getValue(MCP_SUBSCRIPTION_ID).jsonPrimitive.content == "sub-door-1"
        })

        val progress = server.progressNotification(JsonPrimitive("req-7"), progress = 50, total = 100, message = "Running checks")
        assertEquals("notifications/progress", progress.getValue("method").jsonPrimitive.content)
        assertNull(progress.getValue("params").jsonObject["_meta"])
    }

    @Test
    fun approvalsReturnInputRequiredAndCompleteWhenRetriedWithInputResponses() {
        var approved = false
        val server = ReaktorMcpServer(
            name = "reaktor",
            version = "1.0",
            instructions = "",
            tools = listOf(
                McpTool("kernel", "Kernel gate", emptyObjectSchema(), readOnly = false, idempotent = false, destructive = true) {
                    if (!approved) {
                        buildJsonObject {
                            put("type", "approval-required")
                            put("approval", "apr-99")
                            put("message", "Approve deploy.run in production")
                        }
                    } else {
                        buildJsonObject { put("type", "completed"); put("status", "deployed") }
                    }
                },
            ),
            onInputResponse = { _, _, inputResponses, requestState, _ ->
                val stateApproval = requestState?.jsonObject?.get("approval")?.jsonPrimitive?.content
                val decision = inputResponses.jsonObject[stateApproval]?.jsonObject?.get("approved")?.jsonPrimitive?.content?.toBoolean()
                if (decision == true) {
                    approved = true
                    null
                } else {
                    buildJsonObject { put("type", "refused"); put("reason", "Rejected by person") }
                }
            },
        )
        val first = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "kernel")
                putJsonObject("arguments") { put("operation", "deploy.run") }
                putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) }
            }
        }.toString())!!.jsonObject.getValue("result").jsonObject

        assertEquals("input_required", first.getValue("resultType").jsonPrimitive.content)
        val inputReq = first.getValue("inputRequests").jsonArray.single().jsonObject
        assertEquals("apr-99", inputReq.getValue("id").jsonPrimitive.content)
        assertEquals("approval", inputReq.getValue("kind").jsonPrimitive.content)
        val state = first.getValue("requestState").jsonObject
        assertEquals("apr-99", state.getValue("approval").jsonPrimitive.content)

        val retried = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 2); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "kernel")
                putJsonObject("arguments") { put("operation", "deploy.run") }
                put("requestState", state)
                putJsonObject("inputResponses") { putJsonObject("apr-99") { put("approved", true) } }
                putJsonObject("_meta") { put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION) }
            }
        }.toString())!!.jsonObject.getValue("result").jsonObject

        assertEquals("complete", retried.getValue("resultType").jsonPrimitive.content)
        assertEquals("deployed", retried.getValue("structuredContent").jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun traceContextFromMetaFlowsIntoRecordedServiceSpans() {
        val spans = dev.shibasis.reaktor.service.SpanBuffer()
        val server = ReaktorMcpServer(
            name = "reaktor-door",
            version = "1.0",
            instructions = "",
            tools = listOf(McpTool("atlas_query", "Query", emptyObjectSchema(), readOnly = true, idempotent = true) { log }),
            spanSink = spans,
        )
        val incomingParent = "00-11111111111111111111111111111111-2222222222222222-01"
        val response = server.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") {
                put("name", "atlas_query")
                putJsonObject("arguments") {}
                putJsonObject("_meta") {
                    put(MCP_REQUEST_VERSION, MCP_TASKS_VERSION)
                    put("traceparent", incomingParent)
                    put("tracestate", "reaktor=door")
                    put("baggage", "reaktor-session=sess-door-1")
                }
            }
        }.toString())!!.jsonObject.getValue("result").jsonObject

        val recorded = spans.drain().single()
        assertEquals("11111111111111111111111111111111", recorded.traceId)
        assertEquals("2222222222222222", recorded.parentId)
        assertEquals("reaktor-door", recorded.island)
        assertEquals("mcp", recorded.contract)
        assertEquals("atlas_query", recorded.operation)
        assertEquals("sess-door-1", recorded.applicationSession)
        assertEquals("reaktor=door", recorded.decisions["tracestate"])
        assertEquals("reaktor-session=sess-door-1", recorded.decisions["baggage"])
        val resultMeta = response.getValue("_meta").jsonObject
        assertEquals("00-11111111111111111111111111111111-${recorded.spanId}-01", resultMeta.getValue("traceparent").jsonPrimitive.content)
        assertEquals("reaktor=door", resultMeta.getValue("tracestate").jsonPrimitive.content)
        assertEquals("reaktor-session=sess-door-1", resultMeta.getValue("baggage").jsonPrimitive.content)
    }

    private fun server(structuredContent: Boolean = true) = ReaktorMcpServer("test", "1", "", listOf(
        McpTool("session_log", "Reads a session.", emptyObjectSchema(), readOnly = true, idempotent = true) { log },
    ), structuredContent = structuredContent)

    private fun call(server: ReaktorMcpServer) =
        server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"session_log","arguments":{}}}""")!!
            .jsonObject.getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
}
