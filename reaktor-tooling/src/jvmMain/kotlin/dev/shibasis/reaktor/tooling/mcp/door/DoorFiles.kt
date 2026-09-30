package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.CallCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.concurrent.ConcurrentHashMap

/**
 * What each provider offered when it was last asked.
 *
 * Listing reads from here, so a provider that is slow to start or not running still shows its
 * calls, and a description that changed behind our back shows up as a different digest.
 */
class CallSnapshots(private val directory: Path) {
    private val loaded = ConcurrentHashMap<String, CallCatalog>()

    fun read(provider: String): CallCatalog? = loaded[provider] ?: runCatching {
        val file = directory.resolve("$provider.json")
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return null
        DoorJson.decodeFromString(CallCatalog.serializer(), Files.readString(file)).takeIf { it.provider == provider }
    }.getOrNull()?.also { loaded[provider] = it }

    /** Returns true when the provider now offers something different from what was last recorded. */
    fun write(catalog: CallCatalog): Boolean {
        val changed = read(catalog.provider)?.digest != catalog.digest
        loaded[catalog.provider] = catalog
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, catalog.provider, ".tmp")
        Files.writeString(temporary, DoorJson.encodeToString(CallCatalog.serializer(), catalog))
        Files.move(temporary, directory.resolve("${catalog.provider}.json"), ATOMIC_MOVE, REPLACE_EXISTING)
        return changed
    }

    private companion object { const val MAX_BYTES = 8_000_000L }
}

/**
 * One line per call. Arguments are recorded as a digest only: they can carry source, queries and
 * paths, and a log that is safe to read is worth more than one that is complete.
 */
class CallLog(private val file: Path, private val caller: CallCaller) {
    @Synchronized
    fun record(provider: String, call: String, arguments: JsonObject, millis: Long, outcome: String, detail: String? = null) {
        runCatching {
            Files.createDirectories(file.parent)
            if (Files.isRegularFile(file) && Files.size(file) > ROTATE_BYTES)
                Files.move(file, file.resolveSibling(file.fileName.toString().removeSuffix(".jsonl") + ".1.jsonl"), REPLACE_EXISTING)
            Files.writeString(file, buildJsonObject {
                put("at", System.currentTimeMillis())
                caller.seat?.let { put("seat", it) }
                caller.runId?.let { put("runId", it) }
                put("provider", provider)
                put("call", call)
                put("arguments", sha256Hex(arguments.toString()).take(16))
                put("millis", millis)
                put("outcome", outcome)
                detail?.let { put("detail", it.take(MAX_DETAIL)) }
            }.toString() + "\n", CREATE, APPEND)
        }
    }

    private companion object {
        const val ROTATE_BYTES = 5_000_000L
        const val MAX_DETAIL = 300
    }
}
