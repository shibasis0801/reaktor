package dev.shibasis.reaktor.cluster.build

import dev.shibasis.reaktor.cluster.ClusterCommands
import dev.shibasis.reaktor.cluster.ClusterLocations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

class RouterState(val state: Path, val workspace: Path = ClusterLocations.workspace(state).toRealPath()) {
    val config: JsonObject = ControlFiles.read(state.resolve("config.json"))
    val host: String = config.text("host") ?: throw IllegalArgumentException("The router configuration names no worker host")
    val remote: String = config.text("remote_root") ?: throw IllegalArgumentException("The router configuration names no worker folder")
    val projects: List<String> = (config["projects"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun slotRoots(): List<Path> {
        val configured = Path.of(config.text("local_slots_root") ?: state.resolve("slots").toString())
        require(configured.isAbsolute && configured.normalize() == configured && configured.parent == state && configured.fileName.toString() in SlotFolders) {
            "Slot root must be a managed state child"
        }
        return (listOf(configured) + SlotFolders.map(state::resolve).filter { Files.exists(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }).distinct().onEach { root ->
            require(generateSequence(root) { it.parent }.none(Files::isSymbolicLink)) { "Slot roots and their ancestors must be real directories" }
            require(!Files.exists(root) || Files.isDirectory(root)) { "Slot root is not a directory" }
        }
    }

    fun slotRemote(index: Int, worktree: String?): String = when {
        worktree != null -> "$remote/.remote-dev/agent-worktrees/$worktree/slots/job-$index"
        index == 0 -> remote
        else -> "$remote/.remote-dev/slots/job-$index"
    }

    fun worktree(workspace: Path, project: String, commands: ClusterCommands): String? {
        val registry = state.resolve("worktrees").takeIf(Files::isDirectory) ?: return null
        val sources = projects.map(this.workspace::resolve).filter(Files::isDirectory).map { it.toRealPath() }.toSet()
        return Files.list(registry).use { files -> files.sorted().toList() }.firstNotNullOfOrNull { file ->
            val identity = file.fileName.toString().removeSuffix(".json")
            if (Files.isSymbolicLink(file) || !Worktree.matches(identity) || !file.fileName.toString().endsWith(".json")) return@firstNotNullOfOrNull null
            runCatching {
                val roots = (Json.parseToJsonElement(Files.readString(file)) as JsonObject)["roots"] as JsonArray
                val pairs = roots.map { root -> (root as JsonObject).let { Path.of(it.text("source")!!).toRealPath() to Path.of(it.text("path")!!).toRealPath() } }
                require(pairs.size in 1..16 && pairs.map { it.first.fileName }.toSet().size == pairs.size && pairs.map { it.second.parent }.toSet().size == 1)
                pairs.forEach { (source, checkout) ->
                    require(source in sources && checkout.fileName == source.fileName && checkout != source && Files.isRegularFile(checkout.resolve(".git")))
                    require(commonDirectory(source, commands) == commonDirectory(checkout, commands))
                }
                identity.takeIf { pairs.first().second.parent == workspace && pairs.any { it.second == workspace.resolve(project) } }
            }.getOrNull()
        }
    }

    private fun commonDirectory(directory: Path, commands: ClusterCommands): String =
        commands.run(listOf("git", "-C", directory.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir"), 10)
            .also { require(it.exitCode == 0) }.output.trim().let { Path.of(it).toRealPath().toString() }

    companion object {
        private val SlotFolders = listOf("slots", "slots.noindex")
        private val Worktree = Regex("[a-f0-9]{64}")
    }
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
