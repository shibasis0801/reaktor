package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.memory.*
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class LinkedMemories(val facts: List<MemoryFact>, val graph: GraphShape)

class AgentMemoryResources(private val root: File, private val api: MemoryApi, private val revision: () -> String?) {
    fun scopes(actor: BoardActor, thread: String?): List<MemoryScope> = buildList {
        add(MemoryScope(MemoryScopeKind.Workspace, root.canonicalPath))
        add(MemoryScope(MemoryScopeKind.Repository, root.canonicalPath))
        thread?.let { add(MemoryScope(MemoryScopeKind.Plan, it)) }
        if (actor.kind == BoardActorKind.Agent) add(MemoryScope(MemoryScopeKind.Agent, requireNotNull(actor.agent)))
    }

    suspend fun list(actor: BoardActor, thread: String?): List<String> = MemoryResources(api, scopes(actor, thread)).list()

    suspend fun read(uri: String, actor: BoardActor, thread: String?): MemoryFact {
        require(uri.matches(Regex("memory://fact/[a-f0-9]{64}"))) { "Use a canonical memory fact URI" }
        val fact = ConductorJson.decodeFromString(MemoryFact.serializer(), MemoryResources(api, scopes(actor, thread)).read(uri))
        require(fact.status == MemoryStatus.Kept) { "Only reviewed memory is available as a resource" }
        return fact
    }

    suspend fun linked(links: List<String>, actor: BoardActor, thread: String?): LinkedMemories {
        require(links.size in 1..64 && links.all { it.isNotBlank() && it.length <= 2000 })
        val scopes = scopes(actor, thread)
        val facts = links.distinct().flatMap { api.search(MemoryQuery(scopes, link = it, limit = 8)) }
            .distinctBy { it.id }.sortedWith(compareByDescending<MemoryFact> { it.importance }.thenByDescending { it.updatedAt }.thenBy { it.id }).take(8)
        val shape = MemoryGraph(facts, if (facts.isEmpty()) null else revision()).use { it.shape() }
        return LinkedMemories(facts, shape)
    }
}

internal fun agentMemoryResourceTools(workspace: AgentWorkspace): List<McpTool> {
    val properties = mapOf("thread" to stringSchema("Shared plan to include; the authenticated conversation when left out"),
        "by" to buildJsonObject { put("type", "object") })
    fun identity(arguments: JsonObject): Pair<BoardActor, String?> {
        val supplied = AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), arguments.getValue("by"))
        val actor = if (supplied.kind == BoardActorKind.Agent) workspace.identity(supplied).actor else supplied
        val managed = actor.session?.takeIf { it.startsWith("hangar:") }?.removePrefix("hangar:")?.let { workspace.get(it) }
        val thread = arguments["thread"]?.jsonPrimitive?.contentOrNull ?: managed?.let { "hangar:${it.threadId}" } ?: actor.session
        require(thread == null || thread.isNotBlank() && thread.length <= 2000)
        return actor to thread
    }
    return listOf(
        McpTool("agent_memory_resources", "List reviewed memory resources visible to the authenticated session.", objectSchema(properties, listOf("by")), true, true) { arguments ->
            require((arguments.keys - properties.keys).isEmpty())
            val (actor, thread) = identity(arguments)
            buildJsonObject { put("resources", JsonArray(runBlocking { workspace.memory.resources.list(actor, thread) }.map(::JsonPrimitive))) }
        },
        McpTool("agent_memory_resource", "Read a reviewed memory resource with its scope, revision and provenance.",
            objectSchema(properties + ("uri" to stringSchema("memory://fact/<id>")), listOf("uri", "by")), true, true) { arguments ->
            require((arguments.keys - properties.keys - "uri").isEmpty())
            val (actor, thread) = identity(arguments)
            ConductorJson.encodeToJsonElement(MemoryFact.serializer(), runBlocking { workspace.memory.resources.read(arguments.getValue("uri").jsonPrimitive.content, actor, thread) })
        },
        McpTool("agent_memory_linked", "Reviewed assertions linked to exact code references, with MemoryGraph source freshness.",
            objectSchema(properties + ("links" to buildJsonObject { put("type", "array"); put("items", stringSchema("Exact file:, symbol:, module:, graph: or op: reference")); put("minItems", 1); put("maxItems", 64) }), listOf("links", "by")), true, true) { arguments ->
            require((arguments.keys - properties.keys - "links").isEmpty())
            val (actor, thread) = identity(arguments)
            ConductorJson.encodeToJsonElement(LinkedMemories.serializer(), runBlocking { workspace.memory.resources.linked(arguments.getValue("links").jsonArray.map { it.jsonPrimitive.content }, actor, thread) })
        },
    )
}
