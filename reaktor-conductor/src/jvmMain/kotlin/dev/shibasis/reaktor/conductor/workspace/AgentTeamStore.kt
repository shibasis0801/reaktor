package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

class AgentTeamStore(private val directory: Path, private val identity: (BoardActor) -> BoardActor = { it },
    private val clock: () -> Long = System::currentTimeMillis) {
    private val file = directory.resolve("team.json")
    private var team: AgentTeam
    private val changed: MutableStateFlow<Long>
    val changes: StateFlow<Long>

    init {
        privateDirectory(directory)
        team = if (Files.exists(file)) load() else AgentTeam()
        changed = MutableStateFlow(team.revision)
        changes = changed.asStateFlow()
    }

    private fun load(): AgentTeam {
        val text = Files.readString(file)
        val json = ConductorJson.parseToJsonElement(text).jsonObject
        if (!TeamLegacy.legacy(json)) return ConductorJson.decodeFromJsonElement(AgentTeam.serializer(), json)
        directory.resolve("team-legacy.json").takeIf { !Files.exists(it) }?.let { atomicWrite(it, text) }
        return ConductorJson.decodeFromJsonElement(AgentTeam.serializer(), TeamLegacy.migrate(json)).also {
            atomicWrite(file, ConductorJson.encodeToString(AgentTeam.serializer(), it))
        }
    }

    @Synchronized fun team(): AgentTeam = team

    @Synchronized fun read(since: Long? = null, reader: BoardActor? = null): TeamRead =
        TeamRead(team, since?.let { team.news(it, reader?.let(identity)) }.orEmpty())

    @Synchronized fun write(patch: TeamPatch, by: BoardActor, saved: (String) -> Boolean = { true }, busy: (String) -> String? = { null }): TeamApplied {
        val applied = TeamRules.apply(team, patch, identity(by), clock(), saved, busy)
        if (applied.team != team) save(applied.team)
        return applied
    }

    private fun save(next: AgentTeam) {
        val text = ConductorJson.encodeToString(AgentTeam.serializer(), next)
        require(text.length <= MaxChars) { "A team is at most ${MaxChars / 1024} KB" }
        atomicWrite(file, text)
        team = next
        changed.value = next.revision
    }

    companion object {
        const val MaxChars = 256 * 1024
    }
}

internal object TeamLegacy {
    private val moved: Map<String, Pair<String, Map<String, String>>> = mapOf(
        "council" to ("fan-in" to mapOf("member-a" to "branch-1", "member-b" to "branch-2", "member-c" to "branch-3", "chair" to "join")),
        "best-of-n" to ("fan-in" to mapOf("builder-a" to "branch-1", "builder-b" to "branch-2", "builder-c" to "branch-3", "judge" to "join")),
        "pair" to ("pair" to mapOf("driver" to "a", "navigator" to "b")),
        "escalation-ladder" to ("chain" to mapOf("first" to "step-1", "second" to "step-2", "last" to "step-3")),
        "lead-workers" to ("hub" to mapOf("lead" to "hub", "worker-a" to "spoke-1", "worker-b" to "spoke-2", "worker-c" to "spoke-3")),
        "adversarial" to ("triangle" to mapOf("builder" to "a", "critic" to "b", "judge" to "join")),
        "planner-engineer-tester" to ("loop" to mapOf("planner" to "step-1", "engineer" to "step-2", "tester" to "step-3")),
        "planner-engineer-optimiser-regulariser" to ("chain" to mapOf("planner" to "step-1", "engineer" to "step-2", "optimiser" to "step-3")),
        "top-down-bottom-up" to ("hub" to mapOf("architect" to "hub", "engineer-a" to "spoke-1", "engineer-b" to "spoke-2", "scout" to "spoke-3")),
    )

    fun legacy(json: JsonObject): Boolean = "variants" !in json && "topologies" !in json && ("structures" in json || "templates" in json)

    fun migrate(json: JsonObject): JsonObject {
        val own = json.objects("templates").map { template ->
            JsonObject(mapOf(
                "id" to template.getValue("id"),
                "title" to template.getValue("title"),
                "summary" to (template["summary"] ?: JsonPrimitive("")),
                "nodes" to JsonArray(template.objects("seats").map { seat -> JsonObject(mapOf("key" to seat.getValue("key"), "name" to (seat["role"] ?: seat.getValue("key")))) }),
                "edges" to JsonArray(template.objects("links").map { link -> JsonObject(link.filterKeys { it in setOf("from", "to", "label") }) }),
            ))
        }
        val ids = own.mapNotNullTo(HashSet()) { it.text("id") }
        val variants = json.objects("structures").mapNotNull { structure ->
            val template = structure.text("template") ?: return@mapNotNull null
            val (topology, keys) = if (template in ids) template to null else moved[template] ?: return@mapNotNull null
            val agents = (structure["seats"] as? JsonObject).orEmpty().mapNotNull { (seat, profile) -> (if (keys == null) seat else keys[seat])?.let { it to profile } }.toMap()
            if (agents.isEmpty()) return@mapNotNull null
            JsonObject(structure.filterKeys { it in setOf("key", "title", "revision", "by") } + mapOf("topology" to JsonPrimitive(topology), "agents" to JsonObject(agents)))
        }
        return JsonObject(json - "structures" - "templates" + mapOf("variants" to JsonArray(variants), "topologies" to JsonArray(own)))
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
