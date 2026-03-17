package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.js.Promise

@OptIn(DelicateCoroutinesApi::class)
internal fun <T> promiseOf(block: suspend () -> T): Promise<T> =
    GlobalScope.promise { block() }

internal fun JsonElement.toJsonText(): String =
    json.encodeToString(JsonElement.serializer(), this)

internal fun parseJsonTextOrNull(value: String?): JsonElement? =
    value?.let { encoded -> runCatching { json.parseToJsonElement(encoded) }.getOrNull() }

internal fun anyToStringMap(value: Any?): Map<String, String> {
    if (value == null) return emptyMap()
    val dynamicValue = value.asDynamic()
    val entries = js("Object.entries(dynamicValue)") as Array<Array<dynamic>>
    return entries.mapNotNull { entry ->
        val key = entry.getOrNull(0)?.toString() ?: return@mapNotNull null
        val text = entry.getOrNull(1)?.toString() ?: return@mapNotNull null
        key to text
    }.toMap()
}

internal fun JsonObject.toPlainJsObject(): Any {
    val result = js("({})")
    entries.forEach { (key, value) ->
        result[key] = value.toDynamic()
    }
    return result.unsafeCast<Any>()
}
