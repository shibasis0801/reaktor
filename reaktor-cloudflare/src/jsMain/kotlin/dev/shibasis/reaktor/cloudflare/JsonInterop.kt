package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonElement.Companion.serializer
import kotlinx.serialization.json.JsonNull
import kotlin.js.JSON

internal fun dynamicToJsonElement(value: Any?): JsonElement {
    if (value == null) {
        return JsonNull
    }

    val serialized = JSON.stringify(value)
    return json.parseToJsonElement(serialized)
}

internal fun JsonElement.toDynamic(): dynamic {
    val encoded = json.encodeToString(serializer(), this)
    return JSON.parse<dynamic>(encoded)
}
