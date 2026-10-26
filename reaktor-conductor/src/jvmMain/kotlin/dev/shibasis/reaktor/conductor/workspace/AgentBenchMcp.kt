package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal fun agentBenchTools(workspace: AgentWorkspace): List<McpTool> {
    val actor = "by" to objectSchema(mapOf("kind" to enumSchema("Actor", BoardActorKind.entries.map { it.name }),
        "name" to stringSchema("Actor name"), "session" to stringSchema("Calling session")), listOf("kind", "name"))
    fun JsonObject.only(vararg keys: String) { require((this.keys - keys.toSet()).isEmpty()) { "Unknown benchmark arguments" } }
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    return listOf(
        McpTool("agent_bench_churn", "Measure 14/30-day Git line survival for retained person-accepted landing receipts, grouped by pinned profile and declared module. Immature windows and unavailable history remain unknown. No routing priorities change.", emptyObjectSchema(), true, true) { args ->
            args.only()
            AgentWorkspaceJson.encodeToJsonElement(AgentChurnReport.serializer(), workspace.benchmarks.churn())
        },
        McpTool("agent_bench_catalog", "Repository benchmark scenarios, exact revisions and retained runs. Each repeat is an ordinary board graph. No native turn starts here.",
            emptyObjectSchema(), true, true) { args ->
            args.only()
            AgentWorkspaceJson.encodeToJsonElement(AgentBenchCatalog.serializer(), workspace.benchmarks.catalog())
        },
        McpTool("agent_bench_propose", "Create isolated benchmark graph proposals for one exact scenario revision and named configurations. configurations: [{id, slots: {profileSlot: TaskSeat}, estimatedTokens?}]. Bind every slot; repeats 1..10. Profiles, models and perspectives are pinned. A person must approve each displayed graph revision. Benchmark candidates can be accepted but never landed.",
            objectSchema(mapOf("scenario" to stringSchema("Scenario id"), "revision" to stringSchema("Scenario content revision"),
                "configurations" to buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "object"); put("additionalProperties", true) }) },
                "repeats" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 10) }, actor),
                listOf("scenario", "revision", "configurations", "repeats", "by")), false, false) { args ->
            args.only("scenario", "revision", "configurations", "repeats", "by")
            val by = AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), args.getValue("by"))
            val configs = AgentWorkspaceJson.decodeFromJsonElement(ListSerializer(AgentBenchConfiguration.serializer()), args.getValue("configurations"))
            AgentWorkspaceJson.encodeToJsonElement(ListSerializer(TaskGraphView.serializer()), workspace.benchmarks.propose(
                args.text("scenario"), args.text("revision"), configs, args.getValue("repeats").jsonPrimitive.int, by))
        },
        McpTool("agent_bench_measure", "Measure a benchmark through kernel-owned scorecards and exact candidate evidence, retaining the observation beside the board. Unknown usage/cost and incomplete runs stay explicit. This does not approve, run, accept or land work.",
            objectSchema(mapOf("graph" to stringSchema("Benchmark graph id")), listOf("graph")), false, true) { args ->
            args.only("graph")
            runBlocking { AgentWorkspaceJson.encodeToJsonElement(AgentBenchResult.serializer(), workspace.benchmarks.measure(args.text("graph"))) }
        },
    )
}
