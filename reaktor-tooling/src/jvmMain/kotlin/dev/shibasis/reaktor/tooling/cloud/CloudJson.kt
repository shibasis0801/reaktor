package dev.shibasis.reaktor.tooling.cloud

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
import java.time.Instant

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

class CloudReadFailure(message: String) : Exception(message)
