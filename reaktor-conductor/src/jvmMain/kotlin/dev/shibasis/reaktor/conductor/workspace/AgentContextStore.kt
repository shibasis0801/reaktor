package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import java.nio.file.Files
import java.nio.file.Path

class AgentContextStore(
    private val directory: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val builtIns: Map<String, SystemContext> = SystemContexts.builtIns.associateBy { it.id }

    init {
        privateDirectory(directory)
    }

    @Synchronized
    fun list(): List<SystemContext> {
        val saved = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().matches(FilePattern) }
                .map { read(it.fileName.toString().removeSuffix(".json")) }
                .toList()
        }.associateBy { it.id }
        return (builtIns.values.filter { it.id !in saved } + saved.values).sortedWith(compareBy({ it.builtIn }, { it.name.lowercase() }, { it.id }))
    }

    @Synchronized
    fun read(id: String): SystemContext {
        require(Id.matches(id)) { "A context id is 1 to 64 lowercase letters, digits or dashes, starting with a letter" }
        val file = directory.resolve("$id.json")
        if (!Files.isRegularFile(file)) return requireNotNull(builtIns[id]) { "No system context named $id" }
        require(Files.size(file) <= MaxBytes) { "System context $id exceeds 64 KB" }
        return ConductorJson.decodeFromString(SystemContext.serializer(), Files.readString(file)).also {
            require(it.id == id) { "Context file does not match $id" }
        }
    }

    @Synchronized
    fun save(context: SystemContext, by: BoardActor, expectedRevision: Long? = null): SystemContext {
        require(Id.matches(context.id)) { "A context id is 1 to 64 lowercase letters, digits or dashes, starting with a letter" }
        require(by.name.isNotBlank() && by.name.length <= 100) { "Name who saves the system context" }
        by.session?.let { require(BoardRefs.pattern.matches(it)) { "$it is not a session ref" } }
        val current = if (Files.exists(directory.resolve("${context.id}.json"))) read(context.id) else null
        require(current == null || expectedRevision != null) { "Name the context revision you read before saving ${context.id}" }
        require(expectedRevision == null || expectedRevision == (current?.revision ?: 0)) {
            "System context ${context.id} changed; read its current revision before saving"
        }
        val next = context.copy(
            name = BoardText.title(context.name),
            summary = BoardText.bounded(context.summary, "context summary", 500),
            instructions = BoardText.bounded(context.instructions, "system context instructions", 20_000),
            tools = context.tools.copy(
                allowWrites = context.access == TaskAccess.Write,
                allow = BoardText.lines(context.tools.allow, "allowed tools", 100, 1_000),
                deny = BoardText.lines(context.tools.deny, "denied tools", 100, 1_000),
                additionalDirectories = BoardText.lines(context.tools.additionalDirectories, "additional directories", 100, 1_000),
                mcpConfig = context.tools.mcpConfig?.let { BoardText.bounded(it, "MCP config", 1_000) },
            ),
            planRefs = BoardText.lines(context.planRefs, "plan refs", 64, 240),
            revision = (current?.revision ?: 0) + 1,
            by = by,
            updatedAt = clock(),
            builtIn = false,
        )
        val encoded = ConductorJson.encodeToString(SystemContext.serializer(), next)
        require(encoded.toByteArray().size <= MaxBytes) { "A system context is at most 64 KB" }
        atomicWrite(directory.resolve("${next.id}.json"), encoded)
        return next
    }

    companion object {
        private const val MaxBytes = 65_536L
        private val Id = Regex("[a-z][a-z0-9-]{0,63}")
        private val FilePattern = Regex("[a-z][a-z0-9-]{0,63}\\.json")
    }
}
