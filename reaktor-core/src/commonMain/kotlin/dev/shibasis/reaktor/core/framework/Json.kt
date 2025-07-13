package dev.shibasis.reaktor.core.framework

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Unfortunately we can't change default behaviour as other libraries may rely on it. So these wrappers.

fun JsonBuilder.defaults() {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

var json = Json {
    defaults()
}

val EMPTY_JSON = JsonObject(emptyMap())

inline fun JsonObject.copy(
    fn: MutableMap<String, JsonElement>.() -> Unit
) = JsonObject(toMutableMap().apply(fn))

