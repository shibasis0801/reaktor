package dev.shibasis.reaktor.cluster

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class ClusterBuildSettings(
    val revision: String,
    val localMaxWorkers: Int,
    val workerSlots: Int,
    val additionalSlotMaxWorkers: Int,
    val localSlots: Int = 1,
)

@Serializable
data class ClusterBuildSettingsUpdate(
    val expectedRevision: String,
    val localMaxWorkers: Int,
    val workerSlots: Int,
    val additionalSlotMaxWorkers: Int,
    val localSlots: Int? = null,
)

class ClusterSettingsConflictException : IllegalStateException("Cluster settings changed since they were loaded. Refresh the settings and review your changes again.")

/** Settings are read by each new router process; existing routers retain their loaded policy. */
class ClusterSettings(state: Path = ClusterLocations.state()) {
    private val state = state.toAbsolutePath().normalize()
    private val config = this.state.resolve("config.json")
    private val json = Json { prettyPrint = true }

    fun read(): ClusterBuildSettings = source().let { snapshot(it.first, it.second) }

    fun update(request: ClusterBuildSettingsUpdate): ClusterBuildSettings {
        require(request.expectedRevision.matches(Regex("[0-9a-f]{64}"))) { "A settings revision is required" }
        validate(request.localMaxWorkers, request.workerSlots, request.additionalSlotMaxWorkers)
        request.localSlots?.let(::validateLocalSlots)
        synchronized(writes) {
            requireState()
            FileChannel.open(state.resolve("cluster-settings.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use {
                    val (bytes, document) = source()
                    val current = snapshot(bytes, document)
                    if (current.revision != request.expectedRevision) throw ClusterSettingsConflictException()
                    val localSlots = request.localSlots ?: current.localSlots
                    if (current.localMaxWorkers == request.localMaxWorkers && current.workerSlots == request.workerSlots &&
                        current.additionalSlotMaxWorkers == request.additionalSlotMaxWorkers && current.localSlots == localSlots) return current
                    val changed = JsonObject(document + mapOf(
                        "local_max_workers" to JsonPrimitive(request.localMaxWorkers),
                        "local_slots" to JsonPrimitive(localSlots),
                        "worker_slots" to JsonPrimitive(request.workerSlots),
                        "worker_slot_max_workers" to JsonPrimitive(request.additionalSlotMaxWorkers),
                    ))
                    val encoded = (json.encodeToString(JsonObject.serializer(), changed) + "\n").encodeToByteArray()
                    val permissions = Files.getPosixFilePermissions(config, NOFOLLOW_LINKS)
                    val temporary = Files.createTempFile(state, ".cluster-settings-", ".json", PosixFilePermissions.asFileAttribute(permissions))
                    try {
                        // File creation applies the process umask even with explicit attributes.
                        Files.setPosixFilePermissions(temporary, permissions)
                        FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                            val buffer = ByteBuffer.wrap(encoded)
                            while (buffer.hasRemaining()) output.write(buffer)
                            output.force(true)
                        }
                        if (digest(source().first) != request.expectedRevision) throw ClusterSettingsConflictException()
                        Files.move(temporary, config, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temporary) }
                    return snapshot(encoded, changed)
                }
            }
        }
    }

    private fun requireState() {
        require(Files.isDirectory(state, NOFOLLOW_LINKS) && generateSequence(state) { it.parent }.none(Files::isSymbolicLink)) {
            "Cluster state must be an existing directory without symbolic links"
        }
    }

    private fun source(): Pair<ByteArray, JsonObject> {
        requireState()
        require(Files.isRegularFile(config, NOFOLLOW_LINKS) && Files.size(config) <= 1_048_576) { "Cluster configuration is unavailable or too large" }
        val bytes = Files.readAllBytes(config)
        require(bytes.size <= 1_048_576) { "Cluster configuration is too large" }
        val document = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            ?: throw IllegalArgumentException("Cluster configuration must be a JSON object")
        return bytes to document
    }

    private fun snapshot(bytes: ByteArray, document: JsonObject): ClusterBuildSettings {
        fun number(key: String, default: Int): Int {
            val value = document[key] ?: return default
            return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw IllegalArgumentException("Invalid cluster setting: $key")
        }
        val local = number("local_max_workers", 6)
        val slots = number("worker_slots", 2)
        val additional = number("worker_slot_max_workers", 6)
        val localSlots = number("local_slots", 1)
        validate(local, slots, additional)
        validateLocalSlots(localSlots)
        return ClusterBuildSettings(digest(bytes), local, slots, additional, localSlots)
    }

    private fun validateLocalSlots(slots: Int) {
        require(slots in 1..2) { "This Mac supports one or two build lanes" }
    }

    private fun validate(local: Int, slots: Int, additional: Int) {
        require(local in 1..64) { "Local workers must be between 1 and 64" }
        require(slots in 1..3) { "Worker slots must be between 1 and 3" }
        require(additional in 1..64) { "Additional-slot workers must be between 1 and 64" }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object { val writes = Any() }
}
