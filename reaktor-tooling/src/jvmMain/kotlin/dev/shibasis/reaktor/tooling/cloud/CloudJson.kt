package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

internal val cloudJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.obj(key: String): JsonObject = ((this as? JsonObject)?.get(key) as? JsonObject) ?: JsonObject(emptyMap())
internal fun JsonElement?.objects(key: String): List<JsonObject> =
    ((this as? JsonObject)?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonElement?.strings(key: String): List<String> =
    ((this as? JsonObject)?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
internal fun JsonElement?.text(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
internal fun JsonElement?.number(key: String): Double? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonElement?.long(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
internal fun JsonElement?.flag(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement?.list(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun instantMillis(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal class CommandFailure(message: String) : Exception(message)

class CloudReadFailure(message: String) : Exception(message)

internal object CommandLine {
    suspend fun json(
        command: List<String>,
        timeoutSeconds: Long = 60,
        environment: Map<String, String> = emptyMap(),
        directory: File? = null,
    ): JsonElement {
        val output = run(command, timeoutSeconds, environment, directory)
        return cloudJson.parseToJsonElement(output.ifBlank { "null" })
    }

    suspend fun run(
        command: List<String>,
        timeoutSeconds: Long = 60,
        environment: Map<String, String> = emptyMap(),
        directory: File? = null,
    ): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .apply {
                environment().putAll(environment)
                directory?.let(::directory)
            }
            .start()
        coroutineScope {
            val stdout = async(Dispatchers.IO) { process.inputStream.readBytes().decodeToString() }
            val stderr = async(Dispatchers.IO) { process.errorStream.readBytes().decodeToString() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw CommandFailure("${command.first().substringAfterLast('/')} did not finish within $timeoutSeconds s")
            }
            val out = stdout.await()
            val err = stderr.await()
            if (process.exitValue() != 0 || "did not succeed" in err) {
                throw CommandFailure(err.lineSequence().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("WARNING") && "Python 3.9" !in it && "CLOUDSDK_PYTHON" !in it }
                    .joinToString(" ").take(400).ifBlank { "${command.first()} exited with ${process.exitValue()}" })
            }
            out
        }
    }
}
