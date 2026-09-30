package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.nio.file.Files
import java.nio.file.Path

/** Immutable content-addressed history. The mutable head remains compatible with older owners. */
class AgentCheckpoints(private val directory: Path) {
    private fun dir(runId: String): Path {
        require(runId.matches(Regex("[a-f0-9]{64}")))
        return directory.resolve("history/$runId").also(::privateDirectory)
    }
    @Synchronized fun save(runId: String, checkpoint: ProtocolCheckpoint) {
        val text = ConductorJson.encodeToString(ProtocolCheckpoint.serializer(), checkpoint)
        val path = dir(runId).resolve("${digest(text)}.json")
        if (!Files.exists(path)) atomicWrite(path, text)
        atomicWrite(directory.resolve("$runId.json"), text)
    }
    @Synchronized fun list(runId: String): List<AgentCheckpoint> = Files.list(dir(runId)).use { paths ->
        paths.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }
            .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }.thenBy { it.fileName.toString() }).limit(200).toList()
            .map { path ->
                val state = ConductorJson.decodeFromString(ProtocolCheckpoint.serializer(), Files.readString(path))
                AgentCheckpoint(path.fileName.toString().removeSuffix(".json"), runId, Files.getLastModifiedTime(path).toMillis(),
                    state.sourceRevision, state.completed.keys.toList(), state.inFlight.toList(), state.workflow)
            }
    }
    fun read(runId: String, checkpointId: String): ProtocolCheckpoint {
        require(checkpointId.matches(Regex("[a-f0-9]{64}")))
        val text = Files.readString(dir(runId).resolve("$checkpointId.json"))
        require(digest(text) == checkpointId) { "Checkpoint content changed" }
        return ConductorJson.decodeFromString(ProtocolCheckpoint.serializer(), text)
    }
}

class AgentRunbooks(private val directory: Path) {
    init { privateDirectory(directory) }
    private fun path(id: String): Path { require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))); return directory.resolve("$id.json") }
    @Synchronized fun save(definition: WorkflowDefinition, expectedRevision: String? = null): AgentRunbook {
        definition.validate()
        val file = path(definition.id)
        val existing = if (Files.exists(file)) read(definition.id) else null
        val revision = digest(ConductorJson.encodeToString(WorkflowDefinition.serializer(), definition))
        if (existing?.revision == revision) return existing
        require(existing?.revision == expectedRevision) { "Runbook changed; refresh before saving" }
        require(ConductorJson.encodeToString(WorkflowDefinition.serializer(), definition).length <= 100000)
        if (existing == null) require(list().size < 100) { "Runbook library is full" }
        return AgentRunbook(revision, definition).also { atomicWrite(file, ConductorJson.encodeToString(AgentRunbook.serializer(), it)) }
    }
    fun read(id: String) = ConductorJson.decodeFromString(AgentRunbook.serializer(), Files.readString(path(id)))
    @Synchronized fun list(): List<AgentRunbook> = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(".json") }.sorted().limit(100).toList().map { read(it.fileName.toString().removeSuffix(".json")) }
    }
}
