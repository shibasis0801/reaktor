package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.Rosters
import java.nio.file.Files
import java.nio.file.Path

class AgentProfileStore(private val directory: Path, private val clock: () -> Long = System::currentTimeMillis) {
    private val builtIns = Rosters.default.associate { spec -> spec.id.value to AgentProfile(
        spec.id.value, spec.name, spec.runtime, spec.model, spec.effort?.value, spec.instructions,
        if (spec.tools.allowWrites) TaskAccess.Write else TaskAccess.Read, spec.tools, revision = 1, builtIn = true,
    ) }

    init { privateDirectory(directory) }

    @Synchronized fun list(): List<AgentProfile> = (builtIns.values + Files.list(directory).use { files ->
        files.filter { it.fileName.toString().matches(Regex("[a-z][a-z0-9-]{0,63}\\.json")) }
            .map { read(it.fileName.toString().removeSuffix(".json")) }.toList()
    }).sortedBy { it.id }

    @Synchronized fun read(id: String): AgentProfile {
        require(Id.matches(id)) { "A profile id is 1 to 64 lowercase letters, digits or dashes, starting with a letter" }
        builtIns[id]?.let { return it }
        val file = directory.resolve("$id.json")
        require(Files.isRegularFile(file)) { "No agent profile named $id" }
        require(Files.size(file) <= 65_536) { "Profile $id exceeds 64 KB" }
        return ConductorJson.decodeFromString(AgentProfile.serializer(), Files.readString(file)).also {
            require(it.id == id) { "Profile file does not match $id" }
        }
    }

    @Synchronized fun save(profile: AgentProfile, by: BoardActor, expectedRevision: Long? = null): AgentProfile {
        require(Id.matches(profile.id)) { "A profile id is 1 to 64 lowercase letters, digits or dashes, starting with a letter" }
        require(profile.id !in builtIns) { "Built-in profile ${profile.id} cannot be edited; copy it with a new id" }
        require(by.name.isNotBlank() && by.name.length <= 100) { "Name who saves the profile" }
        by.session?.let { require(BoardRefs.pattern.matches(it)) { "$it is not a session ref" } }
        val current = if (Files.exists(directory.resolve("${profile.id}.json"))) read(profile.id) else null
        require(current == null || expectedRevision != null) { "Name the profile revision you read before saving ${profile.id}" }
        require(expectedRevision == null || expectedRevision == (current?.revision ?: 0)) { "Profile ${profile.id} changed; read its current revision before saving" }
        TaskGraphRules.validSeat(TaskSeat(profile.runtime, profile.model, profile.effort))
        val next = profile.copy(
            name = BoardText.title(profile.name),
            instructions = BoardText.bounded(profile.instructions, "perspective instructions", 20_000),
            tools = profile.tools.copy(
                allowWrites = profile.access == TaskAccess.Write,
                allow = BoardText.lines(profile.tools.allow, "allowed tools", 100, 1_000),
                deny = BoardText.lines(profile.tools.deny, "denied tools", 100, 1_000),
                additionalDirectories = BoardText.lines(profile.tools.additionalDirectories, "additional directories", 100, 1_000),
                mcpConfig = profile.tools.mcpConfig?.let { BoardText.bounded(it, "MCP config", 1_000) },
            ),
            revision = (current?.revision ?: 0) + 1, by = by, updatedAt = clock(), builtIn = false,
        )
        val text = ConductorJson.encodeToString(AgentProfile.serializer(), next)
        require(text.toByteArray().size <= 65_536) { "A profile is at most 64 KB" }
        atomicWrite(directory.resolve("${next.id}.json"), text)
        return next
    }

    companion object { private val Id = Regex("[a-z][a-z0-9-]{0,63}") }
}
