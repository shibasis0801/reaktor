package dev.shibasis.reaktor.conductor.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant readers for harness output.
 *
 * A harness is owned by someone else and its stream will grow fields and event types without
 * warning. Every accessor here returns null rather than throwing, so an unrecognised shape costs
 * one ignored line instead of a failed agent turn.
 */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.content

internal fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.nested(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
